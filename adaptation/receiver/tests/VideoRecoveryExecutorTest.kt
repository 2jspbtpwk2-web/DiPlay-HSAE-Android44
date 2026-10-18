package local.airuize.receiver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryExecutorTest {
    @Test fun blockedNetworkRequestDoesNotBlockCallerOrQueueDuplicates() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val errors=AtomicInteger()
        val executor=VideoRecoveryExecutor { errors.incrementAndGet() }
        try {
            assertTrue(executor.request { entered.countDown(); release.await() })
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(100) { assertFalse(executor.request { fail("duplicate request") }) }
            assertEquals(0,errors.get())
        } finally { release.countDown(); executor.close() }
    }

    @Test fun aCompletedRequestAllowsTheNextRecovery() {
        val first=CountDownLatch(1); val second=CountDownLatch(1)
        val executor=VideoRecoveryExecutor { fail(it.javaClass.simpleName) }
        try {
            assertTrue(executor.request { first.countDown() })
            assertTrue(first.await(2,TimeUnit.SECONDS))
            assertTrue(requestWhenIdle(executor) { second.countDown() })
            assertTrue(second.await(2,TimeUnit.SECONDS))
        } finally { executor.close() }
    }

    @Test fun aRequestFailureIsReportedAndDoesNotKillFutureRecovery() {
        val error=AtomicReference<Exception>()
        val failed=CountDownLatch(1); val recovered=CountDownLatch(1)
        val executor=VideoRecoveryExecutor { error.set(it); failed.countDown() }
        try {
            assertTrue(executor.request { throw java.io.IOException("synthetic") })
            assertTrue(failed.await(2,TimeUnit.SECONDS))
            assertTrue(error.get() is java.io.IOException)
            assertTrue(requestWhenIdle(executor) { recovered.countDown() })
            assertTrue(recovered.await(2,TimeUnit.SECONDS))
        } finally { executor.close() }
    }

    @Test fun closeRejectsNewRequestsAndInterruptsTheOwnedWorker() {
        val entered=CountDownLatch(1); val stopped=CountDownLatch(1)
        val errors=AtomicInteger()
        val executor=VideoRecoveryExecutor { errors.incrementAndGet() }
        assertTrue(executor.request {
            entered.countDown()
            try { CountDownLatch(1).await() } finally { stopped.countDown() }
        })
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        executor.close(); executor.close()
        assertFalse(executor.request { fail("closed executor ran a request") })
        assertTrue(stopped.await(2,TimeUnit.SECONDS))
        assertEquals(0,errors.get())
    }

    @Test fun requestDiagnosticsSeparateNetworkFailureAndDecoderFailure() {
        val diagnostics=MediaDiagnostics { 0L }
        diagnostics.recoveryRequests.incrementAndGet()
        diagnostics.recoveryRequestErrors.incrementAndGet()
        diagnostics.videoRecoveryRequest(8_765_000)
        diagnostics.videoRecoveryRequest(1_000)
        val summary=diagnostics.summary()
        assertTrue(summary.contains("关键帧请求=1；关键帧请求异常=1"))
        assertTrue(summary.contains("异步关键帧请求MaxUs=8765"))
        assertEquals(0L,diagnostics.decoderErrors.get())
    }

    private fun requestWhenIdle(executor:VideoRecoveryExecutor,action:()->Unit):Boolean {
        val deadline=System.nanoTime()+2_000_000_000L
        while(System.nanoTime()<deadline) {
            if(executor.request(action)) return true
            Thread.sleep(1)
        }
        return false
    }
}
