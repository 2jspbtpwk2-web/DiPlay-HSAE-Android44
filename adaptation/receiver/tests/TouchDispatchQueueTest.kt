package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test

class TouchDispatchQueueTest {
    private fun contacts(x: Double = 0.1, down: Boolean = true) =
        listOf(AirPlayContact(0, x, 0.2, down))

    @Test fun consecutiveMovesKeepOnlyTheLatestSnapshotBetweenDownAndUp() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        queue.submit(session, contacts(), false, true, 1)
        queue.submit(session, contacts(0.2), true, false, 2)
        assertEquals(TouchDispatchQueue.SubmitResult.COALESCED,
            queue.submit(session, contacts(0.9), true, false, 3))
        queue.submit(session, contacts(0.9, false), false, false, 4)
        assertFalse(queue.poll(0)!!.isMove)
        val move = queue.poll(0)!!
        assertTrue(move.isMove)
        assertEquals(0.9, move.contacts[0].x, 0.0)
        assertEquals(3L, move.enqueuedNs)
        assertFalse(queue.poll(0)!!.contacts[0].down)
        assertNull(queue.poll(0))
    }

    @Test fun pointerBoundariesPreventMoveCoalescingAcrossThem() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        val two = contacts() + AirPlayContact(1, 0.5, 0.5, true)
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(0.2), true, false)
        queue.submit(session, two, false, false)
        queue.submit(session, two.map { it.copy(x = 0.8) }, true, false)
        queue.submit(session, contacts(0.8, false), false, false)
        assertFalse(queue.poll(0)!!.isMove)
        assertTrue(queue.poll(0)!!.isMove)
        assertEquals(2, queue.poll(0)!!.contacts.size)
        assertTrue(queue.poll(0)!!.isMove)
        assertFalse(queue.poll(0)!!.contacts[0].down)
    }

    @Test fun fullQueueEvictsAMoveBeforeAnyBoundary() {
        val queue = TouchDispatchQueue<Any>(4)
        val session = Any()
        val secondDown = contacts() + AirPlayContact(1, 0.5, 0.5, true)
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(0.2), true, false)
        queue.submit(session, secondDown, false, false)
        queue.submit(session, contacts(0.3), false, false)
        assertEquals(TouchDispatchQueue.SubmitResult.QUEUED,
            queue.submit(session, contacts(0.4, false), false, false))
        assertEquals(0.1, queue.poll(0)!!.contacts[0].x, 0.0)
        assertEquals(2, queue.poll(0)!!.contacts.size)
        assertEquals(0.3, queue.poll(0)!!.contacts[0].x, 0.0)
        assertFalse(queue.poll(0)!!.contacts[0].down)
        assertTrue(queue.diagnosticSummary().contains("evictedMove=1"))
    }

    @Test fun saturatedBoundariesCancelAndRequireAFreshDown() {
        val queue = TouchDispatchQueue<Any>(2)
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(0.2), false, false)
        assertEquals(TouchDispatchQueue.SubmitResult.OVERFLOW_CANCELLED,
            queue.submit(session, contacts(0.3, false), false, false))
        assertTrue(queue.poll(0)!!.contacts.isEmpty())
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(session, contacts(0.4), true, false))
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(session, contacts(0.5, false), false, false))
        assertEquals(TouchDispatchQueue.SubmitResult.QUEUED,
            queue.submit(session, contacts(0.6), false, true))
        assertEquals(0.6, queue.poll(0)!!.contacts[0].x, 0.0)
        assertTrue(queue.diagnosticSummary().contains("overflowCancel=1"))
    }

    @Test fun anIncomingMoveCannotCancelAQueueFullOfBoundaries() {
        val queue = TouchDispatchQueue<Any>(2)
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(0.2), false, false)
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(session, contacts(0.8), true, false))
        assertEquals(0.1, queue.poll(0)!!.contacts[0].x, 0.0)
        assertEquals(0.2, queue.poll(0)!!.contacts[0].x, 0.0)
        assertTrue(queue.diagnosticSummary().contains("overflowCancel=0"))
        assertTrue(queue.diagnosticSummary().contains("evictedMove=1"))
    }

    @Test fun cancelReplacesPendingMotionWithRelease() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(0.8), true, false)
        assertTrue(queue.cancel(session))
        assertTrue(queue.poll(0)!!.contacts.isEmpty())
        assertNull(queue.poll(0))
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(session, contacts(0.9), true, false))
    }

    @Test fun cancelPreservesReleaseEvenWhenUpWasQueued() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.submit(session, contacts(down = false), false, false)
        assertTrue(queue.cancel(session))
        assertTrue(queue.poll(0)!!.contacts.isEmpty())
        assertNull(queue.poll(0))
    }

    @Test fun switchingSessionDiscardsOldWorkAndRejectsAnOldDragContinuation() {
        val queue = TouchDispatchQueue<Any>()
        val old = Any()
        val current = Any()
        queue.submit(old, contacts(), false, true)
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(current, contacts(0.6), true, false))
        assertNull(queue.poll(0))
        queue.submit(current, contacts(0.7), false, true)
        assertSame(current, queue.poll(0)!!.session)
    }

    @Test fun lateOldSessionCancellationCannotEraseANewGesture() {
        val queue = TouchDispatchQueue<Any>()
        val old = Any()
        val current = Any()
        queue.submit(old, contacts(), false, true)
        queue.submit(current, contacts(0.7), false, true)
        assertFalse(queue.cancel(old))
        assertSame(current, queue.poll(0)!!.session)
    }

    @Test fun resetDropsSessionAndRequiresNewDown() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.reset()
        assertNull(queue.poll(0))
        assertFalse(queue.cancel(session))
        assertEquals(TouchDispatchQueue.SubmitResult.IGNORED,
            queue.submit(session, contacts(0.8), true, false))
    }

    @Test fun callerListMutationCannotChangeTheQueuedSnapshot() {
        val queue = TouchDispatchQueue<Any>()
        val mutable = contacts().toMutableList()
        queue.submit(Any(), mutable, false, true)
        mutable[0] = mutable[0].copy(x = 1.0)
        assertEquals(0.1, queue.poll(0)!!.contacts[0].x, 0.0)
    }

    @Test fun aNewDownAfterMissingUpIsPrecededByExplicitRelease() {
        val queue = TouchDispatchQueue<Any>()
        val session = Any()
        queue.submit(session, contacts(), false, true)
        queue.poll(0) // The phone may have received this finger already.
        queue.submit(session, contacts(0.8), false, true)
        assertTrue(queue.poll(0)!!.contacts.isEmpty())
        assertEquals(0.8, queue.poll(0)!!.contacts[0].x, 0.0)
    }

    @Test fun zeroTimeoutPollIsNonBlockingAndEmpty() {
        assertNull(TouchDispatchQueue<Any>().poll(0))
    }

    @Test fun deliveryDiagnosticsSeparateQueueWaitFromNetworkTime() {
        val queue = TouchDispatchQueue<Any>()
        queue.submit(Any(), contacts(), false, true, 1_000_000L)
        val job = queue.poll(0)!!
        queue.recordDelivery(job, 4_000_000L, 2_000_000L, true)
        queue.recordDelivery(job, 3_000_000L, 7_000_000L, false)
        queue.recordStale()
        val summary = queue.diagnosticSummary()
        assertTrue(summary.contains("delivered=1"))
        assertTrue(summary.contains("sendFailure=1"))
        assertTrue(summary.contains("staleSession=1"))
        assertTrue(summary.contains("maxQueueWaitMs=3"))
        assertTrue(summary.contains("maxSendMs=7"))
        assertFalse(summary.contains("0.1"))
    }
}
