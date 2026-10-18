package local.airuize.receiver

/** Bounded shell output: preserve report headers and the latest output/command end marker. */
internal class ShellOutputBuffer(private val maxChars: Int) {
    init { require(maxChars >= 128) }
    data class Completion(val text: String, val exitCode: Int)
    private val value = StringBuilder(minOf(maxChars, 8192))
    private val headChars = maxChars * 3 / 4
    private val tailChars = maxChars - headChars
    private var discarded = 0L

    @Synchronized fun append(chars: CharArray, count: Int) {
        require(count in 0..chars.size)
        val fillHead = minOf(count, (headChars - value.length).coerceAtLeast(0))
        if (fillHead > 0) value.append(chars, 0, fillHead)
        val remaining = count - fillHead
        if (remaining == 0) return
        val existingTail = value.length - headChars
        if (remaining >= tailChars) {
            discarded += existingTail.toLong() + remaining - tailChars
            value.setLength(headChars)
            value.append(chars, count - tailChars, tailChars)
        } else {
            val excess = (existingTail + remaining - tailChars).coerceAtLeast(0)
            if (excess > 0) { value.delete(headChars, headChars + excess); discarded += excess }
            value.append(chars, fillHead, remaining)
        }
    }

    /** The newline and numeric status are required: a split marker is never a completion. */
    @Synchronized fun complete(start: String, end: String): Completion? {
        val prefix = "$end:"
        val endAt = value.indexOf(prefix)
        if (endAt < 0) return null
        val lineEnd = value.indexOf("\n", endAt + prefix.length)
        if (lineEnd < 0) return null
        val status = value.substring(endAt + prefix.length, lineEnd).trim().toIntOrNull() ?: return null
        val startAt = value.indexOf(start)
        var from = if (startAt >= 0 && startAt < endAt) startAt + start.length else 0
        while (from < endAt && (value[from] == '\n' || value[from] == '\r')) from++
        val text = bounded(value.substring(from, endAt), (headChars - from).coerceAtLeast(0))
        value.delete(0, lineEnd + 1)
        discarded = 0
        return Completion(text, status)
    }

    @Synchronized fun snapshot(): String = bounded(value.toString(), headChars)
    @Synchronized fun clear() { value.setLength(0); discarded = 0 }

    private fun bounded(text: String, preservedHead: Int): String {
        if (discarded == 0L) return text.takeLast(maxChars)
        val note = "\n[ROOT output truncated; discardedChars=$discarded]\n"
        val head = minOf(preservedHead, text.length, (maxChars - note.length).coerceAtLeast(0))
        val tail = minOf((maxChars - note.length - head).coerceAtLeast(0), text.length - head)
        return text.take(head) + note + text.takeLast(tail)
    }
}
