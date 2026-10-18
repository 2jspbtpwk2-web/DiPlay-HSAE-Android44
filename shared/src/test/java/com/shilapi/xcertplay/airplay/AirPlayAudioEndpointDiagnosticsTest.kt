package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayAudioEndpointDiagnosticsTest {
    @Test fun zeroRequestsDoesNotInventAPlaybackPath() {
        val s=AirPlayAudioEndpointDiagnostics().snapshot()
        assertTrue(s.contains("audioSetupRequests=0"));assertTrue(s.contains("不等于实际出声"))
    }
    @Test fun modesExposeOnlyKnownNumericOwners() {
        val d=AirPlayAudioEndpointDiagnostics()
        d.command("modesChanged",mapOf("resources" to listOf(mapOf("resourceID" to 2,"entity" to 1,"permanentEntity" to 2)),
            "appStates" to listOf(mapOf("appStateID" to 1,"entity" to 1,"speechMode" to 2))))
        val s=d.snapshot()
        assertTrue(s.contains("id=2 owner=1 permanent=2"));assertTrue(s.contains("id=1 owner=1 speechMode=2"))
    }
    @Test fun unknownValuesAndPayloadsAreNotExported() {
        val d=AirPlayAudioEndpointDiagnostics()
        d.command("private-identifier",mapOf("secret" to "voice text"))
        d.command("modesChanged",mapOf("resources" to listOf(mapOf("resourceID" to "secret", "entity" to byteArrayOf(1,2),"permanentEntity" to 99))))
        val s=d.snapshot()
        assertTrue(s.contains("command=other fieldCount=1"));assertTrue(s.contains("id=unknown owner=unknown permanent=unknown"))
        assertFalse(s.contains("private-identifier"));assertFalse(s.contains("secret"));assertFalse(s.contains("voice text"))
    }
    @Test fun streamRequestHistoryRetainsOnlyAudioMetadata() {
        val d=AirPlayAudioEndpointDiagnostics()
        d.requested(110,mapOf("secret" to "screen"))
        d.requested(100,mapOf("audioType" to "speechRecognition","audioFormat" to 16,"secret" to "voice"))
        val s=d.snapshot();assertTrue(s.contains("audioSetupRequests=1"));assertTrue(s.contains("audioType=speechrecognition"))
        assertFalse(s.contains("secret"));assertFalse(s.contains("voice"))
    }
    @Test fun boundedEventsDoNotGrowWithCommands() {
        val d=AirPlayAudioEndpointDiagnostics()
        repeat(100) { d.command("flushAudio", emptyMap()) }
        val s=d.snapshot();assertTrue(s.contains("commands=100"));assertEquals(32,s.lineSequence().count { it.startsWith("+") })
    }
    @Test fun completeEndpointAdvertisesMonoPcmInputEvenWithOpusOff() {
        val config=AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:02","950.7.1",
            AirPlayDisplayConfig(720,864),entertainmentSampleRate=44100,microphone=true)
        val info=AirPlayInfoPlist.build(config)
        val endpoint=AirPlayAudioEndpointDiagnostics()
        endpoint.advertised(config,info)
        assertTrue(endpoint.snapshot().contains("inputEndpoint=true entertainmentRate=44100 audioFormats=9 inputFormats=4"))
        val formats=(info["audioFormats"] as List<*>).map { it as Map<*,*> }
        for(purpose in listOf("compatibility","default","telephony","speechRecognition")) {
            val input=formats.single { it["type"]==100 && it["audioType"]==purpose }["audioInputFormats"] as Int
            assertTrue(input and 0x554==0x554)
        }
        assertTrue(formats.any { it["type"]==102 && it["audioOutputFormats"]==0x400000 })
    }
}
