package local.airuize.receiver

import java.util.concurrent.atomic.AtomicBoolean

/** A reusable zero PCM frame paced by the monotonic clock. It never accesses microphone APIs. */
internal class SyntheticSilenceSource(frameBytes: Int, frameMillis: Int,
    private val clockNs: () -> Long = { System.nanoTime() },
    private val waitNs: (Long) -> Unit = { java.util.concurrent.locks.LockSupport.parkNanos(it) }) {
    private val stopped = AtomicBoolean()
    private val pcm = ByteArray(frameBytes.also { require(it in 2..256_000 && it % 2 == 0) })
    private val periodNs = frameMillis.also { require(it in 1..1000) } * 1_000_000L
    private var deadline = Long.MIN_VALUE

    fun stop() { stopped.set(true) }

    /** Caller owns the returned frame until the next call. Late sends never cause a catch-up burst. */
    fun nextFrame(): ByteArray? {
        if (stopped.get() || Thread.currentThread().isInterrupted) return null
        val now = clockNs()
        deadline = if (deadline == Long.MIN_VALUE) now else maxOf(deadline + periodNs, now)
        while (!stopped.get() && !Thread.currentThread().isInterrupted) {
            val remaining = deadline - clockNs()
            if (remaining <= 0L) {
                // No consumer should modify this buffer, but enforce the privacy invariant anyway.
                java.util.Arrays.fill(pcm, 0.toByte())
                return pcm
            }
            waitNs(remaining)
        }
        return null
    }
}
