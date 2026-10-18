package com.shilapi.xcertplay.transport

/**
 * Bounded, passive IPv6 transport observations. No addresses, packet contents or TCP
 * sequence/acknowledgement values are retained or included in diagnostic text.
 *
 * Equal TCP header fingerprints only mean an equal header was observed. Pure ACKs,
 * reused ports and unrelated peers can produce the same observation; this is not
 * evidence of a TCP retransmission. Fragments are deliberately not inspected.
 */
class Ipv6PacketDiagnostics(
    private val clockNanos: () -> Long = System::nanoTime,
    private val controlBudgetPerDirection: Int = 12,
    private val airPlayBudgetPerDirection: Int = 40,
    private val otherTcpBudgetPerDirection: Int = 12,
) {
    enum class Direction(val label: String) { RX("usb-in"), TX("usb-out-sent") }

    class Packet internal constructor(
        val bytes: Int,
        val nextHeader: Int,
        val extensions: Int,
        val transport: String,
        val status: String,
        val sourcePort: Int? = null,
        val destinationPort: Int? = null,
        val tcpFlags: Int = 0,
        val tcpPayloadBytes: Int = 0,
        internal val headerFingerprint: Long = 0,
        val icmpType: Int? = null,
    ) {
        val isTcp: Boolean get() = transport == "tcp" && status == "ok"
        val isAirPlay: Boolean get() = isTcp && (sourcePort == 7000 || destinationPort == 7000)
        val syn: Boolean get() = tcpFlags and 0x02 != 0
        val ack: Boolean get() = tcpFlags and 0x10 != 0
        val rst: Boolean get() = tcpFlags and 0x04 != 0
        val fin: Boolean get() = tcpFlags and 0x01 != 0

        fun summary(): String = buildString {
            append("bytes=$bytes next=$nextHeader extensions=$extensions transport=$transport parse=$status")
            if (sourcePort != null && destinationPort != null) append(" ports=$sourcePort->$destinationPort")
            if (isTcp) append(" flags=${flagNames(tcpFlags)} tcpPayloadBytes=$tcpPayloadBytes")
            if (icmpType != null) append(" icmp6Type=$icmpType")
        }

        override fun toString(): String = summary()
    }

    class Observation internal constructor(
        val packet: Packet,
        val elapsedMs: Long,
        val duplicateHeaderObservation: Boolean,
        val firstTcp: Boolean,
        val firstAirPlay: Boolean,
        val logLine: String?,
    )

    private class State(controlBudget: Int, airPlayBudget: Int, otherBudget: Int) {
        var controlLeft = controlBudget.coerceAtLeast(0)
        var airPlayLeft = airPlayBudget.coerceAtLeast(0)
        var otherLeft = otherBudget.coerceAtLeast(0)
        var sawTcp = false
        var sawAirPlay = false
        val recentHeaders = LongArray(64)
        var recentCount = 0
        var recentIndex = 0

        fun noteHeader(fingerprint: Long): Boolean {
            var duplicate = false
            for (index in 0 until recentCount) if (recentHeaders[index] == fingerprint) duplicate = true
            recentHeaders[recentIndex] = fingerprint
            recentIndex = (recentIndex + 1) % recentHeaders.size
            if (recentCount < recentHeaders.size) recentCount++
            return duplicate
        }
    }

    private val startedNanos = clockNanos()
    private val inbound = State(controlBudgetPerDirection, airPlayBudgetPerDirection, otherTcpBudgetPerDirection)
    private val outbound = State(controlBudgetPerDirection, airPlayBudgetPerDirection, otherTcpBudgetPerDirection)

    /** The transmitter calls this after successful USB completion, never on each retry. */
    @Synchronized
    fun observe(direction: Direction, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Observation? {
        val packet = parse(bytes, offset, length) ?: return null
        val elapsedMs = ((clockNanos() - startedNanos) / 1_000_000L).coerceAtLeast(0)
        if (!packet.isTcp) return Observation(packet, elapsedMs, false, false, false, null)
        val state = if (direction == Direction.RX) inbound else outbound
        val firstTcp = !state.sawTcp
        val firstAirPlay = packet.isAirPlay && !state.sawAirPlay
        state.sawTcp = true
        if (packet.isAirPlay) state.sawAirPlay = true
        val duplicate = state.noteHeader(packet.headerFingerprint)
        // NDP/UDP and other TCP traffic never consume the AirPlay or control budgets.
        val hasBudget = when {
            packet.syn || packet.rst || packet.fin -> if (state.controlLeft > 0) { state.controlLeft--; true } else false
            packet.isAirPlay -> if (state.airPlayLeft > 0) { state.airPlayLeft--; true } else false
            else -> if (state.otherLeft > 0) { state.otherLeft--; true } else false
        }
        val line = if (hasBudget || firstTcp || firstAirPlay) buildString {
            append("ncm tcp ${direction.label} bridgeMs=$elapsedMs ${packet.summary()}")
            if (firstTcp) append(" firstTcp=true")
            if (firstAirPlay) append(" firstAirPlay7000=true")
            if (duplicate) append(" duplicateHeaderObservation=true retransmissionProven=false")
        } else null
        return Observation(packet, elapsedMs, duplicate, firstTcp, firstAirPlay, line)
    }

    companion object {
        private const val MAX_EXTENSION_HEADERS = 8

        /** Parses only validated header fields, respecting the IPv6 payload length. */
        fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Packet? {
            if (offset < 0 || length < 40 || offset > bytes.size - length) return null
            if ((bytes[offset].toInt() and 0xf0) != 0x60) return null
            val declaredBytes = 40 + u16(bytes, offset + 4)
            var next = bytes[offset + 6].toInt() and 255
            if (declaredBytes > length) return Packet(length, next, 0, "uninspected", "truncated-ipv6")
            val end = offset + declaredBytes
            var cursor = offset + 40
            var extensions = 0
            fun skipped(reason: String) = Packet(declaredBytes, next, extensions, "uninspected", reason)
            while (next == 0 || next == 43 || next == 60 || next == 51 || next == 44) {
                if (extensions >= MAX_EXTENSION_HEADERS) return skipped("extension-limit")
                if (next == 44) return skipped("fragment-not-inspected")
                if (end - cursor < 2) return skipped("truncated-extension")
                val extensionBytes = if (next == 51) ((bytes[cursor + 1].toInt() and 255) + 2) * 4
                    else ((bytes[cursor + 1].toInt() and 255) + 1) * 8
                if (extensionBytes < 8 || extensionBytes > end - cursor) return skipped("truncated-extension")
                next = bytes[cursor].toInt() and 255
                cursor += extensionBytes
                extensions++
            }
            return when (next) {
                6 -> {
                    if (end - cursor < 20) return skipped("truncated-tcp")
                    val headerBytes = ((bytes[cursor + 12].toInt() and 0xf0) ushr 4) * 4
                    if (headerBytes < 20 || headerBytes > end - cursor) return skipped("invalid-tcp-header")
                    val sourcePort = u16(bytes, cursor)
                    val destinationPort = u16(bytes, cursor + 2)
                    val flags = (bytes[cursor + 13].toInt() and 255) or ((bytes[cursor + 12].toInt() and 1) shl 8)
                    val payloadBytes = end - cursor - headerBytes
                    // Retain a compact header fingerprint, never raw seq/ack or packet contents.
                    var fingerprint = 1125899906842597L
                    for (index in cursor until cursor + 12) fingerprint = fingerprint * 31 + (bytes[index].toInt() and 255)
                    fingerprint = (fingerprint * 31 + flags) * 31 + payloadBytes
                    Packet(declaredBytes, next, extensions, "tcp", "ok", sourcePort, destinationPort, flags, payloadBytes, fingerprint)
                }
                17 -> {
                    if (end - cursor < 8) skipped("truncated-udp")
                    else Packet(declaredBytes, next, extensions, "udp", "ok", u16(bytes, cursor), u16(bytes, cursor + 2))
                }
                58 -> if (cursor >= end) skipped("truncated-icmp6")
                    else Packet(declaredBytes, next, extensions, "icmp6", "ok", icmpType = bytes[cursor].toInt() and 255)
                50 -> skipped("esp-not-inspected")
                59 -> Packet(declaredBytes, next, extensions, "none", "no-next-header")
                else -> skipped("unsupported-next-header")
            }
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

        private fun flagNames(flags: Int): String {
            val names = arrayOf("FIN", "SYN", "RST", "PSH", "ACK", "URG", "ECE", "CWR", "NS")
            val enabled = ArrayList<String>()
            for (index in names.indices) if (flags and (1 shl index) != 0) enabled.add(names[index])
            return if (enabled.isEmpty()) "none" else enabled.joinToString("|")
        }
    }
}
