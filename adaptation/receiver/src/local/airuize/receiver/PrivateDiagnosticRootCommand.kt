package local.airuize.receiver

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One diagnostic command, one private su owner. Never touches RootShell's process or locks.
 * The caller transfers its private script file and supplies cooperative cleanup for owned children.
 * The entire deadline starts at construction/submission, including authorization and scheduling. */
internal class PrivateDiagnosticRootCommand @JvmOverloads constructor(
    private val script:File, private val timeoutMillis:Long,
    private val signalCancel:(()->Unit)?=null, private val afterCleanup:(()->Unit)?=null,
    private val maximumOutputChars:Int=256*1024,
) : Closeable {
    data class Result(val ok:Boolean,val text:String)
    private class End(val reason:String):java.io.IOException(reason)
    private val submittedNs=System.nanoTime()
    private val allowanceNs=TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val closed=AtomicBoolean()
    private val executing=AtomicBoolean()
    private val monitor=Object()
    // Reserve framing/cleanup report metadata within the caller's total output budget.
    private val output=ShellOutputBuffer(maximumOutputChars-1024)
    private val cleanupDone=CountDownLatch(1)
    private val token=UUID.randomUUID().toString().replace("-", "")
    @Volatile private var process:Process?=null
    @Volatile private var scriptStarted=false
    private var stdin:DataOutputStream?=null
    private var ended=false
    private var sequence=0
    private var executeRunning=false
    private var readerRunning=false
    private var resourcesCleaned=false
    private var cancelCleanup=true
    init { require(timeoutMillis in 1L..120_000L);require(maximumOutputChars in 4096..1024*1024) }
    // Allocate cleanup ownership before a process can exist. If this Thread.start fails,
    // construction fails and the caller still owns/deletes its private script file.
    private val cleanupWorker=Thread({cleanupResources()},"legacy-private-diag-root-close").apply {isDaemon=true;start()}
    fun hasStartedScript():Boolean=scriptStarted

    fun execute():Result {
        synchronized(monitor) {
            if(!executing.compareAndSet(false,true)) return Result(false,"PRIVATE_ROOT_ALREADY_EXECUTED; command not replayed")
            if(closed.get()) return Result(false,header("CANCELLED",0L))
            executeRunning=true
        }
        var completed=false
        val queuedMs=((System.nanoTime()-submittedNs).coerceAtLeast(0L)/1_000_000L)
        try {
            checkTime()
            val value=try { ProcessBuilder("su").redirectErrorStream(true).start() }
                catch(_:Exception) { throw End("ROOT_UNAVAILABLE") }
            synchronized(monitor) {
                // Even a cancelled launch is published to the original cleanup owner.
                // No late independent disposer may outlive a successful awaitCleanup().
                process=value;monitor.notifyAll()
                if(closed.get()) throw End("CANCELLED")
                stdin=DataOutputStream(value.outputStream);readerRunning=true
            }
            var readerStarted=false
            try { Thread({
                try {
                    InputStreamReader(value.inputStream,Charsets.UTF_8).use { reader ->
                        val chars=CharArray(4096)
                        while(!closed.get()) {
                            val count=reader.read(chars);if(count<0) break
                            synchronized(monitor) { output.append(chars,count);monitor.notifyAll() }
                        }
                    }
                } catch(_:Exception) { /* This owner's refusal, exit or cancellation. */ }
                finally { synchronized(monitor) { ended=true;readerRunning=false;maybeCleanupDone();monitor.notifyAll() } }
            },"legacy-private-diag-root-reader").apply { isDaemon=true;start() };readerStarted=true
            } finally {if(!readerStarted) synchronized(monitor) {readerRunning=false;maybeCleanupDone();monitor.notifyAll()} }
            val identity=command("id",minOf(10_000_000_000L,remainingNs()))
            if(identity.exitCode!=0 || !Regex("(^|[\\r\\n])uid=0(?:\\(|\\s)").containsMatchIn(identity.text))
                throw End("ROOT_NOT_GRANTED")
            checkTime()
            scriptStarted=true
            val result=command("sh "+quote(script.absolutePath),remainingNs())
            completed=true
            return Result(result.exitCode==0,header(if(result.exitCode==0) "DONE" else "EXIT_${result.exitCode}",queuedMs)+result.text)
        } catch(error:End) {
            return Result(false,header(error.reason,queuedMs)+output.snapshot())
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(false,header("INTERRUPTED",queuedMs)+output.snapshot())
        } catch(error:Exception) {
            return Result(false,header("FAIL_${error.javaClass.simpleName}",queuedMs)+output.snapshot())
        } finally {
            finish(cancel=!completed)
            synchronized(monitor) {executeRunning=false;maybeCleanupDone();monitor.notifyAll()}
        }
    }
    private fun header(reason:String,queuedMs:Long):String=
        "PRIVATE_ROOT status=${reason.take(64)} privateOwner=true submittedDeadlineMs=$timeoutMillis queuedBeforeExecuteMs=$queuedMs elapsedMs=${(System.nanoTime()-submittedNs).coerceAtLeast(0L)/1_000_000L} noReplay=true\n"
    private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
    private fun remainingNs():Long=allowanceNs-(System.nanoTime()-submittedNs)
    private fun checkTime() {
        if(closed.get()) throw End("CANCELLED")
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        if(remainingNs()<=0L) throw End("TIMEOUT")
    }
    private fun command(text:String,stageAllowanceNs:Long):ShellOutputBuffer.Completion=synchronized(monitor) {
        checkTime()
        val stageStarted=System.nanoTime()
        val start="@@PD_${token}_${++sequence}_S";val end="@@PD_${token}_${sequence}_E"
        output.clear()
        val target=stdin ?: throw End("ROOT_UNAVAILABLE")
        target.writeBytes("echo $start\n$text\necho $end:\$?\n");target.flush()
        while(true) {
            checkTime()
            output.complete(start,end)?.let {return@synchronized it}
            if(ended) throw End("ROOT_CLOSED")
            val remaining=minOf(remainingNs(),stageAllowanceNs-(System.nanoTime()-stageStarted))
            if(remaining<=0L) throw End("TIMEOUT")
            monitor.wait(minOf(200L,TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)))
        }
        @Suppress("UNREACHABLE_CODE") throw End("ROOT_CLOSED")
    }
    /** Signal only. Cooperative child shutdown, pipe close and file deletion stay off UI. */
    override fun close()=finish(cancel=true)
    private fun finish(cancel:Boolean) {
        synchronized(monitor) {
            if(!closed.compareAndSet(false,true)) return
            cancelCleanup=cancel;monitor.notifyAll()
        }
    }
    private fun cleanupResources() {
        var released=false
        try {
            synchronized(monitor) {
                while(!closed.get()) monitor.wait()
                // ProcessBuilder.start may still be in flight. Keep cleanup pending until
                // it either publishes this owner's Process or execute finishes without one.
                while(executeRunning && process==null) monitor.wait()
            }
                if(cancelCleanup && scriptStarted) {
                    runCatching {signalCancel?.invoke()}
                    // The controlled collectors check their private stop file once per second.
                    // Give its trap/known-child cleanup a chance before destroying this shell.
                    try {Thread.sleep(3250L)} catch(_:InterruptedException) {Thread.currentThread().interrupt()}
                }
                released=process?.let(::dispose) ?: true
                if(released) {
                    val extra=runCatching {afterCleanup?.invoke();true}.getOrDefault(false)
                    val removed=extra && runCatching {!script.exists() || script.delete()}.getOrDefault(false)
                    released=removed && extra
                }
        } catch(_:Exception) { /* Cleanup remains explicitly incomplete; never claim release. */
        } finally {synchronized(monitor) {resourcesCleaned=released;maybeCleanupDone();monitor.notifyAll()} }
    }
    private fun maybeCleanupDone() {
        if(closed.get() && resourcesCleaned && !executeRunning && !readerRunning) cleanupDone.countDown()
    }
    /** Worker/test cleanup observation only; UI callers use close() without waiting. */
    fun awaitCleanup(timeoutMs:Long):Boolean=cleanupDone.await(timeoutMs.coerceIn(0L,5000L),TimeUnit.MILLISECONDS)
    private fun dispose(value:Process):Boolean {
        runCatching {value.destroy()}
        runCatching {value.outputStream.close()};runCatching {value.inputStream.close()};runCatching {value.errorStream.close()}
        val began=System.nanoTime()
        while(System.nanoTime()-began<2_000_000_000L) {
            try {value.exitValue();return true} catch(_:IllegalThreadStateException) {}
            try {Thread.sleep(20)} catch(_:InterruptedException) {Thread.currentThread().interrupt();return false}
        }
        return try {value.exitValue();true} catch(_:IllegalThreadStateException) {false}
    }
}
