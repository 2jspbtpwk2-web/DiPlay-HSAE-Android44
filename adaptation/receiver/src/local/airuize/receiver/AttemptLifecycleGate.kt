package local.airuize.receiver

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes attempt invalidation with publication. Actions are short state mutations only: never
 * close resources, call listeners, log, or perform I/O while holding this gate.
 *
 * Retirement blocks callbacks and new resource ownership without changing the diagnostic token;
 * the attempt's existing failure reporting and retry backoff can still finish under that token.
 */
internal class AttemptLifecycleGate(
    private val generation: AtomicInteger,
    private val running: AtomicBoolean,
) {
    private val lock = Any()
    private var retiredGeneration: Int? = null

    fun <T> mutate(action: () -> T): T = synchronized(lock) { action() }

    fun isCurrent(expected: Int): Boolean = synchronized(lock) {
        running.get() && generation.get() == expected && retiredGeneration != expected
    }

    fun withCurrent(expected: Int, action: () -> Unit): Boolean = synchronized(lock) {
        if (!running.get() || generation.get() != expected || retiredGeneration == expected) false
        else { action(); true }
    }

    /** Every real generation change and clearing its published state occur in one critical section. */
    fun advance(clearPublished: () -> Unit): Int = synchronized(lock) {
        val next = generation.incrementAndGet()
        retiredGeneration = null
        clearPublished()
        next
    }

    fun retire(expected: Int, clearPublished: () -> Unit): Boolean = synchronized(lock) {
        if (generation.get() != expected) false
        else {
            retiredGeneration = expected
            clearPublished()
            true
        }
    }
}
