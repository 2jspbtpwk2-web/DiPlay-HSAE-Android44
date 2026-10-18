package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class FunctionRunDiagnosticsTest {
    @Test fun inputTimelineDistinguishesRecordingFromSyntheticSilence() {
        val text=MicrophoneNegotiationDiagnostics().snapshot()
        assertTrue(text.contains("实际录音/合成静音见capture与逐流证据"))
        assertFalse(text.contains("再启动录音"))
    }
    @Test fun diagnosticJobHasOneOwnerAcrossActivities() {
        DiagnosticRunState.finish()
        assertTrue(DiagnosticRunState.begin()); assertFalse(DiagnosticRunState.begin())
        DiagnosticRunState.finish(); assertTrue(DiagnosticRunState.begin()); DiagnosticRunState.finish()
    }
    @Test fun siriRequestsCannotBuildAnUnboundedQueue() {
        FunctionRunDiagnostics.finishSiri("reset")
        assertTrue(FunctionRunDiagnostics.beginSiri()); assertFalse(FunctionRunDiagnostics.beginSiri())
        FunctionRunDiagnostics.finishSiri("write-false"); assertTrue(FunctionRunDiagnostics.beginSiri())
        FunctionRunDiagnostics.finishSiri("write-true")
        assertTrue(FunctionRunDiagnostics.snapshot().contains("write-false"))
    }
    @Test fun eventHistoryIsBounded() {
        repeat(1000) { FunctionRunDiagnostics.event("ui", "resumed=$it") }
        assertTrue(FunctionRunDiagnostics.snapshot().lines().size<=65)
        assertFalse(FunctionRunDiagnostics.snapshot().contains("resumed=0\n"))
    }
    @Test fun unknownEventCategoryIsRejected() {
        FunctionRunDiagnostics.event("unknown-secret", "PRIVATE_SENTINEL")
        assertFalse(FunctionRunDiagnostics.snapshot().contains("PRIVATE_SENTINEL"))
    }
    @Test fun microphoneNegotiationDoesNotAcceptPayloads() {
        val d=MicrophoneNegotiationDiagnostics()
        d.record(100,"speechrecognition","reason=missing-input-key")
        d.record(100,"PRIVATE_PURPOSE","key=PRIVATE_KEY address=fe80::1234 pcm=PRIVATE_PCM")
        assertTrue(d.snapshot().contains("missing-input-key")); assertFalse(d.snapshot().contains("PRIVATE_"))
        assertFalse(d.snapshot().contains("fe80::"))
    }
    @Test fun microphoneNegotiationKeepsOnlyRecentNumericEvents() {
        val d=MicrophoneNegotiationDiagnostics()
        repeat(100) { d.record(100,"telephony","negotiated codec=LPCM rate=16000 channels=1 frameMs=20 family=ipv6 scope=3") }
        assertEquals(49,d.snapshot().lines().size)
    }
    @Test fun microphoneEmptyEvidenceDoesNotImplyFailure() {
        assertTrue(MicrophoneNegotiationDiagnostics().snapshot().contains("不能据此判断硬件故障"))
    }
}
