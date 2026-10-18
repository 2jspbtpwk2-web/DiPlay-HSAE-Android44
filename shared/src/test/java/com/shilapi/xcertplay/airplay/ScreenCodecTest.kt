package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun maximumSignedLengthDoesNotOverflowBoundsCheck() {
        assertUntouched(byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x65, 1))
    }

    @Test
    fun unsignedAndZeroLengthsStayUnchanged() {
        assertUntouched(byteArrayOf(0x80.toByte(), 0, 0, 0, 0x65, 1))
        assertUntouched(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x65, 1))
        assertUntouched(byteArrayOf(0, 0, 0, 0, 0x65, 1))
    }

    @Test
    fun malformedLaterNalNeverPartiallyConvertsEarlierNal() {
        val first = byteArrayOf(0, 0, 0, 2, 0x65, 1)
        assertUntouched(first + byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x41, 1))
        assertUntouched(first + byteArrayOf(0, 0, 0, 5, 0x41, 1))
    }

    @Test
    fun trailingBytesAndShortPayloadsStayUnchanged() {
        val first = byteArrayOf(0, 0, 0, 2, 0x65, 1)
        for (trailing in 1..3) assertUntouched(first + ByteArray(trailing))
        for (size in 0..3) assertUntouched(ByteArray(size))
    }

    @Test
    fun existingAnnexBStaysUnchanged() {
        assertUntouched(byteArrayOf(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41, 2))
    }

    private fun assertUntouched(payload: ByteArray) {
        val original = payload.copyOf()
        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }
}
