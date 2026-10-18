package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class FeatureLearningPolicyTest {
    @Test fun liveMicrophoneReferencesExcludeCalibrationUntilAllClose() {
        val gate=FeatureLearningPolicy.MicrophoneLeaseGate()
        val first=gate.tryLive()!!;val second=gate.tryLive()!!
        assertEquals(2,gate.state());assertNull(gate.tryCalibration())
        first.close();first.close();assertEquals(1,gate.state());assertNull(gate.tryCalibration())
        second.close();assertEquals(0,gate.state())
        val calibration=gate.tryCalibration()!!
        assertEquals(-1,gate.state());assertNull(gate.tryLive());assertNull(gate.tryCalibration())
        calibration.close();calibration.close();assertEquals(0,gate.state())
        val next=gate.tryLive()!!;calibration.close();assertEquals(1,gate.state());next.close()
    }
    private fun evidence(sample:Int):FeatureLearningPolicy.PcmEvidence {
        val bytes=ByteArray(3200)
        for(i in bytes.indices step 2) { bytes[i]=sample.toByte();bytes[i+1]=(sample shr 8).toByte() }
        val a=FeatureLearningPolicy.PcmAccumulator();a.read(bytes,bytes.size)
        return a.evidence()
    }
    private val quiet get()=evidence(0)
    private val speech get()=evidence(1000)

    @Test fun onlyExplicitConfirmedSupportedPurposeCanOverride() {
        assertEquals(7,FeatureLearningPolicy.source("speechrecognition",6,true,true,7,quiet,speech))
        assertEquals(6,FeatureLearningPolicy.source("telephony",7,true,true,6,quiet,speech))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,false,true,7,quiet,speech))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,false,7,quiet,speech))
        assertEquals(1,FeatureLearningPolicy.source("default",1,true,true,7,quiet,speech))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,true,5,quiet,speech))
    }
    @Test fun actualReadsAndBothWindowsAreRequired() {
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,true,7,FeatureLearningPolicy.PcmEvidence(),speech))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,true,7,quiet,FeatureLearningPolicy.PcmEvidence()))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,true,7,quiet,quiet))
        assertEquals(6,FeatureLearningPolicy.source("speechrecognition",6,true,true,7,quiet,evidence(32767)))
    }
    @Test fun pcmSignedRangeZerosAndClipsAreComputedWithoutRetainingInput() {
        val data=byteArrayOf(0,0,-1,127,0,-128,1,0)
        val a=FeatureLearningPolicy.PcmAccumulator();a.read(data,data.size)
        data.fill(0)
        val p=a.evidence()
        assertEquals(4L,p.samples);assertEquals(1L,p.zeros);assertEquals(2L,p.clipped)
        assertEquals(32768,p.peak);assertEquals(8L,p.bytes);assertTrue(p.rms>20000)
    }
    @Test fun onlyValidPrefixIsInspected() {
        val a=FeatureLearningPolicy.PcmAccumulator();a.read(byteArrayOf(1,0,-1,127),2)
        assertEquals(1L,a.evidence().samples);assertEquals(1,a.evidence().peak)
    }
    @Test fun invalidAndOddReadsCannotBecomeUsable() {
        val a=FeatureLearningPolicy.PcmAccumulator()
        a.read(ByteArray(2),0);a.read(ByteArray(2),-3);a.read(ByteArray(2),3);a.read(ByteArray(3),3)
        assertEquals(4L,a.evidence().readErrors);assertFalse(a.evidence().usable)
    }
    @Test fun recorderFailureInvalidatesAnOtherwiseNonzeroSample() {
        val a=FeatureLearningPolicy.PcmAccumulator();a.read(ByteArray(3200){1},3200)
        a.failed("Bad\nRecorder secret payload")
        val p=a.evidence();assertFalse(p.usable);assertFalse(p.error.contains('\n'))
        assertEquals(1L,p.readErrors)
    }
    @Test fun corruptFloatingPointEvidenceCannotSelectASource() {
        for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0)) {
            assertFalse(speech.copy(squares=bad).usable)
        }
        assertFalse(speech.copy(samples=Long.MAX_VALUE).usable)
        assertFalse(speech.copy(zeros=99999).usable)
    }
    @Test fun silentQuietWindowIsUsefulButNeverProofOfSpeech() {
        assertTrue(quiet.usable);assertTrue(quiet.allZero);assertEquals(0.0,quiet.rms,0.0)
        assertTrue(speech.usable);assertFalse(speech.allZero);assertFalse(speech.clipping)
    }
    @Test fun onlyFiniteKnownMusicBuffersCanBeRemembered() {
        for(value in listOf(300,500,1000)) assertEquals(value,FeatureLearningPolicy.buffer(300,300,value,true))
        for(value in listOf(-1,0,100,301,Int.MAX_VALUE)) assertEquals(300,FeatureLearningPolicy.buffer(300,300,value,true))
        assertEquals(300,FeatureLearningPolicy.buffer(300,300,500,false))
    }
    @Test fun manualMainSettingAlwaysWinsOverLearning() {
        assertEquals(1000,FeatureLearningPolicy.buffer(1000,300,500,true))
        assertEquals(500,FeatureLearningPolicy.buffer(500,300,1000,true))
    }
    @Test fun identicalGpsSnapshotDoesNotAccumulateAgain() {
        val q=FeatureLearningPolicy.GpsQuality(1,10,8,2,0)
        assertFalse(FeatureLearningPolicy.newGpsEvidence(q,q))
        assertTrue(FeatureLearningPolicy.newGpsEvidence(q,q.copy(callbacks=11)))
        assertTrue(FeatureLearningPolicy.newGpsEvidence(q,q.copy(errors=1)))
        assertTrue(FeatureLearningPolicy.newGpsEvidence(q,q.copy(rejected=3)))
        assertTrue(FeatureLearningPolicy.newGpsEvidence(q,q.copy(runId=2)))
        assertFalse(FeatureLearningPolicy.newGpsEvidence(q,q.copy(runId=0)))
    }
    @Test fun gpsAccuracyIsAnAggregateNotPosition() {
        val q=FeatureLearningPolicy.GpsQuality(1,3,2,1,0,2,25.0,5f,20f)
        assertEquals(12.5,q.meanAccuracy,0.0)
        assertEquals(-1.0,FeatureLearningPolicy.GpsQuality().meanAccuracy,0.0)
    }
    @Test fun locationQualityGetterOnlyCopiesExistingMetadata() {
        var now=0L
        val gps=LocationDiagnostics.Gps(99,"USB") { now }
        gps.fix(false,null,"gps",1,5f,false,true,true)
        gps.fix(false,null,"gps",2,20f,false,true,true)
        gps.fix(false,GpsDiagnostics.Rejection.MOCK,"gps",0,1f,true,false,false)
        val q=gps.quality()
        assertEquals(99L,q.runId);assertEquals(3L,q.callbacks);assertEquals(2L,q.fresh)
        assertEquals(1L,q.rejected);assertEquals(12.5,q.meanAccuracy,0.0)
        assertEquals(q,gps.quality());now=10000;assertEquals(q,gps.quality())
    }
    @Test fun invalidAccuracyDoesNotSpoilQualityAggregate() {
        val gps=LocationDiagnostics.Gps(100,"USB") { 0L }
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f)) gps.fix(false,null,"gps",0,value,false,false,false)
        val q=gps.quality();assertEquals(3L,q.fresh);assertEquals(0L,q.accuracySamples)
    }
}
