package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class LatestDecodedOutputBatchTest {
    private val render = LatestDecodedOutputBatch.ReleaseKind.RENDER
    private val superseded = LatestDecodedOutputBatch.ReleaseKind.SUPERSEDED
    private val recovery = LatestDecodedOutputBatch.ReleaseKind.RECOVERY
    private val metadata = LatestDecodedOutputBatch.ReleaseKind.METADATA
    private val cancelled = LatestDecodedOutputBatch.ReleaseKind.CANCELLED

    @Test fun emptyPassNeverReleasesAnIndex() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.finish(true); batch.finish(false); batch.abort()
        assertTrue(releases.isEmpty())
    }

    @Test fun aSinglePictureIsRenderedOnce() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(3); assertTrue(releases.isEmpty())
        batch.finish(true); batch.abort(); batch.finish(true)
        assertEquals(listOf(3 to render), releases)
    }

    @Test fun aBurstPresentsOnlyTheLatestDecodedPicture() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        repeat(8) { batch.keep(it) }
        batch.finish(true)
        assertEquals((0..6).map { it to superseded } + (7 to render), releases)
        assertEquals(8, releases.map { it.first }.distinct().size)
    }

    @Test fun codecMayReuseAnIndexAfterItWasSuperseded() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(0); batch.keep(1); batch.keep(0); batch.finish(true)
        assertEquals(listOf(0 to superseded, 1 to superseded, 0 to render), releases)
    }

    @Test fun batchCanBeReusedForTheNextDrainWithoutRetainingOwnership() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(2); batch.finish(true)
        batch.keep(2); batch.finish(true)
        assertEquals(listOf(2 to render, 2 to render), releases)
    }

    @Test fun metadataDoesNotReplaceAnEligiblePicture() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(5); batch.discard(6, metadata); batch.finish(true)
        assertEquals(listOf(6 to metadata, 5 to render), releases)
    }

    @Test fun aRecoveryRejectedOutputDoesNotHideAnEligiblePicture() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(5); batch.discard(6, recovery); batch.finish(true)
        assertEquals(listOf(6 to recovery, 5 to render), releases)
    }

    @Test fun aPassContainingOnlyMetadataOrRecoveryNeverPresentsAPicture() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.discard(0, metadata); batch.discard(1, recovery); batch.finish(true); batch.abort()
        assertEquals(listOf(0 to metadata, 1 to recovery), releases)
    }

    @Test fun closingBetweenDequeueAndFinishReturnsThePendingIndexWithoutRendering() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(7); batch.finish(false); batch.abort()
        assertEquals(listOf(7 to cancelled), releases)
    }

    @Test fun abortReturnsThePendingIndexExactlyOnce() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        batch.keep(7); batch.abort(); batch.abort(); batch.finish(true)
        assertEquals(listOf(7 to cancelled), releases)
    }

    @Test fun failedSupersededReleaseStillLetsFinallyReturnTheNewerIndex() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason ->
            releases.add(index to reason)
            if (index == 0) throw IllegalStateException("native release failed")
        }
        batch.keep(0)
        expectFailure { batch.keep(1) }
        batch.abort(); batch.abort()
        assertEquals(listOf(0 to superseded, 1 to cancelled), releases)
    }

    @Test fun failedImmediateReleaseStillLetsFinallyReturnThePreviousPicture() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason ->
            releases.add(index to reason)
            if (index == 1) throw IllegalStateException("native release failed")
        }
        batch.keep(0)
        expectFailure { batch.discard(1, metadata) }
        batch.abort(); batch.abort()
        assertEquals(listOf(1 to metadata, 0 to cancelled), releases)
    }

    @Test fun failedRenderReleaseIsNeverRetriedOnAnAmbiguousNativeIndex() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason ->
            releases.add(index to reason)
            throw IllegalStateException("native release failed")
        }
        batch.keep(0)
        expectFailure { batch.finish(true) }
        batch.abort(); batch.finish(true)
        assertEquals(listOf(0 to render), releases)
    }

    @Test fun failedAbortReleaseIsNeverRetried() {
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason ->
            releases.add(index to reason)
            throw IllegalStateException("native release failed")
        }
        batch.keep(0)
        expectFailure { batch.abort() }
        batch.abort(); batch.finish(true)
        assertEquals(listOf(0 to cancelled), releases)
    }

    @Test fun surfacePictureWithZeroReportedSizeIsRetained() {
        assertTrue(LatestDecodedOutputBatch.isPicture(0, false, false))
        assertTrue(LatestDecodedOutputBatch.isPicture(50, false, false))
        assertFalse(LatestDecodedOutputBatch.isPicture(-1, false, false))
    }

    @Test fun codecConfigurationDoesNotBecomeAPicture() {
        assertFalse(LatestDecodedOutputBatch.isPicture(0, true, false))
        assertFalse(LatestDecodedOutputBatch.isPicture(50, true, false))
        assertFalse(LatestDecodedOutputBatch.isPicture(50, true, true))
    }

    @Test fun eosWithPictureDataIsPresentedWhileEmptyEosIsNot() {
        assertFalse(LatestDecodedOutputBatch.isPicture(0, false, true))
        assertTrue(LatestDecodedOutputBatch.isPicture(50, false, true))
    }

    @Test fun recoveryPtsGateIsPreservedWhileNewEligiblePicturesAreCoalesced() {
        val latency = VideoLatencyPolicy()
        val releases = ArrayList<Pair<Int, LatestDecodedOutputBatch.ReleaseKind>>()
        val batch = LatestDecodedOutputBatch { index, reason -> releases.add(index to reason) }
        latency.queued(100)
        for ((index, pts) in listOf(0 to 99L, 1 to 100L, 2 to 101L)) {
            if (latency.shouldRender(pts)) batch.keep(index) else batch.discard(index, recovery)
        }
        batch.finish(true)
        assertEquals(listOf(0 to recovery, 1 to superseded, 2 to render), releases)
        latency.requireKeyFrame()
        assertFalse(latency.shouldRender(200))
        assertTrue(latency.needsKeyFrame)
    }

    @Test fun diagnosticsDistinguishDecodedOutputCoalescingFromCompressedInputDrops() {
        val diagnostics = MediaDiagnostics { 0 }
        diagnostics.videoDecodedOutputs.addAndGet(8)
        diagnostics.videoSupersededOutputs.addAndGet(7)
        diagnostics.outputVideo()
        assertEquals(0L, diagnostics.videoDropped.get())
        assertEquals(0L, diagnostics.videoQueueCleared.get())
        assertEquals(0L, diagnostics.videoWaitingIdr.get())
        assertTrue(diagnostics.summary().contains("解码输出缓冲=8"))
        assertTrue(diagnostics.summary().contains("解码后合并旧画面=7"))
        assertTrue(diagnostics.summary().contains("显示缓冲释放=1"))
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Expected native release failure") }
        catch (_: IllegalStateException) {}
    }
}
