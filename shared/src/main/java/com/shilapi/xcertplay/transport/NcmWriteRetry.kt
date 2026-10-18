package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** Retain one packet until it is written, cancelled, or its own deadline expires. */
class NcmWriteRetry(
    private val timeoutNanos: Long = 90_000_000_000L,
    private val nanoTime: () -> Long = { System.nanoTime() },
    private val pause: (Long) -> Unit = { LockSupport.parkNanos(it) },
) {
    init { require(timeoutNanos > 0) }

    fun transmit(running: () -> Boolean, attempt: () -> Boolean): Boolean {
        val start = nanoTime()
        var backoff = 1_000_000L
        while (running()) {
            if (attempt()) return true
            val remaining = timeoutNanos - (nanoTime() - start)
            if (remaining <= 0) throw IOException("USB NCM packet remained unwritable for ${timeoutNanos / 1_000_000_000L}s")
            pause(minOf(backoff, remaining))
            backoff = minOf(backoff * 2, 32_000_000L)
        }
        return false
    }
}
