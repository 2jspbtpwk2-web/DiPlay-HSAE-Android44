package local.airuize.receiver

/** Low-frequency main-loop observation; neither display FPS nor a benchmark. No input data. */
internal class UiTimingStats {
    private var dueMs = -1L
    private var samples = 0L
    private var over50 = 0L
    private var over200 = 0L
    private var maximumMs = 0L
    fun resume(nowMs: Long) { dueMs = nowMs }
    fun pause() { dueMs = -1L }
    fun arrived(nowMs: Long) {
        if (dueMs < 0) return
        val late = (nowMs - dueMs).coerceAtLeast(0)
        samples++
        if (late > 50) over50++
        if (late > 200) over200++
        maximumMs = maxOf(maximumMs, late)
    }
    fun schedule(nowMs: Long, intervalMs: Long) { dueMs = nowMs + intervalMs }
    fun summary(): String = "UI主线程调度：samples=$samples lateOver50ms=$over50 lateOver200ms=$over200 maxLateMs=$maximumMs；前台800ms低频采样，不是画面帧率"
}
