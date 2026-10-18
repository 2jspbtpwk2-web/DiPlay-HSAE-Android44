package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class MicrophoneDiagnosticsTest {
    private fun begin(diagnostics: MicrophoneDiagnostics, codec: String = "LPCM") =
        diagnostics.begin("speechrecognition", 6, 16000, 1, codec, 20, 640, 320, null)

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { data ->
        samples.forEachIndexed { index, sample ->
            data[index * 2] = sample.toByte(); data[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun noRequestIsNotMisreportedAsSilentOrSuccessfulRecording() {
        val snapshot = MicrophoneDiagnostics { 0L }.snapshot()
        assertTrue(snapshot.contains("保留=0/8"))
        assertTrue(snapshot.contains("尚无实际麦克风启动请求"))
        assertFalse(snapshot.contains("recordingState=3"))
    }

    @Test fun metadataAndActualRecorderStateAreDistinct() {
        val diagnostics = MicrophoneDiagnostics { 1000L }
        val stream = begin(diagnostics)
        stream.buffer(1024, 2560); stream.recordOpened(1, 16000, 1, 7); stream.recordStarted(3)
        stream.socketBound(6, 6, true, true, true)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("协商=LPCM/16000Hz/1ch；frameMs=20；frameBytes=640；samplesPerPacket=320"))
        assertTrue(snapshot.contains("minBuffer=1024；buffer=2560；AudioRecord.state=1；recordingState=3"))
        assertTrue(snapshot.contains("实际=16000Hz/1ch；session=7"))
        assertTrue(snapshot.contains("本地族=6/含scope=true"))
        assertTrue(snapshot.contains("录音开始=+0ms"))
    }

    @Test fun readsKeepNegativeAndZeroResultsAndExceptionSeparate() {
        var now = 0L
        val diagnostics = MicrophoneDiagnostics { now }; val stream = begin(diagnostics)
        now = 2; stream.read(80, 9000)
        stream.read(0, 4000); stream.read(-3, 2000); stream.readException(12000)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("read正/零/负=1/1/1；read抛异常=1；readCalls=4"))
        assertTrue(snapshot.contains("lastReadCode=-2147483648"))
        assertTrue(snapshot.contains("readBytes=80；readMaxUs=12；首读取=+2ms；末读取=+2ms"))
        assertTrue(snapshot.contains("首发送=未发生"))
    }

    @Test fun pcm16SignsLevelsAndClippingAreComputedWithoutMutation() {
        val diagnostics = MicrophoneDiagnostics { 0L }; val stream = begin(diagnostics)
        val bytes = pcm(0, 32767, -32768, 1, -1); val saved = bytes.copyOf()
        assertTrue(stream.pcm(bytes)); assertArrayEquals(saved, bytes)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("PCM帧=1；非零帧=1；近静音帧(peak<=32)=0"))
        assertTrue(snapshot.contains("samples=5 peak=32768 rms=20724.0 rmsDbFS=-4.0 zero=1 clip=2"))
    }

    @Test fun allZeroAndNearSilentHaveDifferentNonzeroCounts() {
        val diagnostics = MicrophoneDiagnostics { 0L }; val stream = begin(diagnostics)
        assertFalse(stream.pcm(pcm(0, 0)))
        assertTrue(stream.pcm(pcm(-32, 32)))
        assertTrue(stream.pcm(pcm(33)))
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("PCM帧=3；非零帧=2；近静音帧(peak<=32)=2"))
        assertTrue(snapshot.contains("zero=2 clip=0"))
    }

    @Test fun zeroRmsHasExplicitMinusInfinityRatherThanNaN() {
        val diagnostics = MicrophoneDiagnostics { 0L }; begin(diagnostics).pcm(pcm(0, 0, 0))
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("rms=0.0 rmsDbFS=负无穷"))
        assertFalse(snapshot.contains("NaN"))
    }

    @Test fun emptyAndOddPcmDoNotAccessPastTheBuffer() {
        val diagnostics = MicrophoneDiagnostics { 0L }; val stream = begin(diagnostics)
        assertFalse(stream.pcm(byteArrayOf()))
        assertFalse(stream.pcm(byteArrayOf(19)))
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("奇数字节帧=1"))
        assertTrue(snapshot.contains("累计电平=未采到完整PCM16样本"))
    }

    @Test fun levelsDoNotRetainPcmAndRecentWindowExpiresButLifetimeRemains() {
        var now = 0L
        val diagnostics = MicrophoneDiagnostics { now }; val stream = begin(diagnostics)
        val bytes = pcm(1000, -1000); stream.pcm(bytes)
        bytes.fill(0)
        now = 4999
        assertTrue(diagnostics.snapshot().contains("近5个单调秒桶: samples=2 peak=1000 rms=1000.0"))
        now = 5000
        val expired = diagnostics.snapshot()
        assertTrue(expired.contains("累计电平: samples=2 peak=1000 rms=1000.0"))
        assertTrue(expired.contains("近5个单调秒桶=未采到完整PCM16样本"))
        now = 6000; stream.pcm(pcm(20))
        val fresh = diagnostics.snapshot()
        assertTrue(fresh.contains("近5个单调秒桶: samples=1 peak=20 rms=20.0"))
        assertTrue(fresh.contains("累计电平: samples=3 peak=1000"))
    }

    @Test fun encoderAndSuccessfulSendDoNotClaimRecognition() {
        var now = 0L
        val diagnostics = MicrophoneDiagnostics { now }; val stream = begin(diagnostics, "OPUS")
        stream.encoderOpened("OMX.test.opus"); stream.encoderInputTimeout()
        stream.encoded(0, 2000); stream.encoded(2, 4000)
        now = 50; stream.sent(100, 5000)
        now = 75; stream.sendFailed(10000)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("编码器=OMX.test.opus；编码调用=2；输入超时=1；编码单元=2；空输出=1；编码MaxUs=4"))
        assertTrue(snapshot.contains("UDP发送=1/100 bytes；send失败=1；sendMaxUs=10；首发送=+50ms；末发送=+50ms；末发送年龄Ms=25"))
        assertTrue(snapshot.contains("不证明iPhone收到或Siri识别"))
    }

    @Test fun stageErrorsExcludeExceptionMessagesAndFinishedFlowRetainsEvidence() {
        var now = 0L
        val diagnostics = MicrophoneDiagnostics { now }; val stream = begin(diagnostics)
        stream.phase(MicrophoneDiagnostics.Phase.UDP_SEND)
        stream.failure(java.io.IOException("secret-key-and-voice-content"))
        stream.closeRequested("stream-stop")
        now = 200; stream.finish("error")
        now = 900; stream.finish("completed"); stream.phase(MicrophoneDiagnostics.Phase.CREATED)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("阶段=FINISHED"))
        assertTrue(snapshot.contains("结束=+200ms；结束原因=error；停止请求=stream-stop"))
        assertTrue(snapshot.contains("错误=1；错误阶段=UDP_SEND；错误类型=IOException"))
        assertFalse(snapshot.contains("secret-key-and-voice-content"))
    }

    @Test fun retentionIsBoundedAndEndedStreamsAreKeptUntilEvicted() {
        val diagnostics = MicrophoneDiagnostics { 0L }
        begin(diagnostics).finish("stopped")
        repeat(7) { begin(diagnostics) }
        assertTrue(diagnostics.snapshot().contains("麦克风流#1："))
        begin(diagnostics)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("保留=8/8；更早流省略=1"))
        assertFalse(snapshot.contains("麦克风流#1："))
        assertTrue(snapshot.contains("麦克风流#2："))
        assertTrue(snapshot.contains("麦克风流#9："))
    }

    @Test fun cleanupErrorsCannotOverwriteTheFailureStageOrExposeMessages() {
        val diagnostics = MicrophoneDiagnostics { 0L }; val stream = begin(diagnostics)
        stream.phase(MicrophoneDiagnostics.Phase.RECORD_START)
        stream.failure(IllegalStateException("private-original"))
        stream.cleanupFailed("record-stop", IllegalStateException("private-cleanup"))
        stream.finish("error")
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("错误阶段=RECORD_START；错误类型=IllegalStateException"))
        assertTrue(snapshot.contains("清理错误=1；末清理操作=record-stop；清理错误类型=IllegalStateException"))
        assertFalse(snapshot.contains("private-"))
    }

    @Test fun nonWhitelistedPurposeCodecAndReasonsCannotInjectReportText() {
        val diagnostics = MicrophoneDiagnostics { 0L }
        val stream = diagnostics.begin("user-speech", 6, 16000, 1, "content", 20, 640, 320, null)
        stream.closeRequested("secret-stop"); stream.finish("secret-finish")
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("用途=其他")); assertTrue(snapshot.contains("协商=其他/"))
        assertTrue(snapshot.contains("结束原因=其他；停止请求=其他"))
        assertFalse(snapshot.contains("secret-")); assertFalse(snapshot.contains("user-speech"))
    }
}
