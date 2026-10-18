package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class Iap2LocationReporterTest {
    private val provider = FakeProvider()
    private val sent = mutableListOf<Iap2Frame>()
    private val progress = mutableListOf<String>()
    private val request = Iap2LocationRequest()
    private var nowMillis = 0L

    // What the iPhone sent on the Bluetooth link in the car: GGA, RMC, PASCD and their intervals.
    private val start = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
        u16(0, 0)
        void(1)
        void(2)
        void(4)
        u32(0x8001, 1000)
    }
    private val stop = Iap2Messages.buildRaw(Iap2LocationMessages.STOP_LOCATION_INFORMATION) {}

    private fun bluetooth() = Iap2LocationReporter(provider, { progress += it }, request, nanoTime = { nowMillis * 1_000_000 })
    private fun wifi() =
        Iap2LocationReporter(provider, { progress += it }, request, continueRequest = true, nanoTime = { nowMillis * 1_000_000 })

    @Test
    fun theBluetoothLinkSendsAndRecordsTheRequest() {
        val link = bluetooth()

        assertTrue(link.handle(start) { sent += it })
        assertEquals(setOf(0, 1, 2, 4, 0x8001), request.components)
        assertTrue(provider.started)
        nowMillis += 1_000
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.messageId == Iap2LocationMessages.LOCATION_INFORMATION })

        assertTrue(link.handle(stop) { sent += it })
        assertNull(request.components)
        assertFalse(provider.started)
        link.tick { sent += it }
        assertEquals(2, sent.size)
    }

    @Test
    fun theWifiLinkContinuesTheBluetoothRequest() {
        bluetooth().handle(start) { }
        provider.stop() // the Bluetooth link closed without 0xFFFC

        val link = wifi()
        link.tick { sent += it }
        nowMillis += 1_000
        link.tick { sent += it }

        assertTrue(provider.started)
        // The provider learns what was asked for, so vehicle speed follows the request onto Wi-Fi.
        assertEquals(setOf(0, 1, 2, 4, 0x8001), provider.requested)
        assertEquals(2, sent.size)
        assertTrue(progress.any { it.startsWith("iap2 location request continues from the Bluetooth link") })
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun theWifiLinkSendsNothingWithoutARequest() {
        val link = wifi()
        link.tick { sent += it }

        assertFalse(provider.started)
        assertTrue(sent.isEmpty())
        // It still wakes every second, in case the Bluetooth request comes after the Wi-Fi link starts.
        assertEquals(1_000L, link.pollTimeout(60_000L))
        assertEquals(60_000L, Iap2LocationReporter(null, {}, request, continueRequest = true).pollTimeout(60_000L))
    }

    @Test
    fun aStopOnTheWifiLinkIsNotUndone() {
        bluetooth().handle(start) { }
        val link = wifi()
        link.tick { sent += it }

        link.handle(stop) { sent += it }
        link.tick { sent += it }

        assertFalse(provider.started)
        assertEquals(1, sent.size)
        assertEquals(60_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun aBurstOfIncomingMessagesStillSendsOneFixASecond() {
        // The loop ticks after every incoming message; at session start the iPhone sends dozens at once.
        val link = bluetooth()
        link.handle(start) { sent += it }
        repeat(12) { link.tick { sent += it } }
        assertEquals(1, sent.size)

        nowMillis += 400
        repeat(5) { link.tick { sent += it } }
        assertEquals(1, sent.size)
        assertEquals(600L, link.pollTimeout(60_000L))

        nowMillis += 600
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun noFixMeansNothingIsSent() {
        provider.nmea = null
        val link = bluetooth()
        link.handle(start) { sent += it }
        link.tick { sent += it }

        assertTrue(provider.started)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anOptionalRequestFailureDoesNotEscapeOrStartTheProvider() {
        provider.failAt = "request"
        val link = bluetooth()
        assertTrue(link.handle(start) { sent += it })
        nowMillis = 2_000
        link.tick { sent += it }
        assertFalse(provider.started)
        assertEquals(0, provider.starts)
        assertEquals(0, provider.fixCalls)
        assertTrue(sent.isEmpty())
        assertTrue(latestDiagnostic().contains("provider异常=1"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=0"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun anOptionalStartFailureIsStoppedAndDoesNotEscape() {
        provider.failAt = "start"
        val link = bluetooth()
        assertTrue(link.handle(start) { sent += it })
        assertEquals(1, provider.starts)
        assertEquals(1, provider.stops)
        assertFalse(provider.started)
        assertTrue(sent.isEmpty())
        assertTrue(latestDiagnostic().contains("启动=1 启动失败=1"))
        assertTrue(latestDiagnostic().contains("provider异常=1"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun anOptionalFixFailureRetriesAtTheOriginalOneSecondInterval() {
        provider.failAt = "fix"
        val link = bluetooth()
        link.handle(start) { sent += it }
        assertEquals(1, provider.fixCalls)
        assertTrue(sent.isEmpty())
        provider.failAt = ""
        nowMillis = 999
        link.tick { sent += it }
        assertEquals(1, provider.fixCalls)
        assertTrue(sent.isEmpty())
        assertEquals(1L, link.pollTimeout(60_000))
        nowMillis = 1_000
        link.tick { sent += it }
        assertEquals(2, provider.fixCalls)
        assertEquals(1, sent.size)
        assertTrue(latestDiagnostic().contains("provider异常=1"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=1"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun anOptionalStopFailureDoesNotResumeSendingOrEscape() {
        val link = bluetooth()
        link.handle(start) { sent += it }
        assertEquals(1, sent.size)
        provider.failAt = "stop"
        assertTrue(link.handle(stop) { sent += it })
        assertNull(request.components)
        assertFalse(provider.started)
        nowMillis = 10_000
        repeat(3) { link.tick { sent += it } }
        assertEquals(1, sent.size)
        assertEquals(1, provider.fixCalls)
        assertTrue(latestDiagnostic().contains("provider异常=1"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=1"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun firstTransportSendFailurePropagatesTheSameExceptionWithoutAFalseSuccess() {
        val expected = IOException("private-transport-message")
        val link = bluetooth()
        try {
            link.handle(start) { throw expected }
            fail("Real iAP2 send failure was swallowed")
        } catch (actual: IOException) {
            assertSame(expected, actual)
        }
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=0 发送异常=1"))
        assertTrue(latestDiagnostic().contains("provider异常=0"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun laterTransportFailureRetainsExactlyTheEarlierSuccessfulSendCount() {
        val link = bluetooth()
        link.handle(start) { sent += it }
        val expected = IOException("private-transport-message")
        nowMillis = 1_000
        try {
            link.tick { throw expected }
            fail("Later real iAP2 send failure was swallowed")
        } catch (actual: IOException) {
            assertSame(expected, actual)
        }
        assertEquals(1, sent.size)
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=1 发送异常=1"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun emptyFixHasTheSameNoTransmissionMeaningAsMissingFix() {
        provider.nmea = ""
        val link = bluetooth()
        link.handle(start) { sent += it }
        nowMillis = 1_000
        link.tick { sent += it }
        assertTrue(provider.started)
        assertTrue(sent.isEmpty())
        assertTrue(latestDiagnostic().contains("无可用fix=2"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=0 发送异常=0"))
    }

    @Test
    fun diagnosticCountsAndProgressNeverExportTheNmeaSentence() {
        provider.nmea = "\$GPRMC,123456.00,A,3012.3456,N,11054.3210,E,0.0,0.0,010126,,*00\r\n"
        val link = bluetooth()
        link.handle(start) { sent += it }
        assertEquals(1, sent.size)
        assertArrayEquals(Iap2LocationMessages.locationInformation(provider.nmea!!).payload, sent.single().payload)
        val exported = progress.joinToString("\n") + "\n" + Iap2LocationDiagnostics.snapshot()
        for(secret in listOf("GPRMC", "123456.00", "3012.3456", "11054.3210", "010126")) {
            assertFalse("Exported NMEA field $secret", exported.contains(secret))
        }
        assertTrue(exported.contains("0xFFFB成功发送=1"))
    }

    @Test
    fun missingProviderDoesNotEmitAFrameOrClaimSuccessfulStart() {
        val link = Iap2LocationReporter(null, { progress += it })
        assertTrue(link.handle(start) { sent += it })
        assertTrue(sent.isEmpty())
        assertTrue(latestDiagnostic().contains("启动=1 启动失败=1"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=0"))
    }

    @Test
    fun userStatusUsesOnlyTheNewestReporterCountersAndRecordsTermination() {
        val link = bluetooth()
        link.handle(start) { sent += it }
        val live = Iap2LocationDiagnostics.latestUsage()!!
        assertEquals(1L, live.requestCount)
        assertEquals(1L, live.successfulSends)
        assertEquals(0L, live.stopRequestCount)
        assertEquals(0L, live.terminalCleanupCount)
        assertTrue(live.lastSentAgeMillis >= 0)
        link.handle(stop) { sent += it }; link.stop()
        val ended = Iap2LocationDiagnostics.latestUsage()!!
        assertEquals(live.reporterId, ended.reporterId)
        assertEquals(1L, ended.stopRequestCount)
        assertEquals(1L, ended.terminalCleanupCount)
        assertEquals(1L, ended.successfulSends)
        Iap2LocationReporter(null, {})
        val next = Iap2LocationDiagnostics.latestUsage()!!
        assertTrue(next.reporterId > ended.reporterId)
        assertEquals(0L, next.requestCount)
        assertEquals(0L, next.successfulSends)
        assertEquals(-1L, next.lastSentAgeMillis)
    }

    @Test
    fun terminalOptionalStopCannotOverrideARealTransportException() {
        provider.failAt = "stop"
        val link = bluetooth()
        val expected = IOException("private-primary-transport")
        try {
            try { link.handle(start) { throw expected } }
            finally { link.stop() }
            fail("Transport exception was swallowed")
        } catch(actual: IOException) { assertSame(expected, actual) }
        assertFalse(provider.started)
        assertTrue(latestDiagnostic().contains("provider异常=1"))
        assertTrue(latestDiagnostic().contains("0xFFFB成功发送=0 发送异常=1"))
        assertPrivateValuesAbsent()
    }

    @Test
    fun terminalStopDiagnosticCallbackFailureAlsoCannotOverrideTransportFailure() {
        provider.failAt = "stop"
        val link = Iap2LocationReporter(provider, {
            if(it.startsWith("iap2 optional location stop failed")) throw IllegalStateException("private-log-callback")
        })
        val expected = IOException("private-primary-transport")
        try {
            try { link.handle(start) { throw expected } }
            finally { link.stop() }
            fail("Transport exception was swallowed")
        } catch(actual: IOException) { assertSame(expected, actual) }
        assertFalse(provider.started)
        assertFalse(Iap2LocationDiagnostics.snapshot().contains("private-log-callback"))
    }

    private fun latestDiagnostic(): String = Iap2LocationDiagnostics.snapshot().lineSequence().last()
    private fun assertPrivateValuesAbsent() {
        val exported = progress.joinToString("\n") + "\n" + Iap2LocationDiagnostics.snapshot()
        assertFalse(exported.contains("private-"))
        assertFalse(exported.contains("GPGGA"))
        assertFalse(exported.contains("4807.0380"))
        assertFalse(exported.contains("01131.0000"))
    }

    private class FakeProvider : Iap2LocationProvider {
        var started = false
        var requested: Set<Int>? = null
        var failAt = ""
        var starts = 0
        var stops = 0
        var fixCalls = 0
        var nmea: String? = "\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,1.0,545.4,M,0.0,M,,*00\r\n"
        override fun onRequested(components: Set<Int>) {
            if(failAt == "request") throw IOException("private-provider-request-message")
            requested = components
        }
        override fun start(): Boolean {
            starts++; started = true
            if(failAt == "start") throw IOException("private-provider-start-message")
            return true
        }
        override fun stop() {
            stops++; started = false
            if(failAt == "stop") throw IOException("private-provider-stop-message")
        }
        override fun latestNmea(): String? {
            fixCalls++
            if(failAt == "fix") throw IOException("private-provider-fix-message")
            return nmea
        }
    }
}
