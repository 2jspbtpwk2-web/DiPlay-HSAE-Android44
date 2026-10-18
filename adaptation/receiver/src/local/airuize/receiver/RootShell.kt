package local.airuize.receiver

import android.content.Context
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Reuse the authorized su shell while it is healthy; never replay a timed out command. */
object RootShell {
    private const val OUTPUT_CHARS = 1024 * 1024
    private val lock = Object()
    private val execLock = Object()
    private val seq = AtomicInteger()
    private var state = 0 // 0 not started/invalidated; 1 healthy; 2 interactive su unsupported
    private class Channel(val process: Process, val stdin: DataOutputStream) {
        val output = ShellOutputBuffer(OUTPUT_CHARS)
        var ended = false // guarded by lock
    }
    private var channel: Channel? = null
    data class Result(val ok: Boolean, val text: String)
    private data class AwaitResult(val completion: ShellOutputBuffer.Completion?, val failure: String? = null)

    /** Only streams and the exact Process created here are closed; no PID lookup or global kill. */
    private fun dispose(p: Process) {
        runCatching { p.destroy() }
        runCatching { p.outputStream.close() }
        runCatching { p.inputStream.close() }
        runCatching { p.errorStream.close() }
    }
    private fun invalidate(value: Channel, unsupported: Boolean = false) {
        // All callers hold lock. A retired reader cannot append to a replacement channel.
        if (channel === value) { channel = null; state = if (unsupported) 2 else 0 }
        value.ended = true
        dispose(value.process)
        lock.notifyAll()
    }
    private fun exited(p: Process): Boolean = try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }

    private fun openInteractive(): Channel? = synchronized(lock) {
        if (state == 2) return@synchronized null
        channel?.let { existing ->
            if (state == 1 && !existing.ended && !exited(existing.process)) return@synchronized existing
            invalidate(existing)
        }
        var created: Channel? = null
        try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val value = Channel(p, DataOutputStream(p.outputStream))
            created = value; channel = value
            Thread({
                try {
                    InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                        val chars = CharArray(4096)
                        while (true) {
                            val count = reader.read(chars)
                            if (count < 0) break
                            synchronized(lock) {
                                if (channel !== value) return@Thread
                                value.output.append(chars, count); lock.notifyAll()
                            }
                        }
                    }
                } catch (_: IOException) { /* Refusal, exit, or closing this channel. */ }
                finally { synchronized(lock) { value.ended = true; lock.notifyAll() } }
            }, "root-shell-reader").apply { isDaemon = true; start() }
            val id = seq.incrementAndGet()
            val start = "@@RS_S_$id"; val end = "@@RS_E_$id"
            value.stdin.writeBytes("echo $start\nid\necho $end:\$?\n"); value.stdin.flush()
            val result = await(value, start, end, 10000).completion
            if (result?.exitCode == 0 && result.text.contains("uid=0")) {
                state = 1; value
            } else { invalidate(value, true); null }
        } catch (error: InterruptedException) {
            created?.let { invalidate(it) }
            Thread.currentThread().interrupt(); throw error
        } catch (_: Exception) {
            created?.let { invalidate(it, true) }
            state = 2; null
        }
    }

    private fun await(value: Channel, start: String, end: String, timeoutMs: Long): AwaitResult = synchronized(lock) {
        val began = System.nanoTime()
        val allowance = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        while (true) {
            value.output.complete(start, end)?.let { return@synchronized AwaitResult(it) }
            if (channel !== value || value.ended) return@synchronized AwaitResult(null, "CLOSED")
            val remaining = allowance - (System.nanoTime() - began)
            if (remaining <= 0) return@synchronized AwaitResult(null, "TIMEOUT ${timeoutMs}ms")
            lock.wait(minOf(200L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))
        }
        @Suppress("UNREACHABLE_CODE") AwaitResult(null, "CLOSED")
    }
    private fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"
    private fun withStatus(prefix: String, text: String): String {
        if (prefix.length + text.length <= OUTPUT_CHARS) return prefix + text
        val notice="\n[ROOT middle truncated to retain status and report headers]\n"
        val budget=(OUTPUT_CHARS-prefix.length-notice.length).coerceAtLeast(0)
        val head=budget*3/4
        return prefix+text.take(head)+notice+text.takeLast(budget-head)
    }

    private fun execInteractive(script: File, timeoutMs: Long): Result? {
        val value = openInteractive() ?: return null
        return synchronized(lock) {
            val id = seq.incrementAndGet()
            val start = "@@RS_S_$id"; val end = "@@RS_E_$id"
            try {
                value.output.clear()
                value.stdin.writeBytes("echo $start\nsh ${shellQuote(script.absolutePath)}\necho $end:\$?\n")
                value.stdin.flush()
                val result = await(value, start, end, timeoutMs)
                val completed = result.completion
                if (completed == null) {
                    invalidate(value)
                    Result(false, "[ROOT 常驻通道 ${result.failure}；通道已废弃，当前命令未重放]")
                } else Result(completed.exitCode == 0,
                    if (completed.exitCode == 0) completed.text else withStatus("[ROOT exit=${completed.exitCode}]\n", completed.text))
            } catch (error: InterruptedException) {
                invalidate(value); Thread.currentThread().interrupt()
                Result(false, "[ROOT 常驻通道 INTERRUPTED；通道已废弃，当前命令未重放]")
            } catch (error: Exception) {
                invalidate(value)
                Result(false, "[ROOT 常驻通道 FAIL ${error.javaClass.simpleName}；通道已废弃，当前命令未重放]")
            }
        }
    }

    private fun execProcess(argv: Array<String>, timeoutMs: Long): Result {
        var process: Process? = null
        var drain: Thread? = null
        try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start(); process = p
            p.outputStream.close()
            val output = ShellOutputBuffer(OUTPUT_CHARS)
            drain = Thread({
                try {
                    InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                        val chars = CharArray(4096)
                        while (true) { val count = reader.read(chars); if (count < 0) break; output.append(chars, count) }
                    }
                } catch (_: IOException) { /* Closing only this fallback process. */ }
            }, "root-fallback-reader").apply { isDaemon = true; start() }
            val began = System.nanoTime()
            val allowance = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
            var status: Int? = null
            while (status == null && System.nanoTime() - began < allowance) {
                status = try { p.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (status == null) Thread.sleep(20)
            }
            if (status == null) dispose(p)
            drain.join(500)
            val text = output.snapshot()
            return when {
                status == null -> Result(false, withStatus("[ROOT fallback TIMEOUT ${timeoutMs}ms]\n", text))
                drain.isAlive -> Result(false, withStatus("[ROOT OUTPUT_DRAIN_INCOMPLETE]\n", text))
                status != 0 -> Result(false, withStatus("[ROOT exit=$status]\n", text))
                else -> Result(true, text)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(false, "ROOT_EXEC_INTERRUPTED")
        } catch (error: Exception) {
            return Result(false, "ROOT_EXEC_FAIL ${error.javaClass.simpleName}")
        } finally {
            process?.let(::dispose)
            drain?.interrupt()
        }
    }
    private fun execFallback(script: File, timeoutMs: Long): Result =
        execProcess(arrayOf("su", "-c", "sh ${shellQuote(script.absolutePath)}"), timeoutMs)

    /** Execute only an app-private script. Normal commands share one healthy authorized su. */
    fun execScript(context: Context, script: String, timeoutMs: Long = 20000): Result = synchronized(execLock) {
        var file: File? = null
        try {
            if (Thread.currentThread().isInterrupted) return@synchronized Result(false, "ROOT_SCRIPT_INTERRUPTED")
            file = File.createTempFile("root_cmd_", ".sh", context.cacheDir)
            file.setReadable(false, false); file.setWritable(false, false)
            file.setReadable(true, true); file.setWritable(true, true)
            file.writeText(script, Charsets.UTF_8)
            execInteractive(file, timeoutMs) ?: execFallback(file, timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); Result(false, "ROOT_SCRIPT_INTERRUPTED")
        } catch (error: Exception) {
            Result(false, "ROOT_SCRIPT_FAIL ${error.javaClass.simpleName}")
        } finally { runCatching { file?.delete() } }
    }
}

