package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NcmRecoveryTest {
    private val host = ip(2)
    private val phone = ip(3)
    private val hostMac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val phoneMac = byteArrayOf(2, 0, 0, 0, 0, 3)

    @Test fun retriesPendingPacketWithoutAnotherTunRead() {
        var clock = 0L; var calls = 0
        val bytes = byteArrayOf(1, 2, 3)
        val observed = ArrayList<ByteArray>()
        val retry = NcmWriteRetry(1_000_000_000L, { clock }, { clock += it })
        assertTrue(retry.transmit({ true }) { observed.add(bytes); ++calls == 4 })
        assertEquals(4, calls)
        assertTrue(observed.all { it === bytes })
        assertTrue(clock > 0)
    }

    @Test fun permanentFailureHasDeadlineEvenWhenTunIsSilent() {
        var clock = 0L; var calls = 0
        val retry = NcmWriteRetry(1_000_000_000L, { clock }, { clock += it })
        try {
            retry.transmit({ true }) { calls++; clock += 200_000_000L; false }
            fail("must time out")
        } catch (_: IOException) { assertTrue(calls in 4..6); assertTrue(clock >= 1_000_000_000L) }
    }

    @Test fun cancelAndFatalDoNotStartAnotherWrite() {
        var running = true; var calls = 0
        assertFalse(NcmWriteRetry().transmit({ running }) { calls++; running = false; false })
        assertEquals(1, calls)
        calls = 0
        try { NcmWriteRetry().transmit({ true }) { calls++; throw IOException("detached") }; fail("fatal") }
        catch (_: IOException) { assertEquals(1, calls) }
    }

    @Test fun decodesActualVehicleNetworkConnectionNotifications() {
        val up = CdcNcmNotification.parse(hex("a100010004000000"), 8)!!
        assertEquals(0, up.code); assertEquals(1, up.value); assertEquals(4, up.interfaceId)
        val down = CdcNcmNotification.parse(hex("a100000004000000"), 8)!!
        assertEquals(0, down.value)
        assertNull(CdcNcmNotification.parse(hex("a12a000004000800"), 8))
        assertNull(CdcNcmNotification.parse(hex("a100010004000000"), 9))
    }

    @Test fun repliesToPhoneNeighborSolicitationWithValidEthernetAdvertisement() {
        val response = EthernetIpv6Codec.neighborAdvertisement(ns(), hostMac, host)!!
        val ipv6 = EthernetIpv6Codec.parseIpv6(response)!!
        assertArrayEquals(phoneMac, ipv6.destinationMac)
        assertArrayEquals(hostMac, ipv6.sourceMac)
        assertArrayEquals(host, ipv6.ipv6.copyOfRange(8, 24))
        assertArrayEquals(phone, ipv6.ipv6.copyOfRange(24, 40))
        assertEquals(136, ipv6.ipv6[40].toInt() and 255)
        assertEquals(0x60, ipv6.ipv6[44].toInt() and 255)
        assertArrayEquals(hostMac, ipv6.ipv6.copyOfRange(66, 72))
        assertEquals(0xffff, sum(ipv6.ipv6))
        assertArrayEquals(response, Ntb16Codec.parse(Ntb16Codec.build(response, 1)).single())
    }

    @Test fun dadForOurAddressUsesAllNodesAndNoSolicitedFlag() {
        val response = EthernetIpv6Codec.neighborAdvertisement(ns(dad = true), hostMac, host)!!
        val ipv6 = EthernetIpv6Codec.parseIpv6(response)!!
        assertEquals(0x20, ipv6.ipv6[44].toInt() and 255)
        assertArrayEquals(hex("ff020000000000000000000000000001"), ipv6.ipv6.copyOfRange(24, 40))
        assertArrayEquals(hex("333300000001"), ipv6.destinationMac)
        assertEquals(0xffff, sum(ipv6.ipv6))
        assertNull(EthernetIpv6Codec.neighborAdvertisement(ns(target = phone, dad = true), hostMac, host))
    }

    @Test fun rejectsCorruptOrOffLinkNeighborRequests() {
        val badChecksum = ns().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(EthernetIpv6Codec.neighborAdvertisement(badChecksum, hostMac, host))
        assertNull(EthernetIpv6Codec.neighborAdvertisement(ns(hop = 254), hostMac, host))
        assertNull(EthernetIpv6Codec.neighborAdvertisement(ns(target = phone), hostMac, host))
        assertNull(EthernetIpv6Codec.neighborAdvertisement(ns(badOption = true), hostMac, host))
    }

    @Test fun ignoresEthernetPaddingAndRejectsTruncatedIpv6() {
        val request = ns()
        val view = EthernetIpv6Codec.parseIpv6View(request + ByteArray(12))!!
        assertEquals(request.size - 14, view.payloadLength)
        assertNull(EthernetIpv6Codec.parseIpv6View(request.copyOf(request.size - 1)))
    }

    @Test fun ordinaryTcpUdpAndOtherIcmpTrafficCannotProduceNeighborReply() {
        val request = ns()
        val nonNsPackets = listOf(
            request.copyOf().apply { this[14 + 6] = 6 },
            request.copyOf().apply { this[14 + 6] = 17 },
            request.copyOf().apply { this[14 + 7] = 254.toByte() },
            request.copyOf().apply { this[14 + 40] = 136.toByte() },
            request.copyOf().apply { this[14 + 41] = 1 },
        )
        for (packet in nonNsPackets) {
            val before = packet.copyOf()
            assertNull(EthernetIpv6Codec.neighborAdvertisement(packet, hostMac, host))
            assertArrayEquals(before, packet)
        }
    }

    @Test fun unicastAndMulticastSolicitationsKeepIdenticalValidReply() {
        val multicastReply = EthernetIpv6Codec.neighborAdvertisement(ns(), hostMac, host)!!
        val unicastReply = EthernetIpv6Codec.neighborAdvertisement(ns(unicastDestination = true), hostMac, host)!!
        assertArrayEquals(multicastReply, unicastReply)
        assertEquals(0xffff, sum(EthernetIpv6Codec.parseIpv6(unicastReply)!!.ipv6))
    }

    @Test fun neighborReplyIgnoresPaddingButNeverReadsItAsDeclaredPayload() {
        val request = ns()
        val before = request.copyOf()
        val expected = EthernetIpv6Codec.neighborAdvertisement(request, hostMac, host)!!
        assertArrayEquals(expected, EthernetIpv6Codec.neighborAdvertisement(request + ByteArray(32) { 0x7f }, hostMac, host))
        assertArrayEquals(before, request)
        val shortDeclaredPayload = request.copyOf().apply { this[14 + 5] = 23 }
        assertNull(EthernetIpv6Codec.neighborAdvertisement(shortDeclaredPayload, hostMac, host))
        assertNull(EthernetIpv6Codec.neighborAdvertisement(request.copyOf(14 + 40), hostMac, host))
    }

    private fun ns(target: ByteArray = host, dad: Boolean = false, hop: Int = 255, badOption: Boolean = false,
                   unicastDestination: Boolean = false): ByteArray {
        val packet = ByteArray(if (dad) 64 else 72)
        packet[0] = 0x60; packet[5] = (packet.size - 40).toByte(); packet[6] = 58; packet[7] = hop.toByte()
        if (!dad) phone.copyInto(packet, 8)
        (if (unicastDestination) host else hex("ff0200000000000000000001ff000002")).copyInto(packet, 24)
        packet[40] = 135.toByte(); target.copyInto(packet, 48)
        if (!dad) { packet[64] = 1; packet[65] = if (badOption) 0 else 1; phoneMac.copyInto(packet, 66) }
        val checksum = sum(packet).inv() and 0xffff
        packet[42] = (checksum ushr 8).toByte(); packet[43] = checksum.toByte()
        return EthernetIpv6Codec.build(phoneMac, hex("3333ff000002"), packet)
    }

    private fun sum(bytes: ByteArray): Int {
        var s = (bytes.size - 40).toLong() + 58
        fun words(start: Int, end: Int) { for (i in start until end step 2) s += ((bytes[i].toInt() and 255) shl 8) + (bytes[i + 1].toInt() and 255) }
        words(8, 40); words(40, bytes.size)
        while (s ushr 16 != 0L) s = (s and 65535) + (s ushr 16)
        return s.toInt()
    }
    private fun ip(last: Int) = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = last.toByte() }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
