package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class ConnectionMilestonesTest {
    @Test fun firstTimesSurviveRepetitiveTraffic() {
        val table = ConnectionMilestones()
        table.begin(7, 1000)
        table.record(7, 1200, "USB AirPlay active")
        table.record(7, 1250, "USB first decoded video output released")
        repeat(10000) { table.record(7, 2000L + it, "iap2 rx=0x5001") }
        table.record(7, 50000, "USB AirPlay active")
        assertEquals(200L, table.latest()!!.first["AirPlay RECORD激活"])
        assertEquals(250L, table.latest()!!.first["首个解码输出"])
    }
    @Test fun attemptsAreBoundedAndIsolated() {
        val table = ConnectionMilestones()
        for (id in 1..10) { table.begin(id, id * 1000L); table.record(id, id * 1000L, "USB enumeration") }
        table.record(9, 11000, "USB AirPlay active")
        table.record(10, 9999, "USB AirPlay active")
        assertEquals(8, table.history().size)
        assertEquals(3, table.history().first().attempt)
        assertEquals(10, table.latest()!!.attempt)
        assertFalse(table.latest()!!.first.containsKey("AirPlay RECORD激活"))
        assertEquals(0L, table.latest()!!.first["USB枚举"])
    }
    @Test fun snapshotsCannotMutateInternalHistory() {
        val table = ConnectionMilestones(); assertNull(table.latest())
        table.begin(1, 10); table.record(1, 12, "USB enumeration")
        (table.latest()!!.first as MutableMap).clear()
        assertEquals(2L, table.latest()!!.first["USB枚举"])
        table.begin(2, 20)
        assertTrue(table.latest()!!.first.isEmpty())
        assertEquals(2L, table.history()[0].first["USB枚举"])
    }
    @Test fun everyDiagnosticStageIsCapturedIndependently() {
        val table = ConnectionMilestones(); table.begin(4, 100)
        ConnectionMilestones.keys.reversed().forEachIndexed { index, pair -> table.record(4, 100L + index, pair.first) }
        assertEquals(ConnectionMilestones.keys.size, table.latest()!!.first.size)
    }
}
