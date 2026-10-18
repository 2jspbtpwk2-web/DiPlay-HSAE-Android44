package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class LatestGpsFixTest {
    @Test fun aRejectedCallbackCannotEraseTheRecentValidPosition() {
        val fixes = LatestGpsFix<String>()
        assertEquals(LatestGpsFix.Result.ACCEPTED, fixes.offer("recent", 1000, true))
        assertEquals(LatestGpsFix.Result.REJECTED, fixes.offer("mock-or-stale", 2000, false))
        assertEquals("recent", fixes.current())
    }
    @Test fun outOfOrderButOtherwiseFreshFixCannotReplaceTheNewestPosition() {
        val fixes = LatestGpsFix<String>()
        fixes.offer("newest", 5000, true)
        assertEquals(LatestGpsFix.Result.OUT_OF_ORDER, fixes.offer("older", 4000, true))
        assertEquals("newest", fixes.current())
    }
    @Test fun aNewerValidFixAdvancesNormallyAfterRejectedAndOutOfOrderCallbacks() {
        val fixes = LatestGpsFix<String>()
        fixes.offer("first", 1000, true)
        fixes.offer("bad", 3000, false)
        fixes.offer("older", 500, true)
        assertEquals(LatestGpsFix.Result.ACCEPTED, fixes.offer("second", 2000, true))
        assertEquals("second", fixes.current())
    }
    @Test fun equalMonotonicTimeAllowsAVendorToRefineTheSameFix() {
        val fixes = LatestGpsFix<String>()
        fixes.offer("first", 1000, true)
        assertEquals(LatestGpsFix.Result.ACCEPTED, fixes.offer("refined", 1000, true))
        assertEquals("refined", fixes.current())
    }
    @Test fun explicitExpiryDisableOrStopClearsAllRetainedState() {
        val fixes = LatestGpsFix<String>()
        fixes.offer("first", 5000, true); fixes.clear()
        assertNull(fixes.current())
        // A new owner can start from another monotonic epoch after an explicit clear.
        assertEquals(LatestGpsFix.Result.ACCEPTED, fixes.offer("new-connection", 1000, true))
        assertEquals("new-connection", fixes.current())
    }
    @Test fun invalidMonotonicTimesNeverCreateOrOverwriteAFix() {
        val fixes = LatestGpsFix<String>()
        for (time in listOf(-1L, 0L, Long.MIN_VALUE)) {
            assertEquals(LatestGpsFix.Result.REJECTED, fixes.offer("invalid", time, true))
            assertNull(fixes.current())
        }
        fixes.offer("valid", 1, true)
        assertEquals(LatestGpsFix.Result.REJECTED, fixes.offer("zero", 0, true))
        assertEquals("valid", fixes.current())
    }
    @Test fun invalidSamplesDoNotExtendTheExistingTenSecondFreshnessLimit() {
        val fixes = LatestGpsFix<Long>()
        fixes.offer(1_000_000_000L, 1_000_000_000L, true)
        fixes.offer(10_000_000_000L, 10_000_000_000L, false)
        val ageMillis = (11_001_000_000L - fixes.current()!!) / 1_000_000L
        assertFalse(GpsDiagnostics.freshGps("gps", 30.0, 110.0, false, ageMillis))
        fixes.clear(); assertNull(fixes.current())
    }
}
