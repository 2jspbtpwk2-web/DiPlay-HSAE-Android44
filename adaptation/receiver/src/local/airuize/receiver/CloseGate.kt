package local.airuize.receiver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Repeated close callers must wait for disposal, not just observe its start. */
class CloseGate {
    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    fun get(): Boolean = started.get()
    fun close(dispose: () -> Unit) {
        if (started.compareAndSet(false, true)) {
            try { dispose() } finally { finished.countDown() }
        } else {
            try { finished.await() }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        }
    }
}
