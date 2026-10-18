package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.NcmStats
import com.shilapi.xcertplay.transport.NcmWriteRetry
import com.shilapi.xcertplay.transport.Ipv6PacketDiagnostics
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Responds to NDP for the host on the Ethernet side because
 * TUN has no link-layer address and does not behave like LIVI's TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onError: (Throwable) -> Unit,
    private val report: (String) -> Unit = {},
    private val hostAddress: ByteArray = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
        require(hostAddress.size == 16)
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
    private val packetDiagnostics = Ipv6PacketDiagnostics()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private data class PendingPacket(val bytes: ByteArray, val ethernet: Boolean = false, val ndp: Boolean = false)
    private val outbound = ArrayBlockingQueue<PendingPacket>(64)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread
    private lateinit var txThread: Thread

    private fun info(message: String) { Log.i(TAG, message); report(message) }
    private fun fail(error: IOException) {
        if (running.compareAndSet(true, false)) onError(error)
    }
    private fun enqueue(packet: PendingPacket) {
        if (running.get() && !outbound.offer(packet)) {
            NcmStats.txQueueOverflow.incrementAndGet()
            fail(IOException("NCM outbound queue full: ${NcmStats.snapshot()}"))
        }
    }

    @Synchronized fun start() {
        check(!closed.get()) { "bridge is closed" }
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        txThread = Thread(::runTransmit, "ncm-usb-out").apply { isDaemon = true }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
        }
        txThread.start(); ncmToTunThread.start(); tunToNcmThread.start()
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        outbound.clear()
        if (::txThread.isInitialized) txThread.interrupt()
        if (::tunToNcmThread.isInitialized) tunToNcmThread.interrupt()
        ncm.close()
        tun.close()
        if (::ncmToTunThread.isInitialized) join(ncmToTunThread)
        if (::tunToNcmThread.isInitialized) join(tunToNcmThread)
        if (::txThread.isInitialized) join(txThread)
    }

    private fun runNcmToTun() {
        try {
            val output = FileOutputStream(tun.fileDescriptor)
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                NcmStats.parsedIpv6.incrementAndGet()
                if ((ipv6.sourceMac[0].toInt() and 1) == 0 && ipv6.sourceMac.any { it.toInt() != 0 }) peerMac = ipv6.sourceMac
                if (!loggedInbound) {
                    loggedInbound = true
                    info("ncm first inbound ipv6 bytes=${ipv6.payloadLength}; network addresses omitted")
                }
                observeTransport(Ipv6PacketDiagnostics.Direction.RX, frame, ipv6.payloadOffset, ipv6.payloadLength)
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    info("ncm inbound ${frame.summary(ipv6.payloadOffset, ipv6.payloadLength)}")
                }
                val neighborReply = EthernetIpv6Codec.neighborAdvertisement(frame, hostMac, hostAddress)
                if (neighborReply != null) {
                    NcmStats.ndpSolicitations.incrementAndGet()
                    enqueue(PendingPacket(neighborReply, ethernet = true, ndp = true))
                    if (NcmStats.ndpSolicitations.get() <= 8) info("ncm host neighbor solicitation; advertisement queued")
                    continue
                }
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
                NcmStats.tunWritten.incrementAndGet()
            }
        } catch (error: IOException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IOException("NCM receive failed", error))
        }
    }

    private fun runTunToNcm() {
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            val input = FileInputStream(tun.fileDescriptor)
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    fail(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    info("ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    info("ncm outbound queued ${ipv6.summary(0, ipv6.size)}")
                }
                enqueue(PendingPacket(ipv6))
            }
        } catch (error: IOException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IOException("TUN read failed", error))
        }
    }

    private fun runTransmit() {
        try {
            val retry = NcmWriteRetry()
            while (running.get()) {
                val packet = outbound.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val frame = if (packet.ethernet) packet.bytes else {
                    val multicastMac = EthernetIpv6Codec.multicastDestinationMac(packet.bytes)
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (running.get() && multicastMac == null && peerMac == null) {
                        if (!loggedWaitingForPeer) { loggedWaitingForPeer = true; info("ncm waiting for peer MAC; retaining outbound packet") }
                        if (System.nanoTime() >= deadline) throw IOException("NCM peer MAC was not learned within 10s")
                        LockSupport.parkNanos(1_000_000L)
                    }
                    if (!running.get()) return
                    EthernetIpv6Codec.build(hostMac, multicastMac ?: peerMac ?: continue, packet.bytes)
                }
                val sent = retry.transmit(running::get) {
                    when (ncm.send(frame, WRITE_TIMEOUT_MILLIS)) {
                        NcmUsbBridge.TxResult.SENT -> true
                        NcmUsbBridge.TxResult.RETRYABLE -> false
                        NcmUsbBridge.TxResult.FATAL -> throw IOException("USB NCM fatal write: ${NcmStats.snapshot()}")
                    }
                }
                if (!sent) return
                observeTransport(Ipv6PacketDiagnostics.Direction.TX, frame, ETHERNET_HEADER_BYTES, frame.size - ETHERNET_HEADER_BYTES)
                if (!loggedOutbound) { loggedOutbound = true; info("ncm bulk-out became ready; first complete NTB written") }
                if (packet.ndp) {
                    NcmStats.ndpReplies.incrementAndGet()
                    if (NcmStats.ndpReplies.get() <= 8) info("ncm neighbor advertisement sent; ${NcmStats.snapshot()}")
                }
            }
        } catch (error: InterruptedException) {
            if (running.get()) fail(IOException("NCM transmitter interrupted", error))
            Thread.currentThread().interrupt()
        } catch (error: IOException) { fail(error) }
        catch (error: RuntimeException) { fail(IOException("NCM transmitter failed", error)) }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun observeTransport(direction: Ipv6PacketDiagnostics.Direction, packet: ByteArray, offset: Int, length: Int) {
        val observation = packetDiagnostics.observe(direction, packet, offset, length) ?: return
        NcmStats.noteTransport(direction, observation)
        observation.logLine?.let(::info)
    }

    private fun ByteArray.summary(offset: Int, length: Int): String =
        Ipv6PacketDiagnostics.parse(this, offset, length)?.summary() ?: "unparsed bytes=$length"

    private companion object {
        const val TAG = "xcertplay-usb"
        const val ETHERNET_HEADER_BYTES = 14
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
