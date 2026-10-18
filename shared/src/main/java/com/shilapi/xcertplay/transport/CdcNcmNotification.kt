package com.shilapi.xcertplay.transport

object CdcNcmNotification {
    data class Notification(val code: Int, val value: Int, val interfaceId: Int, val payload: ByteArray)
    fun parse(buffer: ByteArray, length: Int): Notification? {
        if (length < 8 || length > buffer.size || (buffer[0].toInt() and 0xff) != 0xa1) return null
        fun word(offset: Int) = (buffer[offset].toInt() and 0xff) or ((buffer[offset + 1].toInt() and 0xff) shl 8)
        val payloadLength = word(6)
        if (payloadLength > length - 8) return null
        return Notification(buffer[1].toInt() and 0xff, word(2), word(4), buffer.copyOfRange(8, 8 + payloadLength))
    }
}
