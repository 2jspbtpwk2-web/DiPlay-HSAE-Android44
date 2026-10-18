package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class MediaDiagnosticsTest {
    @Test fun aggregateInputPacketsDoNotPromisePhysicalMicrophoneCapture() {
        val diagnostics=MediaDiagnostics { 1_000_000L }
        diagnostics.microphoneSentPackets.incrementAndGet()
        val summary=diagnostics.summary()
        assertTrue(summary.contains("输入上行UDP发送包（含合成静音）=1"))
        assertTrue(summary.contains("麦克风读取字节=0"))
    }
    @Test fun playbackHeadIsUnsignedAndWrapAdvancesBySmallDelta() {
        val head = PlaybackHeadProgress(4)
        val before = head.sample(0xffff_fffdL.toInt(), Long.MAX_VALUE)
        assertEquals(0xffff_fffdL, before.rawHead)
        val after = head.sample(2, Long.MAX_VALUE)
        assertEquals(5L, after.advanced)
        assertEquals(0x1_0000_0002L, after.frames)
        assertFalse(after.reset)
    }
    @Test fun unchangedPlaybackHeadDoesNotClaimOutputProgress() {
        val head = PlaybackHeadProgress(4)
        assertEquals(0L, head.sample(0, 400).advanced)
        assertEquals(400L, head.sample(0, 400).queuedBytes)
        assertEquals(100L, head.sample(100, 400).advanced)
        assertEquals(0L, head.sample(100, 400).queuedBytes)
    }
    @Test fun backwardsResetDoesNotClaimBillionsOfPlayedFrames() {
        val head = PlaybackHeadProgress(2)
        head.sample(1000, 4000)
        val reset = head.sample(0, 4000)
        assertTrue(reset.reset)
        assertEquals(0L, reset.advanced)
        assertEquals(1000L, reset.frames)
        assertEquals(10L, head.sample(10, 4000).advanced)
    }
    @Test fun headCanRunPastWrittenBytesWithoutNegativeQueueEstimate() {
        assertEquals(0L, PlaybackHeadProgress(4).sample(200, 100).queuedBytes)
    }
    @Test fun noEventsDoNotLookLikeAnImmediateFirstFrameOrAudioOutput() {
        val diagnostics = MediaDiagnostics { 1_000_000L }
        val summary = diagnostics.summary()
        assertTrue(summary.contains("首视频=未发生"))
        assertTrue(summary.contains("首解码输出释放=未发生"))
        assertTrue(summary.contains("首播放头推进=未发生"))
    }
    @Test fun receivedDataAndOutputAreSeparateFirstAndLastEvents() {
        var now = 0L
        val diagnostics = MediaDiagnostics { now }
        now = 2_000_000L; diagnostics.receivedVideo(80, 1); diagnostics.receivedAudio()
        now = 5_000_000L; diagnostics.receivedVideo(90, 3); diagnostics.receivedAudio()
        assertEquals(2L, diagnostics.videoReceived.get())
        assertEquals(170L, diagnostics.videoBytes.get())
        assertEquals(3L, diagnostics.videoQueuePeak.get())
        assertTrue(diagnostics.summary().contains("首视频=+2ms；末视频=+5ms"))
        assertTrue(diagnostics.summary().contains("首解码输出释放=未发生"))
        assertTrue(diagnostics.summary().contains("首AudioTrack写入=未发生"))
        now = 8_000_000L; diagnostics.outputVideo(); diagnostics.wroteAudio(100)
        now = 10_000_000L; diagnostics.advancedAudio(0)
        assertTrue(diagnostics.summary().contains("首播放头推进=未发生"))
        now = 12_000_000L; diagnostics.advancedAudio(4)
        now = 15_000_000L; diagnostics.advancedAudio(6)
        assertEquals(10L, diagnostics.audioPlayedFrames.get())
        assertTrue(diagnostics.summary().contains("首播放头推进=+12ms；末播放头推进=+15ms"))
        assertTrue(diagnostics.summary().contains("首解码输出释放=+8ms"))
    }
    @Test fun stageMarkersAreReportedOnlyOncePerReceiver() {
        val diagnostics = MediaDiagnostics { 0L }
        assertTrue(diagnostics.receivedVideo(1, 1)); assertFalse(diagnostics.receivedVideo(1, 1))
        assertTrue(diagnostics.receivedAudio()); assertFalse(diagnostics.receivedAudio())
        assertTrue(diagnostics.outputVideo()); assertFalse(diagnostics.outputVideo())
        assertTrue(diagnostics.wroteAudio(4)); assertFalse(diagnostics.wroteAudio(4))
        assertFalse(diagnostics.advancedAudio(0)); assertTrue(diagnostics.advancedAudio(1)); assertFalse(diagnostics.advancedAudio(1))
    }
}
