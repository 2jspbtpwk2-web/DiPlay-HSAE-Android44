package local.airuize.receiver

import android.os.SystemClock
import java.io.Closeable
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Private optional ROOT owner. It never borrows, interrupts or disposes RootShell's
 * connection channel. Only finite logcat -d commands run in this reusable shell. */
internal class HsaeKeyRootChannel : Closeable {
    enum class Problem { ROOT_UNAVAILABLE, ROOT_NOT_GRANTED, LOGCAT_FAILED, TIMEOUT, CLOSED, INTERRUPTED }
    class Failure(val problem: Problem) : java.io.IOException(problem.name)
    private val closed=AtomicBoolean()
    private val monitor=Object()
    private val output=ShellOutputBuffer(256*1024)
    @Volatile private var process:Process?=null
    private var stdin:DataOutputStream?=null
    private var ended=false
    private var ready=false
    private var sequence=0
    private val token=UUID.randomUUID().toString().replace("-", "")

    /** Worker only. No command replay or fallback after a command has been sent. */
    fun snapshot():String {
        if(closed.get()) throw Failure(Problem.CLOSED)
        if(!ready) open()
        val result=command("/system/bin/logcat -d -b main -v threadtime -s HmiManager:V '*:S'",1800)
        if(result.exitCode!=0) { close();throw Failure(Problem.LOGCAT_FAILED) }
        return result.text
    }
    private fun open() {
        val value=try { ProcessBuilder("su").redirectErrorStream(true).start() }
            catch(_:Exception) { throw Failure(Problem.ROOT_UNAVAILABLE) }
        synchronized(monitor) {
            if(closed.get()) { disposeLater(value);throw Failure(Problem.CLOSED) }
            process=value;stdin=DataOutputStream(value.outputStream)
        }
        Thread({
            try {
                InputStreamReader(value.inputStream,Charsets.UTF_8).use { reader ->
                    val chars=CharArray(4096)
                    while(!closed.get()) {
                        val count=reader.read(chars);if(count<0) break
                        synchronized(monitor) { if(process!==value) return@Thread;output.append(chars,count);monitor.notifyAll() }
                    }
                }
            } catch(_:Exception) { /* Process refusal or this owner's close. */ }
            finally { synchronized(monitor) { ended=true;monitor.notifyAll() } }
        },"legacy-hsae-root-reader").apply { isDaemon=true;start() }
        val result=try { command("id",10_000) } catch(error:Failure) {
            close();throw if(error.problem==Problem.CLOSED) error else Failure(Problem.ROOT_NOT_GRANTED)
        }
        if(result.exitCode!=0 || !Regex("(^|[\\r\\n])uid=0(?:\\(|\\s)").containsMatchIn(result.text)) {
            close();throw Failure(Problem.ROOT_NOT_GRANTED)
        }
        ready=true
    }
    private fun command(command:String,timeoutMs:Long):ShellOutputBuffer.Completion=synchronized(monitor) {
        if(closed.get()) throw Failure(Problem.CLOSED)
        val start="@@HK_${token}_${++sequence}_S";val end="@@HK_${token}_${sequence}_E"
        output.clear()
        try {
            val target=stdin ?: throw Failure(Problem.ROOT_UNAVAILABLE)
            target.writeBytes("echo $start\n$command\necho $end:\$?\n");target.flush()
        } catch(error:Failure) { throw error }
        catch(_:Exception) { close();throw Failure(Problem.ROOT_UNAVAILABLE) }
        val began=SystemClock.elapsedRealtime()
        while(true) {
            if(closed.get()) throw Failure(Problem.CLOSED)
            output.complete(start,end)?.let { return@synchronized it }
            if(ended) { close();throw Failure(Problem.ROOT_NOT_GRANTED) }
            val remaining=timeoutMs-(SystemClock.elapsedRealtime()-began)
            if(remaining<=0) { close();throw Failure(Problem.TIMEOUT) }
            try { monitor.wait(minOf(remaining,200L)) }
            catch(_:InterruptedException) { close();Thread.currentThread().interrupt();throw Failure(Problem.INTERRUPTED) }
        }
        @Suppress("UNREACHABLE_CODE") throw Failure(Problem.CLOSED)
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        synchronized(monitor) { monitor.notifyAll() }
        process?.let(::disposeLater)
    }
    private fun disposeLater(value:Process) {
        // Main-thread dismiss only signals; pipe closure and process cleanup stay off UI.
        Thread({
            runCatching { value.destroy() }
            runCatching { value.outputStream.close() };runCatching { value.inputStream.close() };runCatching { value.errorStream.close() }
        },"legacy-hsae-root-close").apply { isDaemon=true;start() }
    }
}
