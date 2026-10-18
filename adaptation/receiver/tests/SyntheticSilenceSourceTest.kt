package local.airuize.receiver

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.junit.Assert.*
import org.junit.Test

class SyntheticSilenceSourceTest {
    @Test fun silenceIsAlwaysZeroAndDoesNotRetainConsumerChanges() {
        var now=0L
        val source=SyntheticSilenceSource(640,20,{now},{now+=it})
        val first=source.nextFrame()!!
        assertEquals(640,first.size); assertTrue(first.all { it==0.toByte() })
        first[0]=12
        val second=source.nextFrame()!!
        assertSame(first,second); assertTrue(second.all { it==0.toByte() })
    }
    @Test fun monotonicPacingDoesNotSendFasterThanNegotiatedFrames() {
        var now=123L; val waits=mutableListOf<Long>()
        val source=SyntheticSilenceSource(640,20,{now},{waits.add(it);now+=it})
        repeat(5) { assertNotNull(source.nextFrame()) }
        assertEquals(listOf(20_000_000L,20_000_000L,20_000_000L,20_000_000L),waits)
    }
    @Test fun lateSendDoesNotCauseCatchupBurst() {
        var now=0L; val waits=mutableListOf<Long>()
        val source=SyntheticSilenceSource(640,20,{now},{waits.add(it);now+=it})
        source.nextFrame(); now=900_000_000L
        source.nextFrame(); source.nextFrame()
        assertEquals(listOf(20_000_000L),waits)
    }
    @Test fun stopIsIdempotentAndPreventsLaterFrames() {
        val source=SyntheticSilenceSource(640,20)
        source.stop();source.stop();assertNull(source.nextFrame())
    }
    @Test fun stopDuringPacingDoesNotReturnExtraFrame() {
        var now=0L; lateinit var source:SyntheticSilenceSource
        source=SyntheticSilenceSource(640,20,{now},{source.stop()})
        assertNotNull(source.nextFrame());assertNull(source.nextFrame())
    }
    @Test fun spuriousWakeupWaitsForMonotonicDeadline() {
        var now=0L; var waits=0
        val source=SyntheticSilenceSource(640,20,{now},{waits++;now+=it/2+1})
        source.nextFrame();source.nextFrame()
        assertTrue(waits>1);assertTrue(now>=20_000_000L)
    }
    @Test fun zeroFrameUsesNormalAuthenticatedRtpAndAdvancesCounters() {
        var now=0L;val source=SyntheticSilenceSource(640,20,{now},{now+=it})
        val key=ByteArray(32) { it.toByte() };val counters=MicrophoneCounters()
        repeat(3) { sequence ->
            val packet=MicrophonePacketizer.sealPacket(key,100,counters,
                MicrophonePacketizer.toWirePcm(source.nextFrame()!!),320)
            val end=packet.size-8;val nonce=ByteArray(12)
            packet.copyInto(nonce,4,end,packet.size)
            val pcm=AirPlayCrypto.chachaOpen(key,nonce,packet.copyOfRange(12,end),packet.copyOfRange(4,12))
            assertEquals(640,pcm.size);assertTrue(pcm.all { it==0.toByte() })
            assertEquals(sequence+1,counters.sequence);assertEquals((sequence+1)*320,counters.timestamp)
            assertEquals((sequence+1).toLong(),counters.nonce)
        }
    }
    @Test fun syntheticEvidenceNeverCountsAsActualCaptureOrRecordedPcm() {
        val d=MicrophoneDiagnostics {0L}
        val flow=d.begin("speechrecognition",-1,16000,1,"LPCM",20,640,320,null,capture=false)
        repeat(3) { flow.syntheticFrame(640);flow.sent(676,1000L) };flow.finish("stopped")
        val snapshot=d.snapshot()
        assertTrue(snapshot.contains("capture=false syntheticSilence=true"))
        assertTrue(snapshot.contains("合成静音帧=3/1920 bytes（不计为录音）"))
        assertTrue(snapshot.contains("readBytes=0"));assertTrue(snapshot.contains("PCM帧=0"))
        assertTrue(snapshot.contains("AudioRecord.state=-1；recordingState=-1"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectOddFrameLength() { SyntheticSilenceSource(3,20) }
    @Test(expected=IllegalArgumentException::class) fun rejectUnboundedFrameLength() { SyntheticSilenceSource(1_000_000,20) }
    @Test(expected=IllegalArgumentException::class) fun rejectInvalidPacing() { SyntheticSilenceSource(640,0) }
}
