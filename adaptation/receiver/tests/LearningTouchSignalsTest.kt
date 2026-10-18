package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LearningTouchSignalsTest {
    @Test fun unownedExcludedAndInvalidTouchesDoNotBecomeSamples() {
        val signals=LearningTouchSignals();signals.record(10)
        assertEquals(-1L,signals.lastTouchMs(1));assertEquals(0L,signals.totalTouches())
        signals.owner(1,0);signals.record(20);signals.activity(true,false,false,0);signals.record(-1)
        assertEquals(-1L,signals.lastTouchMs(1));assertEquals(0L,signals.totalTouches())
    }
    @Test fun currentEligibleOwnerGetsTheNumericSignalOnly() {
        val signals=eligible();signals.record(100)
        assertEquals(100L,signals.lastTouchMs(7));assertEquals(-1L,signals.lastTouchMs(8))
        assertEquals(1L,signals.totalTouches())
    }
    @Test fun unchangedTickerGateDoesNotEraseRecentInteraction() {
        val signals=eligible();signals.record(100);signals.activity(true,false,false,200)
        assertEquals(100L,signals.lastTouchMs(7))
    }
    @Test fun sceneBoundaryAndSessionBoundaryRequireANewTouch() {
        val signals=eligible();signals.record(100)
        signals.activity(false,true,false,110);signals.activity(true,false,false,120)
        assertEquals(-1L,signals.lastTouchMs(7))
        signals.record(200);signals.owner(8,210)
        assertEquals(-1L,signals.lastTouchMs(7));assertEquals(-1L,signals.lastTouchMs(8))
        signals.record(300);signals.owner(null,310)
        assertEquals(-1L,signals.lastTouchMs(8))
    }
    @Test fun explicitNewGenerationClearsEvenAnIdenticalOwnerNumber() {
        val signals=eligible();signals.record(100);signals.owner(7,110)
        assertEquals(-1L,signals.lastTouchMs(7));assertEquals(1L,signals.totalTouches())
    }
    @Test fun aLateWriteToARetiredEpochCannotSetTheNewOwnersTimestamp() {
        val signals=eligible()
        val field=LearningTouchSignals::class.java.getDeclaredField("state").apply {isAccessible=true}
        val current=field.get(signals) as AtomicReference<*>
        val old=current.get()!!
        val epoch=old.javaClass.getDeclaredField("epoch").apply {isAccessible=true}.get(old)!!
        val timestamp=epoch.javaClass.getDeclaredField("lastAtMs").apply {isAccessible=true}.get(epoch) as AtomicLong
        signals.owner(8,10)
        timestamp.set(999)
        assertEquals(-1L,signals.lastTouchMs(8))
        signals.record(1000);assertEquals(1000L,signals.lastTouchMs(8))
    }
    @Test fun recordNeverWaitsForTheObserversObjectMonitor() {
        val signals=eligible()
        val done=CountDownLatch(1)
        val error=AtomicReference<Throwable?>()
        val worker=Thread {try {repeat(1000) {signals.activity(true,false,false,it.toLong());signals.record(it.toLong())}} catch(t:Throwable) {error.set(t)} finally {done.countDown()}}
        val completed=synchronized(signals) {worker.start();done.await(3,TimeUnit.SECONDS)}
        worker.join(3000)
        assertTrue("Touch metadata completed while the observer monitor stayed held",completed)
        assertNull(error.get());assertFalse(worker.isAlive)
        assertEquals(1000L,signals.totalTouches())
    }
    @Test fun aResetClearsOptionalCountsAndCannotResurrectAnOwner() {
        val signals=eligible();signals.record(100);signals.reset(110);signals.record(200)
        assertEquals(0L,signals.totalTouches());assertEquals(-1L,signals.lastTouchMs(7))
    }
    @Test fun connectionOwnerUpdatePreservesNewerUiExclusionAndItsTime() {
        val signals=eligible();signals.activity(false,true,false,200);signals.owner(8,100)
        val view=signals.snapshot()
        assertEquals(8L,view.owner);assertFalse(view.visible);assertTrue(view.diagnostic);assertEquals(200L,view.changedAtMs)
        signals.record(250);assertEquals(-1L,signals.lastTouchMs(8))
    }
    @Test fun uiUpdateCannotReviveAnEndedOwnerOrLoseANewerConnection() {
        val signals=eligible();signals.owner(null,200);signals.activity(true,false,true,210);signals.record(220)
        assertNull(signals.snapshot().owner);assertEquals(0L,signals.totalTouches())
        signals.owner(8,230);signals.activity(true,false,false,240);signals.record(250)
        assertEquals(8L,signals.snapshot().owner);assertEquals(250L,signals.lastTouchMs(8))
    }
    @Test fun pauseRefusesTheNextTouchWithoutWaitingForRuleEvaluation() {
        val signals=eligible();signals.record(100);signals.activity(false,true,false,110)
        repeat(1000) {signals.record(120+it.toLong())}
        assertEquals(1L,signals.totalTouches());assertEquals(-1L,signals.lastTouchMs(7))
        assertTrue(signals.snapshot().changedAtMs>100)
    }
    @Test fun ownerAndUiPublishersDoNotLoseEachOthersFinalUpdates() {
        val signals=eligible();val start=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val owner=Thread {try {start.await();for(i in 1..10000) signals.owner(i.toLong(),i.toLong())} catch(t:Throwable) {failure.set(t)}}
        val ui=Thread {try {
            start.await();for(i in 1..10000) signals.activity(i%2==0,i%2!=0,false,i.toLong())
            signals.activity(false,true,false,10001)
        } catch(t:Throwable) {failure.set(t)}}
        owner.start();ui.start();start.countDown();owner.join(5000);ui.join(5000)
        assertFalse(owner.isAlive);assertFalse(ui.isAlive);assertNull(failure.get())
        val result=signals.snapshot()
        assertEquals(10000L,result.owner);assertFalse(result.visible);assertTrue(result.diagnostic)
        assertEquals(10001L,result.changedAtMs)
    }
    private fun eligible()=LearningTouchSignals().apply {owner(7,0);activity(true,false,false,0)}
}
