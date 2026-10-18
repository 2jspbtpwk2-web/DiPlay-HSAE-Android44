package local.airuize.receiver

/** RECORD starts a bounded observation window; it is not evidence of rendered media. */
class ConnectionObservation(private val startedMs: Long, private val startupMs: Long = 95_000L,
                            private val mediaMs: Long = 60_000L) {
    var firstActiveMs: Long? = null
        private set
    var activeNow = false
        private set
    var disconnects = 0
        private set
    fun update(nowMs: Long, active: Boolean) {
        if (active && firstActiveMs == null) firstActiveMs = nowMs
        if (activeNow && !active) disconnects++
        activeNow = active
    }
    fun shouldContinue(nowMs: Long): Boolean = nowMs < (firstActiveMs?.plus(mediaMs) ?: (startedMs + startupMs))
    fun summary(nowMs: Long): String = "RECORD曾到达=${firstActiveMs != null} 当前会话=$activeNow " +
        "首次激活后观察Ms=${firstActiveMs?.let { (nowMs-it).coerceAtLeast(0) } ?: 0} 观察中断开次数=$disconnects；画面/声音另看媒体计数"
}
