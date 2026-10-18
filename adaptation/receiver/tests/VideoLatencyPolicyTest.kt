package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class VideoLatencyPolicyTest {
    @Test fun freshDecoderRequiresRandomAccess() {
        val p=VideoLatencyPolicy(); assertFalse(p.accepts(false)); assertTrue(p.accepts(true)); assertFalse(p.shouldRender(0))
        p.queued(100); assertTrue(p.accepts(false)); assertTrue(p.shouldRender(100)); assertFalse(p.shouldRender(99))
    }
    @Test fun recoveryHidesOldOutputsUntilTheNextKeyFrame() {
        val p=VideoLatencyPolicy(); p.queued(10); p.requireKeyFrame()
        assertFalse(p.shouldRender(100)); assertFalse(p.accepts(false)); assertTrue(p.accepts(true))
        p.queued(200); assertFalse(p.shouldRender(199)); assertTrue(p.shouldRender(200)); assertTrue(p.shouldRender(201))
    }
    @Test fun keyFrameRetryIsThrottledAndStopsAfterRecovery() {
        val p=VideoLatencyPolicy(); assertTrue(p.requestKeyFrameDue(0)); assertFalse(p.requestKeyFrameDue(999_999_999))
        assertTrue(p.requestKeyFrameDue(1_000_000_000)); p.queued(10); assertFalse(p.requestKeyFrameDue(3_000_000_000))
        p.requireKeyFrame(); assertTrue(p.requestKeyFrameDue(3_000_000_000))
    }
    @Test fun repeatedRecoveryDoesNotBypassThrottle() {
        val p=VideoLatencyPolicy(); assertTrue(p.requestKeyFrameDue(0)); p.requireKeyFrame()
        assertFalse(p.requestKeyFrameDue(100)); assertTrue(p.requestKeyFrameDue(1_000_000_000))
    }
    @Test fun queueAgeHasAHardBudgetWithoutNegativeClockValues() {
        val p=VideoLatencyPolicy(); assertEquals(0L,p.ageNs(100,99)); assertFalse(p.expired(0,250_000_000)); assertTrue(p.expired(0,250_000_001))
    }
    @Test fun frameRatesExcludePartialSecondsAndExpireOldCounts() {
        val r=VideoFrameRates(0); r.received(100); r.queued(100); r.output(100)
        assertEquals(0,r.snapshot(999_999_999).seconds)
        assertEquals(VideoFrameRates.Snapshot(1,1,1,1),r.snapshot(1_000_000_000))
        assertEquals(VideoFrameRates.Snapshot(5,0,0,0),r.snapshot(6_000_000_000))
    }
    @Test fun boundedRateBucketsReuseSlotsWithoutMixingCounts() {
        val r=VideoFrameRates(0)
        repeat(20) { i->r.received(i*1_000_000_000L); r.queued(i*1_000_000_000L); if(i%2==0) r.output(i*1_000_000_000L) }
        assertEquals(VideoFrameRates.Snapshot(5,5,5,2),r.snapshot(20_000_000_000L))
    }
    @Test fun mediaPerformanceSeparatesReceiveQueueAndOutput() {
        var now=0L; val d=MediaDiagnostics { now }
        now=100; d.receivedVideo(4,1); now=200; d.queuedVideo(); now=300; d.outputVideo()
        d.videoQueueAge(123_000); d.videoQueueAge(3); d.videoInputWait(456_000); d.videoOutputRelease(789_000)
        now=1_000_000_000
        val s=d.summary(); assertTrue(s.contains("队列年龄MaxUs=123")); assertTrue(s.contains("输入等待MaxUs=456")); assertTrue(s.contains("输出releaseMaxUs=789")); assertTrue(s.contains("接收=1.0fps")); assertTrue(s.contains("入解码=1.0fps"))
    }
    @Test fun emptyRateCountersNeverImplyActualPlayback() {
        val d=MediaDiagnostics { 0 }; val s=d.summary()
        assertTrue(s.contains("未满1秒")); assertTrue(s.contains("入解码=0")); assertTrue(s.contains("首解码输出释放=未发生"))
    }
    @Test fun onlyIdleReadCodesAreRetryable() {
        for(value in listOf(0,-4,-11,-110)) assertTrue(LegacyUsbIo.retryableRead(value))
        for(value in listOf(-1,-9,-19,-22,-25,-32,-104,-108,1)) assertFalse(LegacyUsbIo.retryableRead(value))
    }
}
