package local.airuize.receiver

/** One AudioTrack, sampled only on its worker. AudioTrack returns an unsigned 32-bit frame count. */
internal class PlaybackHeadProgress(private val frameBytes: Int) {
    private var lastHead = 0L
    private var frames = 0L
    data class Sample(val rawHead: Long, val frames: Long, val advanced: Long, val queuedBytes: Long, val reset: Boolean)
    init { require(frameBytes > 0) }
    fun sample(rawHead: Int, writtenBytes: Long): Sample {
        val head = rawHead.toLong() and 0xffff_ffffL
        val delta = (head - lastHead) and 0xffff_ffffL
        // We sample every 250 ms; a backwards reset must not look like billions of played frames.
        val reset = head < lastHead && delta > 0x7fff_ffffL
        val advanced = if (reset) 0L else delta
        frames += advanced; lastHead = head
        return Sample(head, frames, advanced, (writtenBytes - frames * frameBytes).coerceAtLeast(0), reset)
    }
}
