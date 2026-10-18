package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class ConnectionStagesTest {
    @Test fun progressOnlyAdvances() {
        var stage = -1
        for ((index, entry) in ConnectionStages.items.withIndex()) {
            stage = ConnectionStages.advance(stage, "prefix ${entry.first} suffix")
            assertEquals(index, stage)
            assertEquals(stage, ConnectionStages.advance(stage, "USB enumeration"))
        }
    }
    @Test fun unrelatedLogsDoNotChangeProgress() {
        assertEquals(-1, ConnectionStages.advance(-1, "video queue=4"))
        assertEquals(13, ConnectionStages.advance(13, "audio packets=500"))
    }
    @Test fun combinedMessageUsesFarthestStage() {
        assertEquals(17, ConnectionStages.advance(-1, "USB AirPlay active; USB first video frame rendered"))
    }
}
