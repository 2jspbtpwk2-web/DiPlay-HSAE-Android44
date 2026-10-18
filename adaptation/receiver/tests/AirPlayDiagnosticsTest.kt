package local.airuize.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayDiagnosticsTest {
    @Test fun negotiatedInputDoesNotPromisePhysicalRecording() {
        assertEquals("输入上行已协商：等待音频首包后启动输入上行（实际录音/合成静音见capture设置与逐流证据）",
            AirPlayDiagnostics.summary("receiver microphone negotiated waitingForAudio=true"))
    }
    @Test fun acceptsOnlyBoundedNumericVideoPipelineAggregate() {
        val message = "VideoPipeline windowMs=5001 messages=120 frames=110 frameBytes=40000 " +
            "configs=1 emptyMessages=9 emptyFrames=0 otherMessages=9 fpsMilli=21995 readMaxMs=300 " +
            "frameGapMaxMs=450 lastFrameAgeMs=8 cryptoTotalUs=100000 cryptoMaxUs=5000 " +
            "annexBTotalUs=1000 annexBMaxUs=100 handoffTotalUs=500 handoffMaxUs=10 ended=false"
        assertEquals(message, AirPlayDiagnostics.summary(message))
        assertEquals(message.replace("lastFrameAgeMs=8", "lastFrameAgeMs=none"),
            AirPlayDiagnostics.summary(message.replace("lastFrameAgeMs=8", "lastFrameAgeMs=none")))
        for (bad in listOf(
            message + " payload=secret",
            message + "\nsecret",
            message + "\u0000",
            message.replace("frames=110", "frames=-1"),
            message.replace("frames=110", "frames=1234567890123"),
            message.replace("ended=false", "ended=private"),
            message.replace("lastFrameAgeMs=8", "lastFrameAgeMs=fe80::1"),
        )) assertNull(AirPlayDiagnostics.summary(bad))
    }

    @Test fun acceptsProtocolStageAndDurationWithoutHeaders() {
        val message = "airplay diag request stage=pair-verify status=200 durationMs=21 elapsedMs=600"
        assertEquals(message, AirPlayDiagnostics.summary(message))
    }

    @Test fun acceptsTcpEventAndTimingMilestones() {
        for (message in listOf(
            "airplay diag control accepted localPort=7000 elapsedMs=0",
            "airplay diag event listening localPort=54321 elapsedMs=720",
            "airplay diag event accepted elapsedMs=730",
            "airplay diag event encryption-ready elapsedMs=731",
            "airplay diag timing listening localPort=54322 peerPort=54323 senderStarted=true elapsedMs=710",
            "airplay diag timing first-request-sent",
            "airplay diag timing first-valid-sample",
            "airplay diag timing synchronized sampleCount=2",
            "airplay diag timing summary txRequests=4 txResponses=1 rxRequests=1 rxResponses=3 validSamples=3 synced=true sendErrors=0 receiveErrors=0",
            "airplay diag keepalive listening localPort=54324 elapsedMs=711",
            "airplay diag keepalive first-packet elapsedMs=800",
        )) assertEquals(message, AirPlayDiagnostics.summary(message))
    }

    @Test fun acceptsOnlyKnownStreamMetadata() {
        val message = "airplay diag setup requested kind=audio type=100 width=unknown height=unknown fps=unknown audioFormat=8388608 sampleRate=48000 channels=2 latencyMs=100 framesPerPacket=352 peerDataPort=12345 peerControlPort=unknown audioType=media"
        assertEquals(message, AirPlayDiagnostics.summary(message))
        assertNull(AirPlayDiagnostics.summary(message.replace("audioType=media", "audioType=PrivateDeviceName")))
        assertNull(AirPlayDiagnostics.summary(message + " streamConnectionID=123456789"))
    }

    @Test fun recordsAcceptedAndRejectedSetupWithoutSecrets() {
        for (message in listOf(
            "airplay diag setup result kind=screen type=110 accepted=true dataPort=54325 controlPort=unknown timingPort=unknown",
            "airplay diag setup result kind=audio type=100 accepted=false dataPort=unknown controlPort=unknown timingPort=unknown",
            "airplay diag setup session peerTimingPort=54326 keepAliveRequested=false elapsedMs=700",
            "airplay diag setup rejected reason=plist-decode elapsedMs=700",
        )) assertEquals(message, AirPlayDiagnostics.summary(message))
    }

    @Test fun rejectsRawSetupMapsHeadersAndTrace() {
        for (message in listOf(
            "airplay SETUP stream type=100 payload={shk=secret, streamConnectionID=123}",
            "airplay SETUP response streams=[{streamConnectionID=123}]",
            "airplay diag setup requested payload={shk=secret}",
            "TRACE airplay control rx headers={device-id=private} bodyHex=abcdef",
            "airplay diag request stage=SETUP status=200 durationMs=1 elapsedMs=2 header=private",
        )) assertNull(AirPlayDiagnostics.summary(message))
    }

    @Test fun rejectsUnknownStagesErrorsAndControlCharacters() {
        val known = "airplay diag error stage=SETUP error=handler durationMs=1 elapsedMs=2"
        assertEquals(known, AirPlayDiagnostics.summary(known))
        assertNull(AirPlayDiagnostics.summary(known.replace("stage=SETUP", "stage=PrivateDeviceName")))
        assertNull(AirPlayDiagnostics.summary(known.replace("error=handler", "error=rawExceptionMessage")))
        assertNull(AirPlayDiagnostics.summary(known + "\nsecret"))
        assertNull(AirPlayDiagnostics.summary(known + "\u0000"))
    }

    @Test fun rejectsUnboundedNumericValuesAndPorts() {
        assertNull(AirPlayDiagnostics.summary("airplay diag control accepted localPort=123456 elapsedMs=0"))
        assertNull(AirPlayDiagnostics.summary("airplay diag control accepted localPort=7000 elapsedMs=1234567890123"))
        assertNull(AirPlayDiagnostics.summary("airplay diag control accepted localPort=-1 elapsedMs=0"))
    }

    @Test fun recordsAdvertisedConfigurationAndFinalCounters() {
        val advertised = "airplay diag advertised width=960 height=1280 fps=30 hevc=false microphone=true audioRate=48000"
        val closed = "airplay diag control closed reason=eof lastStage=RECORD requests=10 activeStreams=2 idleMs=500 elapsedMs=10000"
        assertEquals(advertised, AirPlayDiagnostics.summary(advertised))
        assertEquals(closed, AirPlayDiagnostics.summary(closed))
    }

    @Test fun preservesExistingPrivacySafeSummaries() {
        assertEquals("AirPlay 控制通道加密已启用", AirPlayDiagnostics.summary("airplay control encryption enabled"))
        assertEquals("AirPlay 请求：SETUP other；CSeq=9；body=400", AirPlayDiagnostics.summary("airplay rx SETUP rtsp://private-device/session cseq=9 body=400"))
        assertEquals("AirPlay 事件通道 TCP 已接入", AirPlayDiagnostics.summary("airplay event connection accepted from /fe80::private:12345"))
    }

    @Test fun rejectsTimingAddressesAndRawClockTimestamps() {
        assertNull(AirPlayDiagnostics.summary("airplay diag timing first-valid-sample peer=fe80::private"))
        assertNull(AirPlayDiagnostics.summary("airplay diag timing synchronized sampleCount=2 origin=123456789012345"))
        assertNull(AirPlayDiagnostics.summary("airplay diag timing summary txRequests=4 txResponses=1 rxRequests=1 rxResponses=3 validSamples=3 synced=true sendErrors=0 receiveErrors=0 key=secret"))
    }
}
