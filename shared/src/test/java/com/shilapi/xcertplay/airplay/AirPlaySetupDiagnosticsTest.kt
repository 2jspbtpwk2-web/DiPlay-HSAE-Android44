package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlaySetupDiagnosticsTest {
    @Test fun requestIncludesOnlyExplicitNumericFieldsAndKnownPurpose() {
        val summary = AirPlaySetupDiagnostics.request(100, mapOf(
            "audioFormat" to 0x800000L, "sampleRate" to 48000L, "channels" to 2L,
            "audioLatencyMs" to 100L, "framesPerPacket" to 352L, "dataPort" to 12345L,
            "audioType" to "Media", "streamConnectionID" to "PRIVATE_ID", "shk" to "PRIVATE_KEY",
            "deviceID" to "PRIVATE_DEVICE", "name" to "PRIVATE_NAME", "unrecognized" to "PRIVATE_FIELD",
        ))
        assertTrue(summary.contains("kind=audio type=100"))
        assertTrue(summary.contains("audioFormat=8388608 sampleRate=48000 channels=2 latencyMs=100 framesPerPacket=352 peerDataPort=12345"))
        assertTrue(summary.endsWith("audioType=media"))
        assertFalse(summary.contains("PRIVATE"))
        assertFalse(summary.contains("streamConnectionID"))
        assertFalse(summary.contains("shk"))
    }

    @Test fun doesNotStringifyUnexpectedObjectsOrStringEncodedNumbers() {
        val untrusted = object {
            override fun toString(): String = throw AssertionError("Untrusted value stringified")
        }
        val summary = AirPlaySetupDiagnostics.request(110, mapOf(
            "width" to untrusted, "height" to "1280", "fps" to Double.NaN,
            "audioType" to untrusted, "dataPort" to byteArrayOf(1, 2, 3), "secret" to untrusted,
        ))
        assertTrue(summary.contains("width=unknown height=unknown fps=unknown"))
        assertTrue(summary.endsWith("audioType=unknown"))
    }

    @Test fun rejectsNegativeOverflowAndOutOfRangeValues() {
        val summary = AirPlaySetupDiagnostics.request(-1, mapOf(
            "width" to 16385L, "height" to -1L, "fps" to 241L, "audioFormat" to Long.MAX_VALUE,
            "sampleRate" to 384001L, "channels" to 33L, "audioLatencyMs" to 60001L,
            "framesPerPacket" to 65536L, "dataPort" to 65536L, "controlPort" to -1L,
            "audioType" to "PRIVATE_NAME",
        ))
        assertFalse(summary.contains("PRIVATE"))
        assertFalse(summary.contains("9223372036854775807"))
        assertTrue(summary.contains("kind=other type=unknown width=unknown height=unknown fps=unknown audioFormat=unknown"))
        assertTrue(summary.contains("peerDataPort=unknown peerControlPort=unknown audioType=unknown"))
    }

    @Test fun screenAndResponsePortsUseSafeBounds() {
        val summary = AirPlaySetupDiagnostics.request(110, mapOf("widthPixels" to 960, "heightPixels" to 1280, "fps" to 30))
        assertTrue(summary.contains("kind=screen type=110 width=960 height=1280 fps=30"))
        assertEquals("airplay diag setup result kind=screen type=110 accepted=true dataPort=54321 controlPort=unknown timingPort=unknown",
            AirPlaySetupDiagnostics.result(110, "screen", true, mapOf("dataPort" to 54321L, "controlPort" to -1, "shk" to "PRIVATE_KEY")))
        assertEquals("airplay diag setup result kind=other type=unknown accepted=false dataPort=unknown controlPort=unknown timingPort=unknown",
            AirPlaySetupDiagnostics.result(-1, "PRIVATE_KIND", false, null))
    }

    @Test fun advertisedDisplayOmitsDeviceIdentityAndLimitsNumericValues() {
        val config = AirPlayConfig("PRIVATE_NAME", "PRIVATE_ID", "PRIVATE_MAC", "PRIVATE_VERSION", AirPlayDisplayConfig(960, 1280, fps=30), microphone=true)
        assertEquals("airplay diag advertised width=960 height=1280 fps=30 hevc=false microphone=true audioRate=48000", AirPlaySetupDiagnostics.advertised(config))
        assertFalse(AirPlaySetupDiagnostics.advertised(config).contains("PRIVATE"))
    }
}
