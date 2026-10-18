package local.airuize.receiver

/** Worker-owned recovery state; compressed inter frames are never dropped independently. */
internal class VideoLatencyPolicy(
    val maxFrameAgeNs: Long = 250_000_000L,
    private val keyFrameRetryNs: Long = 1_000_000_000L,
) {
    var needsKeyFrame = true
        private set
    private var lastRequestNs: Long? = null
    private var renderAfterPtsUs = Long.MAX_VALUE

    fun ageNs(receivedNs: Long, nowNs: Long): Long = (nowNs - receivedNs).coerceAtLeast(0L)
    fun expired(receivedNs: Long, nowNs: Long): Boolean = ageNs(receivedNs, nowNs) > maxFrameAgeNs
    fun requireKeyFrame() { needsKeyFrame = true; renderAfterPtsUs = Long.MAX_VALUE }
    fun accepts(randomAccess: Boolean): Boolean = !needsKeyFrame || randomAccess
    fun queued(ptsUs: Long) {
        if (needsKeyFrame) { renderAfterPtsUs = ptsUs; needsKeyFrame = false }
    }
    fun shouldRender(ptsUs: Long): Boolean = !needsKeyFrame && ptsUs >= renderAfterPtsUs
    fun requestKeyFrameDue(nowNs: Long): Boolean {
        if (!needsKeyFrame) return false
        val last = lastRequestNs
        if (last != null && nowNs - last < keyFrameRetryNs) return false
        lastRequestNs = nowNs
        return true
    }
}

/** Six fixed buckets retain the last five complete seconds and the current partial second. */
internal class VideoFrameRates(private val originNs: Long) {
    data class Snapshot(val seconds: Int, val received: Long, val queued: Long, val output: Long) {
        private fun rate(count: Long): String = if (seconds == 0) "未满1秒" else
            "${count / seconds}.${(count % seconds) * 10 / seconds}"
        fun summary(): String = "近${seconds}个完整秒：接收=${rate(received)}fps；入解码=${rate(queued)}fps；输出释放=${rate(output)}fps"
    }
    private val tags = LongArray(6) { -1L }
    private val received = LongArray(6)
    private val queued = LongArray(6)
    private val output = LongArray(6)
    private fun second(nowNs: Long): Long = ((nowNs - originNs).coerceAtLeast(0L) / 1_000_000_000L)
    @Synchronized fun received(nowNs: Long) { received[slot(nowNs)]++ }
    @Synchronized fun queued(nowNs: Long) { queued[slot(nowNs)]++ }
    @Synchronized fun output(nowNs: Long) { output[slot(nowNs)]++ }
    private fun slot(nowNs: Long): Int {
        val second = second(nowNs)
        val index = (second % 6).toInt()
        if (tags[index] != second) {
            tags[index] = second; received[index] = 0; queued[index] = 0; output[index] = 0
        }
        return index
    }
    @Synchronized fun snapshot(nowNs: Long): Snapshot {
        val until = second(nowNs)
        val from = (until - 5).coerceAtLeast(0L)
        var r = 0L; var q = 0L; var o = 0L
        for (index in tags.indices) if (tags[index] >= from && tags[index] < until) {
            r += received[index]; q += queued[index]; o += output[index]
        }
        return Snapshot((until - from).toInt(), r, q, o)
    }
}
