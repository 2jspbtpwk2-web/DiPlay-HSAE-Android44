package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class UiTimingStatsTest {
    @Test fun recordsLateCallbacks() {
        val stats = UiTimingStats()
        stats.resume(1000); stats.arrived(1000)
        stats.schedule(1000, 800); stats.arrived(1860)
        stats.schedule(1860, 800); stats.arrived(3000)
        val s = stats.summary()
        assertTrue(s.contains("samples=3")); assertTrue(s.contains("lateOver50ms=2"))
        assertTrue(s.contains("lateOver200ms=1")); assertTrue(s.contains("maxLateMs=340"))
    }
    @Test fun backgroundTimeIsExcluded() {
        val stats = UiTimingStats()
        stats.resume(100); stats.arrived(100); stats.pause(); stats.arrived(100000)
        stats.resume(200000); stats.arrived(200000)
        assertTrue(stats.summary().contains("samples=2"))
        assertTrue(stats.summary().contains("maxLateMs=0"))
    }
    @Test fun earlyDeliveryCannotProduceNegativeDelay() {
        val stats = UiTimingStats(); stats.resume(1000); stats.arrived(500)
        assertTrue(stats.summary().contains("maxLateMs=0"))
    }
}
