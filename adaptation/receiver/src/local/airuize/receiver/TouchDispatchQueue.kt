package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque

/**
 * Small touch handoff queue. Only MOVE snapshots may be coalesced or evicted; a normal gesture's
 * DOWN, pointer changes and UP stay ordered. The caller supplies newly normalized snapshots and
 * sends each polled job on a background thread after checking that its session is still current.
 *
 * If even the boundary reports fill the queue, the blocked peer cannot consume all input within a
 * finite memory budget. Cancel explicitly and wait for a fresh DOWN instead of replaying an old
 * drag or leaving a finger held. No UI operation waits for the network.
 */
internal class TouchDispatchQueue<S : Any>(private val capacity: Int = 32) {
    init { require(capacity >= 2) }

    data class Job<S>(
        val session: S,
        val contacts: List<AirPlayContact>,
        val isMove: Boolean,
        val enqueuedNs: Long,
    )

    enum class SubmitResult { QUEUED, COALESCED, IGNORED, OVERFLOW_CANCELLED }

    private val monitor = java.lang.Object()
    private val pending = ArrayDeque<Job<S>>()
    private var session: S? = null
    private var requireDown = true
    private var gestureOpen = false
    private var submitted = 0L
    private var coalescedMoves = 0L
    private var evictedMoves = 0L
    private var overflowCancels = 0L
    private var ignoredUntilDown = 0L
    private var explicitCancels = 0L
    private var sessionResets = 0L
    private var highWatermark = 0
    private var delivered = 0L
    private var sendFailures = 0L
    private var staleSessions = 0L
    private var maxQueueWaitNs = 0L
    private var maxSendNs = 0L

    /** isNewGesture is true only for ACTION_DOWN, never ACTION_POINTER_DOWN. */
    fun submit(
        session: S,
        contacts: List<AirPlayContact>,
        isMove: Boolean,
        isNewGesture: Boolean,
        nowNs: Long = System.nanoTime(),
    ): SubmitResult = synchronized(monitor) {
        submitted++
        if (this.session !== session) {
            pending.clear()
            this.session = session
            requireDown = true
            gestureOpen = false
            sessionResets++
        }
        if (requireDown && !isNewGesture) {
            ignoredUntilDown++
            return@synchronized SubmitResult.IGNORED
        }
        // A missing Android UP/CANCEL must not carry a held finger into another gesture.
        if (isNewGesture && gestureOpen) {
            pending.clear()
            pending.addLast(Job(session, emptyList(), false, nowNs))
            explicitCancels++
            gestureOpen = false
        }
        if (isNewGesture) requireDown = false
        val snapshot = contacts.toList()
        val job = Job(session, snapshot, isMove && !isNewGesture, nowNs)
        if (job.isMove && pending.peekLast()?.isMove == true) {
            pending.removeLast()
            pending.addLast(job)
            coalescedMoves++
            gestureOpen = snapshot.any { it.down }
            monitor.notifyAll()
            return@synchronized SubmitResult.COALESCED
        }
        if (pending.size >= capacity) {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().isMove) {
                    iterator.remove()
                    evictedMoves++
                    break
                }
            }
        }
        if (pending.size >= capacity) {
            // A MOVE need not displace any edge. Preserve all queued boundaries until the
            // consumer drains them; the next boundary still carries its final coordinates.
            if (job.isMove) {
                evictedMoves++
                return@synchronized SubmitResult.IGNORED
            }
            pending.clear()
            pending.addLast(Job(session, emptyList(), false, nowNs))
            requireDown = true
            gestureOpen = false
            overflowCancels++
            monitor.notifyAll()
            return@synchronized SubmitResult.OVERFLOW_CANCELLED
        }
        pending.addLast(job)
        gestureOpen = snapshot.any { it.down }
        if (!gestureOpen) requireDown = true
        highWatermark = maxOf(highWatermark, pending.size)
        monitor.notifyAll()
        SubmitResult.QUEUED
    }

    /** Drop pending motion and enqueue all-up, including when the last UP was still waiting. */
    fun cancel(session: S, nowNs: Long = System.nanoTime()): Boolean = synchronized(monitor) {
        if (this.session !== session) return@synchronized false
        pending.clear()
        pending.addLast(Job(session, emptyList(), false, nowNs))
        requireDown = true
        gestureOpen = false
        explicitCancels++
        highWatermark = maxOf(highWatermark, pending.size)
        monitor.notifyAll()
        true
    }

    /** Session ended: discard old work. A new session always requires a fresh ACTION_DOWN. */
    fun reset() = synchronized(monitor) {
        pending.clear()
        session = null
        requireDown = true
        gestureOpen = false
        monitor.notifyAll()
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long = 100): Job<S>? = synchronized(monitor) {
        require(timeoutMillis >= 0 && timeoutMillis <= 60_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (pending.isEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@synchronized null
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        pending.removeFirst()
    }

    /** Timing and counts only; never retain or log contact coordinates. */
    fun recordDelivery(job: Job<S>, startedNs: Long, durationNs: Long, success: Boolean) = synchronized(monitor) {
        if (success) delivered++ else sendFailures++
        maxQueueWaitNs = maxOf(maxQueueWaitNs, (startedNs - job.enqueuedNs).coerceAtLeast(0))
        maxSendNs = maxOf(maxSendNs, durationNs.coerceAtLeast(0))
    }

    fun recordStale() = synchronized(monitor) { staleSessions++ }

    fun diagnosticSummary(): String = synchronized(monitor) {
        "touchQueue pending=${pending.size} peak=$highWatermark capacity=$capacity " +
            "submitted=$submitted coalescedMove=$coalescedMoves evictedMove=$evictedMoves " +
            "overflowCancel=$overflowCancels ignoredUntilDown=$ignoredUntilDown " +
            "cancel=$explicitCancels sessionReset=$sessionResets delivered=$delivered " +
            "sendFailure=$sendFailures staleSession=$staleSessions " +
            "maxQueueWaitMs=${maxQueueWaitNs / 1_000_000L} maxSendMs=${maxSendNs / 1_000_000L}"
    }
}
