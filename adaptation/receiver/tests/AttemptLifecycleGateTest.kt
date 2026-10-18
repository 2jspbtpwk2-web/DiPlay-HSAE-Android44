package local.airuize.receiver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AttemptLifecycleGateTest {
    @Test fun invalidationBeforeAnOldCallbackRejectsPublication() {
        val fixture = Fixture()
        fixture.advance()
        assertFalse(fixture.publish(1, Any()))
        fixture.assertEmpty()
    }

    @Test fun invalidationAfterPublicationClearsEveryPublishedField() {
        val fixture = Fixture()
        assertTrue(fixture.publish(1, Any()))
        assertNotNull(fixture.active)
        fixture.advance()
        fixture.assertEmpty()
    }

    @Test fun retirementClosesPublicationWithoutChangingTheDiagnosticGeneration() {
        val fixture = Fixture()
        fixture.publish(1, Any())
        assertTrue(fixture.gate.retire(1) { fixture.clear() })
        assertEquals(1, fixture.generation.get())
        assertFalse(fixture.publish(1, Any()))
        assertFalse(fixture.gate.isCurrent(1))
        fixture.assertEmpty()
    }

    @Test fun aRetiredAttemptCanBeReplacedByANewGeneration() {
        val fixture = Fixture()
        fixture.gate.retire(1) { fixture.clear() }
        val id = fixture.advance()
        assertEquals(2, id)
        assertTrue(fixture.publish(id, Any()))
        assertTrue(fixture.gate.isCurrent(id))
    }

    @Test fun anOldCleanupCannotRetireOrClearTheNewGeneration() {
        val fixture = Fixture()
        val id = fixture.advance()
        val session = Any()
        fixture.publish(id, session)
        assertFalse(fixture.gate.retire(1) { fixture.clear() })
        assertSame(session, fixture.active)
        assertTrue(fixture.gate.isCurrent(id))
    }

    @Test fun oldEndedCallbackCannotClearANewGenerationSession() {
        val fixture = Fixture()
        val old = Any()
        fixture.publish(1, old)
        val id = fixture.advance()
        val current = Any()
        fixture.publish(id, current)
        assertFalse(fixture.ended(1, old))
        assertSame(current, fixture.active)
    }

    @Test fun endedCallbackUsesIdentityWithinTheSameGeneration() {
        val fixture = Fixture()
        val old = Any()
        val current = Any()
        fixture.publish(1, old)
        fixture.publish(1, current)
        assertTrue(fixture.ended(1, old))
        assertSame(current, fixture.active)
        assertTrue(fixture.ended(1, current))
        assertNull(fixture.active)
    }

    @Test fun stoppedServiceRejectsCallbacksEvenWithAnEqualGeneration() {
        val fixture = Fixture()
        fixture.gate.mutate {
            fixture.running.set(false)
            fixture.gate.advance { fixture.clear() }
        }
        assertFalse(fixture.publish(fixture.generation.get(), Any()))
        fixture.assertEmpty()
    }

    @Test fun retirementStillClearsAStoppedService() {
        val fixture = Fixture()
        fixture.publish(1, Any())
        fixture.gate.mutate { fixture.running.set(false) }
        assertTrue(fixture.gate.retire(1) { fixture.clear() })
        fixture.assertEmpty()
    }

    @Test fun workerEntryCannotSetWorkingGenerationAfterInvalidation() {
        val fixture = Fixture()
        fixture.advance()
        assertFalse(fixture.gate.withCurrent(1) { fixture.workingGeneration = 1 })
        assertEquals(0, fixture.workingGeneration)
        assertTrue(fixture.gate.withCurrent(2) { fixture.workingGeneration = 2 })
        assertEquals(2, fixture.workingGeneration)
    }

    @Test fun queueAllocationAndClearCanShareOneMutation() {
        val fixture = Fixture()
        fixture.publish(1, Any())
        var selected: String? = null
        fun allocate(): Int? = fixture.gate.mutate {
            if (!fixture.running.get() || selected != null) null
            else { selected = "iphone"; fixture.gate.advance { fixture.clear() } }
        }
        assertEquals(2, allocate())
        assertNull(allocate())
        fixture.assertEmpty()
        assertEquals("iphone", selected)
    }

    @Test fun aCallbackPausedBeforePublicationCannotUndoCompletedInvalidation() {
        val fixture = Fixture()
        val callbackReady = CountDownLatch(1)
        val resumeCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val errors = AtomicReference<Throwable?>()
        val callback = thread(errors) {
            callbackReady.countDown()
            await(resumeCallback)
            accepted.set(fixture.publish(1, Any()))
        }
        try {
            await(callbackReady)
            fixture.advance()
        } finally { resumeCallback.countDown() }
        join(callback, errors)
        assertFalse(accepted.get())
        fixture.assertEmpty()
    }

    @Test fun invalidationWaitsForInFlightPublicationThenClearsItsResult() {
        val fixture = Fixture()
        val insidePublish = CountDownLatch(1)
        val finishPublish = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val invalidationDone = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val callback = thread(errors) {
            assertTrue(fixture.gate.withCurrent(1) {
                fixture.active = Any()
                insidePublish.countDown()
                await(finishPublish)
                fixture.media = Any()
                fixture.display = Any()
            })
        }
        var invalidator: Thread? = null
        try {
            await(insidePublish)
            invalidator = thread(errors) {
                invalidationStarted.countDown()
                fixture.advance()
                invalidationDone.countDown()
            }
            await(invalidationStarted)
            assertEquals(1L, invalidationDone.count)
        } finally { finishPublish.countDown() }
        join(callback, errors)
        join(checkNotNull(invalidator), errors)
        assertEquals(0L, invalidationDone.count)
        fixture.assertEmpty()
    }

    @Test fun oldEndedCallbackPausedUntilReconnectCannotClearTheCurrentSession() {
        val fixture = Fixture()
        val old = Any()
        fixture.publish(1, old)
        val endedReady = CountDownLatch(1)
        val resumeEnded = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val callback = thread(errors) {
            endedReady.countDown()
            await(resumeEnded)
            assertFalse(fixture.ended(1, old))
        }
        val current = Any()
        try {
            await(endedReady)
            val id = fixture.advance()
            assertTrue(fixture.publish(id, current))
        } finally { resumeEnded.countDown() }
        join(callback, errors)
        assertSame(current, fixture.active)
    }

    private class Fixture {
        val generation = AtomicInteger(1)
        val running = AtomicBoolean(true)
        val gate = AttemptLifecycleGate(generation, running)
        var active: Any? = null
        var media: Any? = null
        var display: Any? = null
        var workingGeneration = 0

        fun clear() { active = null; media = null; display = null }
        fun advance(): Int = gate.advance { clear() }
        fun publish(id: Int, session: Any): Boolean = gate.withCurrent(id) {
            active = session; media = Any(); display = Any()
        }
        fun ended(id: Int, session: Any): Boolean = gate.withCurrent(id) {
            if (active === session) active = null
        }
        fun assertEmpty() { assertNull(active); assertNull(media); assertNull(display) }
    }

    private fun thread(errors: AtomicReference<Throwable?>, action: () -> Unit): Thread = Thread {
        try { action() } catch (error: Throwable) { errors.compareAndSet(null, error) }
    }.apply { isDaemon = true; start() }

    private fun await(latch: CountDownLatch) {
        assertTrue("Lifecycle test barrier timed out", latch.await(5, TimeUnit.SECONDS))
    }

    private fun join(thread: Thread, errors: AtomicReference<Throwable?>) {
        thread.join(5000)
        if (thread.isAlive) { thread.interrupt(); fail("Lifecycle test thread did not exit") }
        errors.get()?.let { throw AssertionError("Lifecycle test thread failed").apply { initCause(it) } }
    }
}
