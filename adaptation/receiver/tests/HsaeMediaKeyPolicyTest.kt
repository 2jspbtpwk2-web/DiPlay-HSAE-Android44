package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class HsaeMediaKeyPolicyTest {
    private val session=Any()
    private fun event(kind:HsaeMediaKeyParser.Kind,time:Long)=HsaeMediaKeyParser.Event(kind,time,"${kind.name}:$time")
    private fun policy()=HsaeMediaKeyPolicy().apply { reset(session,1000L) }
    private fun send(p:HsaeMediaKeyPolicy,kind:HsaeMediaKeyParser.Kind,time:Long,mono:Long=10000L,
        enabled:Boolean=true,active:Boolean=true,diag:Boolean=false,value:Any?=session)=
        p.accept(event(kind,time),value,time,mono,enabled,active,diag)
    private fun short(p:HsaeMediaKeyPolicy,previous:Boolean=false,base:Long=1100,mono:Long=10000):Int? {
        send(p,if(previous) HsaeMediaKeyParser.Kind.PREVIOUS_DOWN else HsaeMediaKeyParser.Kind.NEXT_DOWN,base,mono)
        return send(p,if(previous) HsaeMediaKeyParser.Kind.PREVIOUS_UP else HsaeMediaKeyParser.Kind.NEXT_UP,base+200,mono)
    }
    @Test fun nextShortPressProducesOneNextHidIndex() { assertEquals(4,short(policy())) }
    @Test fun previousShortPressProducesOnePreviousHidIndex() { assertEquals(5,short(policy(),true)) }
    @Test fun orphanReleaseIsIgnored() { assertNull(send(policy(),HsaeMediaKeyParser.Kind.NEXT_UP,1300)) }
    @Test fun repeatedSnapshotDoesNotRepeatPair() { val p=policy();assertEquals(4,short(p));assertNull(short(p)) }
    @Test fun repeatedReleaseIsIgnored() { val p=policy();short(p);assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300)) }
    @Test fun oldOrphanDownDoesNotPoisonTheNextPhysicalPress() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100)
        assertEquals(4,short(p,base=3000,mono=12000))
    }
    @Test fun longPressCodeSuppressesRelease() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100)
        send(p,HsaeMediaKeyParser.Kind.NEXT_LONG,1200);assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300))
    }
    @Test fun observedLongPressFlagSuppressesRelease() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.PREVIOUS_DOWN,1100)
        send(p,HsaeMediaKeyParser.Kind.LONG_PRESS,1200);assertNull(send(p,HsaeMediaKeyParser.Kind.PREVIOUS_UP,1300))
    }
    @Test fun repeatedDownMarksHoldAndDoesNotTurnIntoShortPress() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100)
        send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1200);assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300))
    }
    @Test fun tooLongPressIsIgnoredWithoutExplicitLongCode() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100)
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,2400))
    }
    @Test fun disabledModeNeverForwards() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100,enabled=false)
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300,enabled=false))
    }
    @Test fun nonMediaSiriOrDisconnectedStateNeverForwards() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100,active=false)
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300,active=false))
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1400,value=null))
    }
    @Test fun diagnosticCaptureDoesNotIssueCommandsOrKeepPartialPress() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100,diag=true)
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300,diag=false))
    }
    @Test fun oldSessionPressCannotCompleteInNewSession() {
        val p=policy();send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,1100)
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,1300,value=Any()))
    }
    @Test fun startupHistoryIsNeverReplayed() {
        val p=policy();assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_DOWN,900))
        assertNull(send(p,HsaeMediaKeyParser.Kind.NEXT_UP,950))
    }
    @Test fun staleAndFutureEventsAreIgnored() {
        val p=policy()
        assertNull(p.accept(event(HsaeMediaKeyParser.Kind.NEXT_DOWN,1100),session,4000,10000,true,true,false))
        assertNull(p.accept(event(HsaeMediaKeyParser.Kind.NEXT_UP,5000),session,4000,10000,true,true,false))
    }
    @Test fun nextPhysicalPressAfterDebounceIsAllowed() {
        val p=policy();assertEquals(4,short(p));assertEquals(4,short(p,base=1700,mono=10600))
    }
    @Test fun rapidDuplicateSourceIsSuppressedAcrossDirections() {
        val p=policy();assertEquals(4,short(p));assertNull(short(p,true,base=1400,mono=10200))
    }
    @Test fun parserAcceptsExactObservedFirmwarePairsAndRejectsUnknownCodePurpose() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val now=f.parse("2026-10-17 11:05:58.000")!!.time
        val first=HsaeMediaKeyParser.parse("10-17 11:05:57.570   398   458 D HmiManager: seekupKeyEvent: 0",now)
        val last=HsaeMediaKeyParser.parse("10-17 11:05:57.780   398   458 D HmiManager: seekupKeyEvent: 2",now)
        assertEquals(HsaeMediaKeyParser.Kind.NEXT_DOWN,first?.kind);assertEquals(HsaeMediaKeyParser.Kind.NEXT_UP,last?.kind)
        assertEquals(210L,last!!.wallMillis-first!!.wallMillis)
        assertNull(HsaeMediaKeyParser.parse("10-17 11:05:57.780 398 458 D HmiManager: seekdownKeyEvent: 2",now))
    }
    @Test fun parserDoesNotTreatTouchVolumeOrWrongTagAsTrackButtons() {
        val now=System.currentTimeMillis()
        assertNull(HsaeMediaKeyParser.parse("10-17 11:05:57.780 398 458 D Other: seekupKeyEvent: 2",now))
        assertNull(HsaeMediaKeyParser.parse("10-17 11:05:57.780 398 458 D HmiManager: injectSWCInputEvent adjustVol offset1",now))
        assertNull(HsaeMediaKeyParser.parse("[1792206375.165886] /dev/input/event1: EV_KEY BTN_TOUCH DOWN",now))
    }
    @Test fun malformedDatesAndExcessiveLinesAreRejected() {
        assertNull(HsaeMediaKeyParser.parse("99-99 99:99:99.999 398 458 D HmiManager: seekupKeyEvent: 2",0))
        assertNull(HsaeMediaKeyParser.parse("x".repeat(513),0))
    }
    @Test fun twoPhysicalPressesDeliveredInOneSnapshotBothSurviveDebounce() {
        val p=policy()
        fun batched(kind:HsaeMediaKeyParser.Kind,time:Long)=p.accept(event(kind,time),session,2800,10000,true,true,false)
        assertNull(batched(HsaeMediaKeyParser.Kind.PREVIOUS_DOWN,1100))
        assertEquals(5,batched(HsaeMediaKeyParser.Kind.PREVIOUS_UP,1280))
        assertNull(batched(HsaeMediaKeyParser.Kind.PREVIOUS_DOWN,1940))
        assertEquals(5,batched(HsaeMediaKeyParser.Kind.PREVIOUS_UP,2090))
        assertNull(batched(HsaeMediaKeyParser.Kind.PREVIOUS_UP,2090))
    }
    @Test fun exactOctober18VehicleLinesProduceOneCommandPerShortPress() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val now=f.parse("2026-10-18 10:37:49.000")!!.time
        val p=HsaeMediaKeyPolicy().apply { reset(session,now-1800) }
        val lines=listOf(
            "10-18 10:37:47.870   404   454 D HmiManager: seekdownKeyEvent: 3",
            "10-18 10:37:48.050   404   454 D HmiManager: seekdownKeyEvent: 5",
            "10-18 10:37:48.710   404   454 D HmiManager: seekdownKeyEvent: 3",
            "10-18 10:37:48.860   404   454 D HmiManager: seekdownKeyEvent: 5")
        val parsed=lines.map { HsaeMediaKeyParser.parse(it,now)!! }
        assertEquals(listOf(5,5),parsed.mapNotNull { p.accept(it,session,now,10000,true,true,false) })
        assertTrue(parsed.mapNotNull { p.accept(it,session,now,10001,true,true,false) }.isEmpty())
    }
    @Test fun wallclockOrMonotonicReversalDoesNotProduceExtraCommand() {
        val p=policy();assertEquals(4,short(p))
        assertNull(short(p,base=1700,mono=9900))
        assertNull(short(p,base=1101,mono=12000))
    }
    @Test fun fixedTwoSecondPrefilterKeepsFreshKeysAfterThousandsOfOldLines() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val now=f.parse("2026-10-18 10:37:49.000")!!.time
        val window=HsaeKeyFreshWindow(now)
        val old="10-18 10:30:00.000   404   454 D HmiManager: seekupKeyEvent: 0"
        val fresh="10-18 10:37:48.860   404   454 D HmiManager: seekdownKeyEvent: 5"
        val kept=(List(5000) { old }+fresh).filter { window.includes(it) }
        assertEquals(listOf(fresh),kept)
        assertNotNull(HsaeMediaKeyParser.parse(kept.single(),now))
    }
    @Test fun fixedWindowCrossesMidnightAndRetainsStrictBounds() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val window=HsaeKeyFreshWindow(f.parse("2026-10-19 00:00:00.500")!!.time)
        assertTrue(window.includes("10-18 23:59:58.500 KEY"))
        assertTrue(window.includes("10-19 00:00:00.500 KEY"))
        assertFalse(window.includes("10-18 23:59:58.499 KEY"))
        assertFalse(window.includes("10-19 00:00:00.501 KEY"))
    }
    @Test fun fixedWindowHandlesYearBoundaryWithoutIncludingOtherMonths() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val window=HsaeKeyFreshWindow(f.parse("2027-01-01 00:00:00.500")!!.time)
        assertTrue(window.includes("12-31 23:59:59.900 KEY"))
        assertTrue(window.includes("01-01 00:00:00.100 KEY"))
        assertFalse(window.includes("06-01 00:00:00.100 KEY"))
        assertFalse(window.includes("01-01 00:00:00.600 KEY"))
    }
    @Test fun prefilterNeverReplacesFinalFreshnessOrStrictFormatValidation() {
        val f=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US)
        val now=f.parse("2026-10-18 10:37:49.000")!!.time
        val line="10-18 10:37:47.100   404   454 D HmiManager: seekupKeyEvent: 0"
        assertTrue(HsaeKeyFreshWindow(now).includes(line))
        val parsed=HsaeMediaKeyParser.parse(line,now)!!
        val p=HsaeMediaKeyPolicy().apply { reset(session,now-3000) }
        assertNull(p.accept(parsed,session,now,10000,true,true,false))
        assertFalse(HsaeKeyFreshWindow(now).includes("short"))
        assertNull(HsaeMediaKeyParser.parse("10-18 10:37:48.100 NOT A KEY",now))
    }
}
