package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpClockDiagnosticsTest {
    @Test fun unusedClockReportsZeroExchangeAndDoesNotClaimSynchronization() {
        val messages = ArrayList<String>()
        val clock = NtpClock { messages.add(it) }
        clock.close()
        clock.close()
        assertEquals(listOf("airplay diag timing summary txRequests=0 txResponses=0 rxRequests=0 rxResponses=0 validSamples=0 synced=false sendErrors=0 receiveErrors=0"), messages)
    }

    @Test fun diagnosticCallbackFailureDoesNotBreakClockClosure() {
        var called = false
        val clock = NtpClock {
            called = true
            throw IllegalStateException("test callback failure")
        }
        clock.close()
        assertTrue(called)
    }
}
