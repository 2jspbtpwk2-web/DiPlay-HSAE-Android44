package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class PerformanceEvidenceTest {
    private fun w(at: Long) = PerformanceLearningPolicy.Window(at, 5_000, 100, 80, 300_000, 200, 0, 0, 0, 100)
    private fun good() = PerformanceLearningPolicy.Result(PerformanceLearningPolicy.Reason.HEALTHY, true)
    private fun bad() = PerformanceLearningPolicy.Result(PerformanceLearningPolicy.Reason.DECODE_BACKLOG, true, true)

    @Test fun triggerContainsOnlyFourPriorAndThreeFollowingWindows() {
        val e = PerformanceEvidence()
        for (at in 5_000L..30_000L step 5_000L) e.add(w(at), good())
        e.add(w(35_000), bad())
        for (at in 40_000L..70_000L step 5_000L) e.add(w(at), good())
        val text = e.snapshot()
        assertTrue(text.contains("windows=8")); assertFalse(text.contains("monoMs=5000 "))
        assertTrue(text.contains("monoMs=15000 ")); assertTrue(text.contains("monoMs=50000 "))
        assertFalse(text.contains("monoMs=55000 "))
    }

    @Test fun sustainedFailureDoesNotContinuouslyWriteNewEpisodes() {
        val e = PerformanceEvidence()
        for (at in 5_000L..100_000L step 5_000L) e.add(w(at), bad())
        assertTrue(e.snapshot().contains("episodes=1"))
        assertEquals(4L, e.revision)
    }

    @Test fun onlyThreeEpisodesSurviveAndOlderEpisodesAreCounted() {
        val e = PerformanceEvidence()
        for (at in listOf(5_000L, 130_000L, 255_000L, 380_000L)) {
            e.add(w(at), bad())
            e.add(w(at + 5_000), good()); e.add(w(at + 10_000), good()); e.add(w(at + 15_000), good())
        }
        assertTrue(e.snapshot().contains("episodes=3 dropped=1"))
        // A previous window can legitimately be copied into another episode's four pre-windows.
        // Timestamps expose the deliberate sampling gaps; pre-windows do not promise 20 seconds.
        val triggers = e.encode().removePrefix("PLE1\n").split("\n--\n").map { episode ->
            episode.lines().map { PerformanceEvidence.Entry.decode(it)!! }
                .last { it.reason == PerformanceLearningPolicy.Reason.DECODE_BACKLOG }.window.atMs
        }
        assertEquals(listOf(130_000L, 255_000L, 380_000L), triggers)
        assertTrue(e.encode().length < 32_768)
    }

    @Test fun sessionBoundaryDoesNotAttachPreviousSessionPreOrPostContext() {
        val e = PerformanceEvidence()
        e.add(w(5_000), good()); e.add(w(10_000), bad())
        e.sessionBoundary()
        e.add(w(15_000), bad()); e.add(w(20_000), good())
        assertTrue(e.snapshot().contains("episodes=2"))
        val pieces = e.encode().split("\n--\n")
        assertFalse(pieces[1].contains(":5000,")); assertFalse(pieces[0].contains(":20000,"))
    }

    @Test fun persistedJournalRestoresAsTypedMetadataAndNamesTheMeasurementLimit() {
        val e = PerformanceEvidence(); e.add(w(5_000), bad()); e.add(w(10_000), good())
        val restored = PerformanceEvidence(); restored.restore(e.encode())
        assertEquals(e.encode(), restored.encode())
        assertTrue(restored.snapshot().contains("outputReleased仅输出缓冲释放计数"))
        assertTrue(restored.snapshot().contains("不是屏幕实测FPS或端到端触摸延迟"))
    }

    @Test fun arbitraryPreferenceTextIsNeverReturnedAsEvidence() {
        for (text in listOf("private-key-secret", "PLE1\nSECRET:123", "PLE1\n" + "x".repeat(40_000),
            "PLE1\nDECODE_BACKLOG:1,2,3", "PLE1\n" + List(4) { "HEALTHY:0:none:" + w(5000).encode() }.joinToString("\n--\n"))) {
            val e = PerformanceEvidence(); e.restore(text)
            assertTrue(e.snapshot().contains("episodes=0")); assertFalse(e.snapshot().contains("secret"))
        }
    }

    @Test fun emptyAndInvalidSamplesCannotCreateAnEpisode() {
        val e = PerformanceEvidence()
        e.add(w(5_000).copy(receivedFrames = -1), bad())
        e.add(w(10_000), PerformanceLearningPolicy.Result(PerformanceLearningPolicy.Reason.INVALID, anomaly = true))
        assertTrue(e.snapshot().contains("episodes=0"))
    }

    @Test fun resetDiscardsJournalAndUnfinishedPostCapture() {
        val e = PerformanceEvidence(); e.add(w(5_000), bad())
        e.restore("PLE1\n"); e.add(w(10_000), good())
        assertEquals("PLE1\n", e.encode())
    }

    @Test fun connectionFailuresCanBeRetainedWithoutVideoWindows() {
        val e = PerformanceFailureEvidence()
        assertTrue(e.add(4, 1000, "CONNECTION_FAILURE"))
        assertTrue(e.snapshot().contains("generation=4 monoMs=1000 event=CONNECTION_FAILURE"))
        assertFalse(e.snapshot().contains("private"))
    }

    @Test fun videoEvidenceKeepsItsActualSessionAndFrozenProfileAcrossRestore() {
        val e = PerformanceEvidence()
        e.add(w(5000), bad(), 8, PerformanceLearningPolicy.Profile(640, 768, 30))
        val restored = PerformanceEvidence(); restored.restore(e.encode())
        assertTrue(restored.snapshot().contains("generation=8 profile=640x768@30"))
        assertFalse(restored.snapshot().contains("profile=720x864"))
    }

    @Test fun connectionEventsRejectFreeTextAndCoalesceRepeatedCallbacks() {
        val e = PerformanceFailureEvidence()
        assertFalse(e.add(1, 1000, "TLS_FAILURE secret=key"))
        assertFalse(e.add(-1, 1000, "TLS_FAILURE"))
        assertTrue(e.add(1, 1000, "TLS_FAILURE"))
        assertFalse(e.add(1, 2000, "TLS_FAILURE"))
        assertTrue(e.add(1, 12000, "TLS_FAILURE"))
        assertTrue(e.snapshot().contains("count=2 coalesced=1"))
    }

    @Test fun connectionEventStorageHasATwentyFourEntryLimitAndStrictRoundTrip() {
        val e = PerformanceFailureEvidence()
        for (n in 1L..40L) assertTrue(e.add(n, n * 10_000, "SESSION_ENDED"))
        assertTrue(e.snapshot().contains("count=24"))
        assertFalse(e.snapshot().contains("generation=1 "))
        val restored = PerformanceFailureEvidence(); restored.restore(e.encode())
        assertEquals(e.encode(), restored.encode())
        assertTrue(restored.snapshot().contains("不自动判定故障"))
        restored.restore("PLF1\n1,2000,PRIVATE_SECRET")
        assertEquals("PLF1\n", restored.encode())
    }
}
