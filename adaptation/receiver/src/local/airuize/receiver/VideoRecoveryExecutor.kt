package local.airuize.receiver

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One queued or running request; a slow event socket must never block the codec worker. */
internal class VideoRecoveryExecutor(private val failed: (Exception) -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), ThreadFactory { task ->
            Thread(task, "legacy-video-recovery").apply { isDaemon = true }
        })

    fun request(action: () -> Unit): Boolean {
        if (closed.get() || !pending.compareAndSet(false, true)) return false
        return try {
            executor.execute {
                try { if (!closed.get()) action() }
                catch (error: Exception) { if (!closed.get()) failed(error) }
                finally { pending.set(false) }
            }
            true
        } catch (_: RejectedExecutionException) { pending.set(false); false }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // The session owner closes its event socket during teardown. Do not wait here for a
        // network write to return, especially while a media callback owns the sink monitor.
        executor.shutdownNow()
    }
}
