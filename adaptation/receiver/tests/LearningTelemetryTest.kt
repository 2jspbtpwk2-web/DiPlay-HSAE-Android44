package local.airuize.receiver

import com.shilapi.xcertplay.airplay.ScreenFrameCrypto
import org.junit.Assert.*
import org.junit.Test

class LearningTelemetryTest {
    private fun c(n:Long,age:Long=0)=LearningTelemetry.Counts(n,n,n*1000,n/5,n/10,n/20,age)
    private fun x(n:Long)=ScreenFrameCrypto.Counters(n,0,0,0)
    @Test fun firstOnlyEstablishesBaseline(){assertNull(LearningTelemetry().sample(5000,c(2),x(2),true,true))}
    @Test fun subtractsCountsButKeepsIntervalMaximum(){val t=LearningTelemetry();t.sample(5000,c(20),x(20),true,true)
        val w=t.sample(10000,c(60,125),x(60),true,true)!!
        assertEquals(40L,w.receivedFrames);assertEquals(40000L,w.receivedBytes);assertEquals(125L,w.queueAgeMaxMs)
        assertEquals(40L,w.nativeFrames);assertTrue(w.valid())}
    @Test fun rejectsResetAndRebaselines(){val t=LearningTelemetry();t.sample(5000,c(60),x(60),true,true)
        assertNull(t.sample(10000,c(0),x(0),true,true));assertEquals(30L,t.sample(15000,c(30),x(30),true,true)!!.receivedFrames)}
    @Test fun rejectsGapAndShortIntervals(){val t=LearningTelemetry();t.sample(5000,c(0),x(0),true,true)
        assertNull(t.sample(16000,c(50),x(50),true,true));assertNull(t.sample(17000,c(60),x(60),true,true))}
    @Test fun carriesSurfaceAndDecoderState(){val t=LearningTelemetry();t.sample(0,c(0),x(0),true,true)
        val w=t.sample(5000,c(50),x(50),false,false)!!;assertFalse(w.surfaceValid);assertFalse(w.decoderActive)}
    @Test fun intervalMaximumResetsWithoutResettingOriginalCounters(){val d=MediaDiagnostics();d.videoQueueAge(120_000_000)
        assertEquals(120L,d.learningCounters().queueAgeMs);d.videoQueueAge(10_000_000)
        assertEquals(10L,d.learningCounters().queueAgeMs);assertEquals(0L,d.learningCounters().queueAgeMs)
        assertTrue(d.summary().contains("队列年龄MaxUs=120000"))}
}
