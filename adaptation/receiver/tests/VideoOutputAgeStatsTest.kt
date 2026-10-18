package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class VideoOutputAgeStatsTest {
    @Test fun ageUsesLocalEpochAndInputPtsWithoutThePhoneClock() {
        assertEquals(70_000L,VideoOutputAgeStats.ageUs(1_000_000_000L,30_000L,1_100_000_000L))
        assertEquals(0L,VideoOutputAgeStats.ageUs(1_000L,1L,2_000L))
    }
    @Test fun futureNegativeAndUnrepresentablePtsAreInvalidRatherThanZeroLatency() {
        assertNull(VideoOutputAgeStats.ageUs(1_000_000L,-1L,2_000_000L))
        assertNull(VideoOutputAgeStats.ageUs(1_000_000L,1001L,2_000_000L))
        assertNull(VideoOutputAgeStats.ageUs(1_000_000L,Long.MAX_VALUE,2_000_000L))
        assertNull(VideoOutputAgeStats.ageUs(1_000_000L,0L,999_999L))
    }
    @Test fun monotonicClockSignedWrapIsHandledByRelativeSubtraction() {
        assertEquals(4L,VideoOutputAgeStats.ageUs(Long.MAX_VALUE-2000L,0L,Long.MIN_VALUE+1999L))
    }
    @Test fun decodedAndSubmittedCountersAreSeparate() {
        val stats=VideoOutputAgeStats()
        stats.decoded(0,1000,51_000_000);stats.decoded(0,2000,62_000_000)
        stats.submitted(0,2000,72_000_000)
        val value=stats.snapshot()
        assertEquals(2L,value.decoded.samples);assertEquals(55_000L,value.decoded.meanUs)
        assertEquals(1L,value.submitted.samples);assertEquals(70_000L,value.submitted.maxUs)
    }
    @Test fun fixedBucketsKeepExactBoundaryCounts() {
        val stats=VideoOutputAgeStats()
        for(age in listOf(0L,16_000L,16_001L,33_000L,33_001L,66_000L,66_001L,100_000L,100_001L,250_000L,250_001L))
            stats.decoded(0,0,age*1000)
        val value=stats.snapshot().decoded
        assertEquals(listOf(2L,2L,2L,2L,2L,1L),value.buckets)
        assertEquals(11L,value.samples);assertEquals(250_001L,value.maxUs)
        assertEquals(value.samples,value.buckets.sum())
    }
    @Test fun invalidPtsNeverAffectMeanMaxOrBucketCounts() {
        val stats=VideoOutputAgeStats()
        stats.decoded(0,-1,1_000_000);stats.decoded(0,2000,1_000_000)
        stats.decoded(0,0,1_000_000)
        val value=stats.snapshot().decoded
        assertEquals(2L,value.invalid);assertEquals(1L,value.samples)
        assertEquals(1000L,value.maxUs);assertEquals(1000L,value.meanUs)
        assertEquals(1L,value.buckets.sum())
    }
    @Test fun diagnosticSnapshotDoesNotRetainMutableWorkerBuckets() {
        val stats=VideoOutputAgeStats();stats.decoded(0,0,1_000_000)
        val before=stats.snapshot();stats.decoded(0,0,2_000_000)
        assertEquals(1L,before.decoded.samples);assertEquals(1L,before.decoded.buckets.sum())
        assertEquals(2L,stats.snapshot().decoded.samples)
    }
    @Test fun metadataDoesNotClaimAScreenFpsOrEndToEndLatency() {
        val stats=VideoOutputAgeStats();val summary=stats.summary()
        assertTrue(summary.contains("解码[样本=0"));assertTrue(summary.contains("提交[样本=0"))
        assertTrue(summary.contains("不含手机处理"));assertTrue(summary.contains("提交不等于屏幕实显"))
        assertTrue(summary.contains("不是端到端触摸延迟"))
    }
    @Test fun diagnosticsIncludeTheAgeLineWithoutChangingTheExistingTimingFields() {
        val d=MediaDiagnostics {0L}
        d.videoDecodedAge(0,0,10_000_000);d.videoSubmittedAge(0,0,20_000_000)
        val summary=d.summary()
        assertTrue(summary.contains("队列年龄MaxUs=0"));assertTrue(summary.contains("输出releaseMaxUs=0"))
        assertTrue(summary.contains("解码[样本=1 均值Us=10000 最大Us=10000"))
        assertTrue(summary.contains("提交[样本=1 均值Us=20000 最大Us=20000"))
        assertTrue(summary.contains("视频线程DISPLAY优先级请求=false 实际nice=未读取"))
    }
}
