package local.airuize.receiver

/** Keep actionable stack locations without copying protocol payloads or secrets from messages. */
object FailureDiagnostics {
    fun progress(text: String): String = text
        .replace(Regex("bluetooth=[^ ;]+"), "bluetooth=[redacted]")
        .replace(Regex("usb=[^ ;]+"), "usb=[redacted]")
    fun lines(stage: String, error: Throwable): List<String> {
        val result = ArrayList<String>()
        val seen = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.size < 6 && seen.none { it === current }) {
            val cause = current
            seen.add(cause)
            result.add("${if (seen.size == 1) "失败阶段=$stage" else "原因"}：${cause.javaClass.name}")
            cause.stackTrace.take(16).forEach { result.add("  at $it") }
            current = cause.cause
        }
        return result
    }
}
