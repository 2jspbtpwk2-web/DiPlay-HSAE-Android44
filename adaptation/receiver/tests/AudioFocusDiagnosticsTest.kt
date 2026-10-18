package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class AudioFocusDiagnosticsTest {
    @Test fun recordsRequestsLossGainAndApiVolumeResults() {
        var now = 100L
        val history = AudioFocusHistory(nowMs = { now })
        val id = history.create(true, true, 80)
        history.tracks(id, "MEDIA", true)
        history.state(id, listOf("MEDIA", "NAVIGATION"), "MEDIA", false, true, 80)
        history.request(id, "MEDIA", 1, 1)
        now += 30
        history.change(id, -3)
        history.volume(id, "MEDIA", 0.16f, 0)
        history.change(id, -2)
        history.change(id, -1)
        history.change(id, 1)
        history.abandon(id, 1)
        val text = history.snapshot()
        assertTrue(text.contains("requests=1 granted=1 requestFailureOrError=0"))
        assertTrue(text.contains("focusChanges=4"))
        assertTrue(text.contains("name=LOSS_TRANSIENT_CAN_DUCK"))
        assertTrue(text.contains("name=LOSS_TRANSIENT"))
        assertTrue(text.contains("name=LOSS"))
        assertTrue(text.contains("name=GAIN"))
        assertTrue(text.contains("navigationDuck=true"))
        assertTrue(text.contains("expectedPermille=160 result=0"))
        assertTrue(text.contains("+30ms"))
    }
    @Test fun failureStatusAndExceptionsRemainDistinguishable() {
        val history = AudioFocusHistory()
        val id = history.create(true, false, 100)
        history.request(id, "PHONE", 2, 0)
        history.request(id, "ASSISTANT", 3, null, SecurityException("private detail"))
        history.abandon(id, null)
        history.volume(id, "PHONE", 1f, -1)
        history.volume(id, "PHONE", 1f, null, IllegalStateException("secret"))
        val text = history.snapshot()
        assertTrue(text.contains("requests=2 granted=0 requestFailureOrError=2"))
        assertTrue(text.contains("abandonFailureOrError=1"))
        assertTrue(text.contains("volumeCalls=2 volumeFailureOrError=2"))
        assertTrue(text.contains("result=unavailable error=SecurityException"))
        assertFalse(text.contains("private detail"))
        assertFalse(text.contains("secret"))
    }
    @Test fun eventAndCoordinatorHistoryAreBounded() {
        val history = AudioFocusHistory(eventLimit = 4, coordinatorLimit = 2)
        val first = history.create(true, false, 100)
        val second = history.create(true, false, 100)
        val third = history.create(true, false, 100)
        repeat(20) { history.change(third, it) }
        history.state(first, listOf("MEDIA"), "MEDIA", false, false, 100)
        history.state(second, emptyList(), null, false, false, 100)
        val text = history.snapshot()
        assertTrue(text.contains("coordinatorsCreated=3 retained=2/2"))
        assertTrue(text.contains("recentEvents=4/4 olderEventsOmitted=19"))
        assertFalse(text.contains("coordinator=$first tracks="))
        assertTrue(text.contains("coordinator=$second tracks=0"))
        assertEquals(4, text.lineSequence().count { it.startsWith("+") })
    }
    @Test fun channelsAreAllowListedAndNoObjectIdentifiersAreRecorded() {
        val history = AudioFocusHistory()
        val id = history.create(true, false, 100)
        history.tracks(id, "private media title", true)
        history.request(id, "contact name", 1, 1)
        history.volume(id, "device address", 0.5f, 0)
        history.state(id, listOf("media", "phone"), "media", false, false, 100)
        val text = history.snapshot()
        assertFalse(text.contains("private media title"))
        assertFalse(text.contains("contact name"))
        assertFalse(text.contains("device address"))
        assertTrue(text.contains("channel=OTHER"))
        assertTrue(text.contains("channels={MEDIA=1,PHONE=1,ASSISTANT=0,NAVIGATION=0}"))
    }
    @Test fun finishedTracksLeaveZeroCurrentStateAndKeepHistory() {
        val history = AudioFocusHistory()
        val id = history.create(true, false, 75)
        history.tracks(id, "ASSISTANT", true)
        history.tracks(id, "ASSISTANT", false)
        history.state(id, emptyList(), null, false, false, 75)
        val text = history.snapshot()
        assertTrue(text.contains("tracksAdded=1 tracksRemoved=1"))
        assertTrue(text.contains("tracks=0 channels={MEDIA=0,PHONE=0,ASSISTANT=0,NAVIGATION=0} focus=NONE"))
        assertTrue(text.contains("track-added channel=ASSISTANT"))
        assertTrue(text.contains("track-removed channel=ASSISTANT"))
    }
    @Test fun concurrentRecordersKeepExactCounters() {
        val history = AudioFocusHistory(eventLimit = 8)
        val id = history.create(true, false, 100)
        val threads = (1..4).map { Thread { repeat(100) { history.change(id, -3); history.volume(id, "MEDIA", 0.2f, 0) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val text = history.snapshot()
        assertTrue(text.contains("focusChanges=400 volumeCalls=400 volumeFailureOrError=0"))
        assertEquals(8, text.lineSequence().count { it.startsWith("+") })
    }
    @Test fun invalidVolumeAndClockCannotProducePrivateOrNegativeTiming() {
        var now = 100L
        val history = AudioFocusHistory(nowMs = { now })
        val id = history.create(true, false, 1000)
        now = 50
        history.volume(id, "MEDIA", Float.NaN, 0)
        history.volume(id, "MEDIA", Float.POSITIVE_INFINITY, 0)
        val text = history.snapshot()
        assertTrue(text.contains("volumePercent=100"))
        assertTrue(text.contains("expectedPermille=-1"))
        assertFalse(text.contains("+-50ms"))
    }
}
