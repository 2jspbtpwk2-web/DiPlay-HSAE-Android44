package com.shilapi.xcertplay.transport

/**
 * Untagged Ethernet II framing around IPv6 payloads, the shape NCM datagrams carry.
 *
 * The NCM data path moves raw Ethernet frames. This codec is the seam the later Android VPN layer
 * uses to exchange IPv6 packets with the phone; it does not create or own a network interface.
 */
object EthernetIpv6Codec {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6

    private const val HEADER_BYTES = 14

    data class Ipv6Frame(
        val sourceMac: ByteArray,
        val destinationMac: ByteArray,
        val ipv6: ByteArray,
    )

    data class Ipv6FrameView(
        val sourceMac: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    /** Returns null for tagged frames, non-IPv6 frames, or truncated input. */
    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        val view = parseIpv6View(frame) ?: return null
        return Ipv6Frame(
            sourceMac = view.sourceMac,
            destinationMac = frame.copyOfRange(0, 6),
            ipv6 = frame.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength),
        )
    }

    /** Returns an allocation-light view for callers that can consume the payload in place. */
    fun parseIpv6View(frame: ByteArray): Ipv6FrameView? {
        if (frame.size < HEADER_BYTES + IPV6_HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        if ((frame[HEADER_BYTES].toInt() and 0xf0) != 0x60) return null
        val length = IPV6_HEADER_BYTES + readU16(frame, HEADER_BYTES + IPV6_PAYLOAD_LENGTH_OFFSET)
        if (length > frame.size - HEADER_BYTES) return null
        return Ipv6FrameView(
            sourceMac = frame.copyOfRange(6, 12),
            payloadOffset = HEADER_BYTES,
            payloadLength = length,
        )
    }

    fun build(sourceMac: ByteArray, destinationMac: ByteArray, ipv6: ByteArray): ByteArray {
        require(sourceMac.size == MAC_BYTES) { "sourceMac must be $MAC_BYTES bytes" }
        require(destinationMac.size == MAC_BYTES) { "destinationMac must be $MAC_BYTES bytes" }
        require(ipv6.isNotEmpty()) { "ipv6 payload must not be empty" }

        val frame = ByteArray(HEADER_BYTES + ipv6.size)
        destinationMac.copyInto(frame, 0)
        sourceMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        ipv6.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /** Maps an IPv6 multicast destination to its Ethernet 33:33:xx:xx:xx:xx address. */
    fun multicastDestinationMac(ipv6: ByteArray): ByteArray? {
        if (ipv6.size < IPV6_HEADER_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return null
        if ((ipv6[IPV6_DESTINATION_OFFSET].toInt() and 0xff) != 0xff) return null
        return byteArrayOf(
            0x33,
            0x33,
            ipv6[IPV6_DESTINATION_OFFSET + 12],
            ipv6[IPV6_DESTINATION_OFFSET + 13],
            ipv6[IPV6_DESTINATION_OFFSET + 14],
            ipv6[IPV6_DESTINATION_OFFSET + 15],
        )
    }

    /** RFC 4861 response for our address only. TUN has no Ethernet neighbour cache. */
    fun neighborAdvertisement(frame: ByteArray, hostMac: ByteArray, hostAddress: ByteArray): ByteArray? {
        require(hostMac.size == MAC_BYTES && hostAddress.size == 16)
        val view = parseIpv6View(frame) ?: return null
        if (view.payloadLength < ICMPV6_NA_MIN_BYTES) return null
        // Almost every frame is media/control traffic. Reject it in the containing buffer
        // before allocating a second complete IPv6 payload for the uncommon NS validation.
        val start = view.payloadOffset
        if ((frame[start + 6].toInt() and 0xff) != 58 || (frame[start + 7].toInt() and 0xff) != 255 ||
            (frame[start + 40].toInt() and 0xff) != 135 || frame[start + 41].toInt() != 0) return null
        val request = frame.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength)
        if (!request.copyOfRange(48, 64).contentEquals(hostAddress)) return null
        if ((hostAddress[0].toInt() and 0xff) == 0xff || hostAddress.all { it.toInt() == 0 }) return null
        if ((view.sourceMac[0].toInt() and 1) != 0 || view.sourceMac.all { it.toInt() == 0 } ||
            view.sourceMac.contentEquals(hostMac)) return null
        if ((request[8].toInt() and 0xff) == 0xff || icmpv6Sum(request) != 0xffff) return null

        val unspecified = (8 until 24).all { request[it].toInt() == 0 }
        val solicitedGroup = ByteArray(16).apply {
            this[0] = 0xff.toByte(); this[1] = 2; this[11] = 1; this[12] = 0xff.toByte()
            hostAddress.copyInto(this, 13, 13, 16)
        }
        val destination = request.copyOfRange(24, 40)
        if (!destination.contentEquals(hostAddress) && !destination.contentEquals(solicitedGroup)) return null
        if (unspecified && !destination.contentEquals(solicitedGroup)) return null
        var offset = 64
        while (offset < request.size) {
            if (offset + 2 > request.size) return null
            val bytes = (request[offset + 1].toInt() and 0xff) * 8
            if (bytes == 0 || offset + bytes > request.size) return null
            if (request[offset].toInt() == 1 && bytes != 8) return null
            if (unspecified && request[offset].toInt() == 1) return null
            offset += bytes
        }

        val reply = ByteArray(72)
        reply[0] = 0x60; reply[5] = 32; reply[6] = 58; reply[7] = 0xff.toByte()
        hostAddress.copyInto(reply, 8)
        if (unspecified) { reply[24] = 0xff.toByte(); reply[25] = 2; reply[39] = 1 }
        else request.copyInto(reply, 24, 8, 24)
        reply[40] = 136.toByte()
        reply[44] = if (unspecified) 0x20 else 0x60 // Override; Solicited only for a unicast reply.
        hostAddress.copyInto(reply, 48)
        reply[64] = 2; reply[65] = 1; hostMac.copyInto(reply, 66)
        writeIcmpv6Checksum(reply)
        val destinationMac = multicastDestinationMac(reply) ?: view.sourceMac
        return build(hostMac, destinationMac, reply)
    }

    /**
     * A TUN interface has no layer-2 address, so Android emits Neighbor Advertisements without a
     * Target Link-Layer Address option. Add the option required by the Ethernet peer and repair
     * the IPv6 payload length and ICMPv6 checksum.
     */
    fun addNeighborAdvertisementTargetMac(ipv6: ByteArray, hostMac: ByteArray): ByteArray {
        require(hostMac.size == MAC_BYTES) { "hostMac must be $MAC_BYTES bytes" }
        if (ipv6.size < ICMPV6_NA_MIN_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return ipv6
        if ((ipv6[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != ICMPV6_NEXT_HEADER ||
            (ipv6[IPV6_HEADER_BYTES].toInt() and 0xff) != ICMPV6_NEIGHBOR_ADVERTISEMENT
        ) return ipv6

        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        if (payloadBytes < ICMPV6_NA_BYTES || IPV6_HEADER_BYTES + payloadBytes > ipv6.size) return ipv6
        var optionOffset = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
        val payloadEnd = IPV6_HEADER_BYTES + payloadBytes
        while (optionOffset + 2 <= payloadEnd) {
            val optionBytes = (ipv6[optionOffset + 1].toInt() and 0xff) * 8
            if (optionBytes == 0 || optionOffset + optionBytes > payloadEnd) break
            if ((ipv6[optionOffset].toInt() and 0xff) == ICMPV6_TARGET_LINK_LAYER_OPTION &&
                optionBytes >= 8
            ) {
                return ipv6.copyOf().also { result ->
                    hostMac.copyInto(result, optionOffset + 2)
                    writeIcmpv6Checksum(result)
                }
            }
            optionOffset += optionBytes
        }

        val result = ipv6.copyOf(payloadEnd + 8)
        result[IPV6_PAYLOAD_LENGTH_OFFSET] = ((payloadBytes + 8) ushr 8).toByte()
        result[IPV6_PAYLOAD_LENGTH_OFFSET + 1] = (payloadBytes + 8).toByte()
        result[payloadEnd] = ICMPV6_TARGET_LINK_LAYER_OPTION.toByte()
        result[payloadEnd + 1] = 1
        hostMac.copyInto(result, payloadEnd + 2)
        writeIcmpv6Checksum(result)
        return result
    }

    private fun writeIcmpv6Checksum(ipv6: ByteArray) {
        ipv6[IPV6_HEADER_BYTES + 2] = 0
        ipv6[IPV6_HEADER_BYTES + 3] = 0
        val checksum = icmpv6Sum(ipv6).inv() and 0xffff
        ipv6[IPV6_HEADER_BYTES + 2] = (checksum ushr 8).toByte()
        ipv6[IPV6_HEADER_BYTES + 3] = checksum.toByte()
    }

    private fun icmpv6Sum(ipv6: ByteArray): Int {
        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        var sum = 0L
        fun addWords(start: Int, bytes: Int) {
            var offset = start
            val end = start + bytes
            while (offset + 1 < end) {
                sum += readU16(ipv6, offset).toLong()
                offset += 2
            }
            if (offset < end) sum += (ipv6[offset].toInt() and 0xff).toLong() shl 8
        }
        addWords(IPV6_SOURCE_OFFSET, 32)
        sum += (payloadBytes ushr 16).toLong()
        sum += (payloadBytes and 0xffff).toLong()
        sum += ICMPV6_NEXT_HEADER.toLong()
        addWords(IPV6_HEADER_BYTES, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val IPV6_HEADER_BYTES = 40
    private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
    private const val IPV6_NEXT_HEADER_OFFSET = 6
    private const val IPV6_SOURCE_OFFSET = 8
    private const val IPV6_DESTINATION_OFFSET = 24
    private const val ICMPV6_NEXT_HEADER = 58
    private const val ICMPV6_NEIGHBOR_ADVERTISEMENT = 136
    private const val ICMPV6_NA_BYTES = 24
    private const val ICMPV6_NA_MIN_BYTES = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
    private const val ICMPV6_TARGET_LINK_LAYER_OPTION = 2
}
