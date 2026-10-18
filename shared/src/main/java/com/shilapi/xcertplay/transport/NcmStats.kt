package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicLong

/**
 * Session-scoped counters for the NCM data plane. Reset at the start of every connection
 * attempt so a failed session cannot inflate the next one. The snapshot is emitted verbatim
 * in diagnostics to show exactly where the data plane stopped.
 */
object NcmStats {
    val attemptedTx = AtomicLong()
    val successfulTx = AtomicLong()
    val txBytes = AtomicLong()
    val rxNtb = AtomicLong()
    val rxBytes = AtomicLong()
    val parsedIpv6 = AtomicLong()
    val tunWritten = AtomicLong()
    val consecutiveTxFail = AtomicLong()
    val ndpSolicitations = AtomicLong()
    val ndpReplies = AtomicLong()
    val txQueueOverflow = AtomicLong()
    val tcpRx = TcpCounters()
    val tcpTx = TcpCounters()
    val transportInspectionSkipped = AtomicLong()

    /** Counts header observations, not TCP connection state or proven retransmissions. */
    class TcpCounters {
        val packets = AtomicLong()
        val syn = AtomicLong()
        val synAck = AtomicLong()
        val ackFlag = AtomicLong()
        val rst = AtomicLong()
        val fin = AtomicLong()
        val dataPackets = AtomicLong()
        val payloadBytes = AtomicLong()
        val airPlay7000 = AtomicLong()
        val duplicateHeaderObservations = AtomicLong()
        val firstPacketBridgeMs = AtomicLong(-1)
        val firstAirPlayBridgeMs = AtomicLong(-1)

        internal fun reset() {
            packets.set(0); syn.set(0); synAck.set(0); ackFlag.set(0)
            rst.set(0); fin.set(0); dataPackets.set(0); payloadBytes.set(0)
            airPlay7000.set(0); duplicateHeaderObservations.set(0)
            firstPacketBridgeMs.set(-1); firstAirPlayBridgeMs.set(-1)
        }

        internal fun snapshot(prefix: String): String =
            "${prefix}Packets=${packets.get()} ${prefix}Syn=${syn.get()} ${prefix}SynAck=${synAck.get()} " +
                "${prefix}AckFlag=${ackFlag.get()} ${prefix}Rst=${rst.get()} ${prefix}Fin=${fin.get()} " +
                "${prefix}DataPackets=${dataPackets.get()} ${prefix}PayloadBytes=${payloadBytes.get()} " +
                "${prefix}AirPlay7000=${airPlay7000.get()} ${prefix}DuplicateHeaderObservations=${duplicateHeaderObservations.get()} " +
                "${prefix}FirstBridgeMs=${firstPacketBridgeMs.get()} ${prefix}AirPlayFirstBridgeMs=${firstAirPlayBridgeMs.get()}"
    }

    fun noteTransport(direction: Ipv6PacketDiagnostics.Direction, observation: Ipv6PacketDiagnostics.Observation) {
        val packet = observation.packet
        if (packet.transport == "uninspected") transportInspectionSkipped.incrementAndGet()
        if (!packet.isTcp) return
        val counters = if (direction == Ipv6PacketDiagnostics.Direction.RX) tcpRx else tcpTx
        counters.packets.incrementAndGet()
        if (packet.syn) counters.syn.incrementAndGet()
        if (packet.syn && packet.ack) counters.synAck.incrementAndGet()
        if (packet.ack) counters.ackFlag.incrementAndGet()
        if (packet.rst) counters.rst.incrementAndGet()
        if (packet.fin) counters.fin.incrementAndGet()
        if (packet.tcpPayloadBytes > 0) counters.dataPackets.incrementAndGet()
        counters.payloadBytes.addAndGet(packet.tcpPayloadBytes.toLong())
        counters.firstPacketBridgeMs.compareAndSet(-1, observation.elapsedMs)
        if (packet.isAirPlay) {
            counters.airPlay7000.incrementAndGet()
            counters.firstAirPlayBridgeMs.compareAndSet(-1, observation.elapsedMs)
        }
        if (observation.duplicateHeaderObservation) counters.duplicateHeaderObservations.incrementAndGet()
    }

    @Volatile
    private var lastErrorValue: String? = null

    @Volatile
    private var linkUpValue: Boolean? = null

    fun lastError(): String? = lastErrorValue
    fun linkUp(): Boolean? = linkUpValue

    fun noteError(value: String) {
        lastErrorValue = value
    }
    fun clearError() { lastErrorValue = null }

    fun noteLink(up: Boolean) {
        linkUpValue = up
    }

    fun reset() {
        attemptedTx.set(0)
        successfulTx.set(0)
        txBytes.set(0)
        rxNtb.set(0)
        rxBytes.set(0)
        parsedIpv6.set(0)
        tunWritten.set(0)
        consecutiveTxFail.set(0)
        ndpSolicitations.set(0)
        ndpReplies.set(0)
        txQueueOverflow.set(0)
        tcpRx.reset()
        tcpTx.reset()
        transportInspectionSkipped.set(0)
        lastErrorValue = null
        linkUpValue = null
    }

    fun snapshot(): String =
        "attemptedTx=${attemptedTx.get()} successfulTx=${successfulTx.get()} " +
            "txBytes=${txBytes.get()} rxNtb=${rxNtb.get()} rxBytes=${rxBytes.get()} " +
            "parsedIpv6=${parsedIpv6.get()} tunWritten=${tunWritten.get()} " +
            "consecutiveTxFail=${consecutiveTxFail.get()} " +
            "ndpSolicitations=${ndpSolicitations.get()} ndpReplies=${ndpReplies.get()} txQueueOverflow=${txQueueOverflow.get()} " +
            tcpRx.snapshot("tcpRx") + " " + tcpTx.snapshot("tcpTx") + " transportInspectionSkipped=${transportInspectionSkipped.get()} " +
            "linkUp=${linkUpValue ?: "unknown"} lastError=${lastErrorValue ?: "none"}"
}
