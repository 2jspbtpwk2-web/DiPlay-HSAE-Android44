package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class FutureDiagnosticsTest {
    @Test fun noSessionHasBoundedStartupWindow() {
        val state=ConnectionObservation(100)
        assertTrue(state.shouldContinue(95099)); assertFalse(state.shouldContinue(95100))
        assertNull(state.firstActiveMs)
    }
    @Test fun recordKeepsMediaWindowOpen() {
        val state=ConnectionObservation(0)
        state.update(90000,true)
        assertTrue(state.shouldContinue(149999)); assertFalse(state.shouldContinue(150000))
        assertTrue(state.activeNow)
    }
    @Test fun disconnectDoesNotEraseEvidenceOrExtendDeadline() {
        val state=ConnectionObservation(0)
        state.update(1000,true); state.update(2000,false); state.update(3000,false); state.update(4000,true)
        assertEquals(1,state.disconnects); assertEquals(1000L,state.firstActiveMs)
        assertFalse(state.shouldContinue(61000))
    }
    @Test fun existingSessionGetsItsOwnMediaObservation() {
        val state=ConnectionObservation(700)
        state.update(700,true)
        assertTrue(state.shouldContinue(60699)); assertFalse(state.shouldContinue(60700))
    }
    @Test fun socketSnapshotOmitsAddressesAndOtherApps() {
        val table="""sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode
           0: 000000000000000000000000020080FE:1B58 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 55555
           1: DEADBEEFDEADBEEFDEADBEEFDEADBEEF:1234 00000000000000000000000000000000:4321 01 00000001:00000002 00:00000000 00000000 9999 0 66666
        """.trimIndent()
        val summary=ConnectionRuntimeProbe.sockets(table,10123)
        assertTrue(summary.contains("localPort=7000")); assertTrue(summary.contains("stateHex=0A"))
        assertFalse(summary.contains("DEADBEEF")); assertFalse(summary.contains("020080FE"))
        assertFalse(summary.contains("55555")); assertFalse(summary.contains("localPort=4660"))
    }
    @Test fun malformedSocketTablesStayInconclusive() {
        assertTrue(ConnectionRuntimeProbe.sockets("permission denied",1000).contains("无匹配"))
        assertTrue(ConnectionRuntimeProbe.sockets("head\n0: bad bad ZZ q t r 1000 0 5",1000).contains("无匹配"))
    }
    @Test fun socketExportIsBounded() {
        val row="0: 00000000:1B58 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1"
        val result=ConnectionRuntimeProbe.sockets("head\n"+List(500){row}.joinToString("\n"),1000)
        assertEquals(48,result.lineSequence().count { it.startsWith("localPort=") })
        assertTrue(result.contains("上限"))
    }
    @Test fun oldProtocolPayloadIsExcludedFromLogcatExport() {
        val report=ProtocolLogFilter.filter("I/xcertplay-airplay: airplay SETUP stream type=110 payload={secret=hidden}\n"+
            "I/xcertplay-airplay: airplay diag setup result kind=screen accepted=true\nI/USB: driver failed")
        assertFalse(report.contains("hidden")); assertTrue(report.contains("airplay diag"))
        assertTrue(report.contains("driver failed")); assertTrue(report.contains("过滤=1"))
    }
    @Test fun oldTouchReportIsExcluded() {
        val report=ProtocolLogFilter.filter("I/xcertplay-airplay: airplay touch report sent contacts=1 first=(10,20,down=true) report=DEADBEEF")
        assertFalse(report.contains("DEADBEEF")); assertFalse(report.contains("10,20"))
    }
}
