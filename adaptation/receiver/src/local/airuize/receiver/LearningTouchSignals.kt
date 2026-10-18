package local.airuize.receiver

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional UI metadata only. Control publishers replace epochs at session/eligibility boundaries;
 * touch callbacks publish two atomics and never wait for the learning/persistence/media locks.
 * A late callback can write only its retired epoch, which the new owner will never consume.
 */
internal class LearningTouchSignals {
    private class Epoch {val lastAtMs=AtomicLong(-1L)}
    private class State(val owner:Long?,val visible:Boolean,val diagnostic:Boolean,val voice:Boolean,
        val changedAtMs:Long,val epoch:Epoch?,val total:AtomicLong)
    data class Snapshot(val owner:Long?,val visible:Boolean,val diagnostic:Boolean,val voice:Boolean,
        val changedAtMs:Long,val lastTouchMs:Long)
    private val state=AtomicReference(State(null,false,false,false,0L,null,AtomicLong()))

    /** A connection boundary preserves any concurrently published newer UI environment. */
    fun owner(owner:Long?,atMs:Long) {
        while(true) {
            val old=state.get()
            val next=State(owner,old.visible,old.diagnostic,old.voice,maxOf(old.changedAtMs,atMs),
                if(owner==null) null else Epoch(),old.total)
            if(state.compareAndSet(old,next)) return
        }
    }
    /** UI-only metadata. No preference, device or rule-owner access happens here. */
    fun activity(visible:Boolean,diagnostic:Boolean,voice:Boolean,atMs:Long) {
        while(true) {
            val old=state.get()
            if(old.visible==visible && old.diagnostic==diagnostic && old.voice==voice) return
            val next=State(old.owner,visible,diagnostic,voice,maxOf(old.changedAtMs,atMs),
                if(old.owner==null) null else Epoch(),old.total)
            if(state.compareAndSet(old,next)) return
        }
    }
    fun record(atMs:Long) {
        if(atMs<0L) return
        val observed=state.get()
        val epoch=observed.epoch ?: return
        if(!observed.visible || observed.diagnostic || observed.voice) return
        epoch.lastAtMs.set(atMs)
        observed.total.incrementAndGet()
    }
    fun snapshot():Snapshot {
        val value=state.get()
        return Snapshot(value.owner,value.visible,value.diagnostic,value.voice,value.changedAtMs,
            value.epoch?.lastAtMs?.get() ?: -1L)
    }
    fun lastTouchMs(owner:Long):Long=snapshot().takeIf {it.owner==owner && it.visible && !it.diagnostic && !it.voice}?.lastTouchMs ?: -1L
    fun totalTouches():Long=state.get().total.get()
    fun reset(atMs:Long) {
        while(true) {
            val old=state.get()
            val next=State(null,old.visible,old.diagnostic,old.voice,maxOf(old.changedAtMs,atMs),null,AtomicLong())
            if(state.compareAndSet(old,next)) return
        }
    }
}
