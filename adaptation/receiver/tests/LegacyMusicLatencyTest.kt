package local.airuize.receiver

import com.shilapi.xcertplay.media.MediaAudioBuffer
import org.junit.Assert.*
import org.junit.Test

class LegacyMusicLatencyTest {
    @Test fun carMusicCapacityNoLongerInheritsFourTimesVoiceMinimum() {
        val old = MediaAudioBuffer.plan(true, 44100, 2, 36864, 300)
        val next = LegacyMusicLatency.plan(44100, 2, 36864, 300)
        assertEquals(147456, old.trackBufferBytes); assertEquals(88200, next.trackBufferBytes)
        assertEquals(52920, next.startBytes); assertEquals(old.startBytes, next.startBytes)
        assertTrue(next.trackBufferBytes >= 36864)
    }
    @Test fun largerUserStartupBuffersArePreserved() {
        for (millis in listOf(300, 500, 1000)) {
            val p = LegacyMusicLatency.plan(44100, 2, 36864, millis)
            assertEquals(176400L * millis / 1000L, p.startBytes.toLong())
            assertTrue(p.trackBufferBytes > p.startBytes); assertEquals(0, p.trackBufferBytes % 4)
        }
    }
    @Test fun systemMinimumAndFrameAlignmentAreNeverUndercut() {
        val p = LegacyMusicLatency.plan(8000, 1, 100001, 300)
        assertTrue(p.trackBufferBytes >= 100001); assertTrue(p.startBytes >= 100001)
        assertEquals(0, p.trackBufferBytes % 2)
    }
}
