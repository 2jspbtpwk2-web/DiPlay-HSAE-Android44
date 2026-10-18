package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class Ipv6PacketDiagnosticsTest {
    @Test fun reportsTcpHeadersWithoutAddressesSequenceValuesOrContents() {
        val packet = tcp(flags = 0x12, payloadBytes = 6)
        // Neither address bytes nor this payload marker may appear in summaries.
        "SECRET".toByteArray().copyInto(packet, 60)
        val parsed = Ipv6PacketDiagnostics.parse(packet)!!
        assertEquals(49152, parsed.sourcePort)
        assertEquals(7000, parsed.destinationPort)
        assertEquals(6, parsed.tcpPayloadBytes)
        assertTrue(parsed.syn); assertTrue(parsed.ack); assertTrue(parsed.isAirPlay)
        val summary = parsed.toString()
        assertTrue(summary.contains("flags=SYN|ACK"))
        assertFalse(summary.contains("SECRET"))
        assertFalse(summary.contains("src=")); assertFalse(summary.contains("dst="))
        assertFalse(summary.contains("seq=")); assertFalse(summary.contains("ack="))
        assertFalse(summary.contains("16909060"))
    }

    @Test fun followsBoundedHopRoutingDestinationAndAhHeaders() {
        val original = tcp(flags = 0x10, payloadBytes = 3)
        val result = ByteArray(original.size + 36)
        original.copyInto(result, 0, 0, 40)
        result[6] = 0
        // Hop-by-hop -> Routing -> Destination options -> Authentication -> TCP.
        result[40] = 43; result[41] = 0
        result[48] = 60; result[49] = 0
        result[56] = 51; result[57] = 0
        result[64] = 6; result[65] = 1
        original.copyInto(result, 76, 40)
        setU16(result, 4, result.size - 40)
        val parsed = Ipv6PacketDiagnostics.parse(result)!!
        assertTrue(parsed.isTcp)
        assertEquals(4, parsed.extensions)
        assertEquals(3, parsed.tcpPayloadBytes)
        assertEquals(7000, parsed.destinationPort)
    }

    @Test fun neverInterpretsFragmentBytesAsTcpPorts() {
        for (fragmentOffset in listOf(0, 8, 0xfff8)) {
            val fragment = ByteArray(68)
            fragment[0] = 0x60; fragment[6] = 44
            setU16(fragment, 4, 28)
            fragment[40] = 6
            setU16(fragment, 42, fragmentOffset)
            val parsed = Ipv6PacketDiagnostics.parse(fragment)!!
            assertEquals("fragment-not-inspected", parsed.status)
            assertFalse(parsed.isTcp)
            assertNull(parsed.sourcePort); assertNull(parsed.destinationPort)
        }
    }

    @Test fun rejectsHeaderTruncationInvalidTcpAndExcessExtensions() {
        assertNull(Ipv6PacketDiagnostics.parse(ByteArray(39)))
        assertNull(Ipv6PacketDiagnostics.parse(tcp(), -1, 60))
        assertNull(Ipv6PacketDiagnostics.parse(tcp(), 0, 61))
        assertEquals("truncated-ipv6", Ipv6PacketDiagnostics.parse(tcp().copyOf(55))!!.status)
        assertEquals("invalid-tcp-header", Ipv6PacketDiagnostics.parse(tcp().also { it[52] = 0x40 })!!.status)
        assertEquals("invalid-tcp-header", Ipv6PacketDiagnostics.parse(tcp().also { it[52] = 0x60 })!!.status)
        val truncated = ByteArray(42).also { it[0] = 0x60; it[6] = 0; it[40] = 6; it[41] = 0; setU16(it, 4, 2) }
        assertEquals("truncated-extension", Ipv6PacketDiagnostics.parse(truncated)!!.status)
        val chain = ByteArray(40 + 9 * 8)
        chain[0] = 0x60; chain[6] = 0; setU16(chain, 4, 9 * 8)
        for (index in 0 until 9) chain[40 + index * 8] = if (index == 8) 6 else 0
        assertEquals("extension-limit", Ipv6PacketDiagnostics.parse(chain)!!.status)
    }

    @Test fun honorsIpv6LengthAndEthernetOffsetWithoutCountingPadding() {
        val original = tcp(payloadBytes = 3)
        val frame = ByteArray(14 + original.size + 16)
        original.copyInto(frame, 14)
        val parsed = Ipv6PacketDiagnostics.parse(frame, 14, frame.size - 14)!!
        assertEquals(63, parsed.bytes)
        assertEquals(3, parsed.tcpPayloadBytes)
        assertFalse(Ipv6PacketDiagnostics.parse(frame, 14, original.size - 1)!!.isTcp)
    }

    @Test fun airPlayBudgetSurvivesNdpAndOtherTcpTrafficAndControlBudgetIsSeparate() {
        val observer = Ipv6PacketDiagnostics(controlBudgetPerDirection = 1, airPlayBudgetPerDirection = 1, otherTcpBudgetPerDirection = 1)
        val ndp = ByteArray(64).also { it[0] = 0x60; it[6] = 58; it[40] = 135.toByte(); setU16(it, 4, 24) }
        repeat(100) { assertNull(observer.observe(Ipv6PacketDiagnostics.Direction.RX, ndp)!!.logLine) }
        repeat(10) { observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp(destinationPort = 8080)) }
        val firstAirPlay = observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp())!!
        assertTrue(firstAirPlay.firstAirPlay)
        assertTrue(firstAirPlay.logLine!!.contains("firstAirPlay7000=true"))
        assertNull(observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp())!!.logLine)
        val reset = observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp(flags = 0x14))!!
        assertTrue(reset.logLine!!.contains("flags=RST|ACK"))
        // TX has its own budget and first-packet markers.
        assertTrue(observer.observe(Ipv6PacketDiagnostics.Direction.TX, tcp())!!.firstTcp)
    }

    @Test fun duplicateHeadersAreLabelledAsObservationsWithoutRetransmissionClaim() {
        val observer = Ipv6PacketDiagnostics()
        val first = observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp())!!
        val duplicate = observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp())!!
        assertFalse(first.duplicateHeaderObservation)
        assertTrue(duplicate.duplicateHeaderObservation)
        assertTrue(duplicate.logLine!!.contains("duplicateHeaderObservation=true retransmissionProven=false"))
        assertFalse(observer.observe(Ipv6PacketDiagnostics.Direction.TX, tcp())!!.duplicateHeaderObservation)
        assertFalse(observer.observe(Ipv6PacketDiagnostics.Direction.RX, tcp().also { it[47] = 2 })!!.duplicateHeaderObservation)
    }

    @Test fun countersKeepDirectionsFlagsLengthsAndFirstTimesThenReset() {
        NcmStats.reset()
        var clock = 0L
        val observer = Ipv6PacketDiagnostics(clockNanos = { clock })
        fun note(direction: Ipv6PacketDiagnostics.Direction, bytes: ByteArray) {
            clock += 2_000_000
            NcmStats.noteTransport(direction, observer.observe(direction, bytes)!!)
        }
        note(Ipv6PacketDiagnostics.Direction.RX, tcp(flags = 2))
        note(Ipv6PacketDiagnostics.Direction.TX, tcp(flags = 0x12))
        note(Ipv6PacketDiagnostics.Direction.RX, tcp(flags = 0x10, payloadBytes = 7))
        note(Ipv6PacketDiagnostics.Direction.RX, tcp(flags = 0x10, payloadBytes = 7))
        note(Ipv6PacketDiagnostics.Direction.TX, tcp(flags = 0x15))
        assertEquals(3L, NcmStats.tcpRx.packets.get())
        assertEquals(1L, NcmStats.tcpRx.syn.get())
        assertEquals(2L, NcmStats.tcpRx.ackFlag.get())
        assertEquals(14L, NcmStats.tcpRx.payloadBytes.get())
        assertEquals(1L, NcmStats.tcpRx.duplicateHeaderObservations.get())
        assertEquals(2L, NcmStats.tcpRx.firstPacketBridgeMs.get())
        assertEquals(1L, NcmStats.tcpTx.synAck.get())
        assertEquals(1L, NcmStats.tcpTx.rst.get())
        assertEquals(1L, NcmStats.tcpTx.fin.get())
        assertTrue(NcmStats.snapshot().contains("tcpRxAirPlay7000=3"))
        NcmStats.reset()
        assertEquals(0L, NcmStats.tcpRx.packets.get())
        assertEquals(-1L, NcmStats.tcpTx.firstAirPlayBridgeMs.get())
    }

    private fun tcp(flags: Int = 0x10, payloadBytes: Int = 0, destinationPort: Int = 7000): ByteArray =
        ByteArray(60 + payloadBytes).also {
            it[0] = 0x60; it[6] = 6; it[7] = 64
            for (index in 8 until 40) it[index] = index.toByte()
            setU16(it, 4, 20 + payloadBytes)
            setU16(it, 40, 49152); setU16(it, 42, destinationPort)
            it[44] = 1; it[45] = 2; it[46] = 3; it[47] = 4
            it[52] = 0x50; it[53] = flags.toByte()
        }

    private fun setU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
    }
}
