package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class VideoPipelineStatsTest {
    @Test fun separatesRealFramesFromEmptyMessagesAndConfiguration() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats(output::add) { clock }
        stats.reading(); clock = 1_000_000L
        stats.received(VideoPipelineStats.Kind.OTHER, 0)
        stats.reading(); clock = 2_000_000L
        stats.received(VideoPipelineStats.Kind.CONFIG, 40)
        stats.reading(); clock = 4_000_000L
        stats.received(VideoPipelineStats.Kind.FRAME, 100)
        stats.frame(84, 2_000L, 3_000L, 4_000L)
        assertTrue(output.isEmpty())
        clock = 5_000_000_000L; stats.flush()
        val line = output.single()
        assertTrue(line.contains("windowMs=5000 messages=3 frames=1 frameBytes=84"))
        assertTrue(line.contains("configs=1 emptyMessages=1 emptyFrames=0 otherMessages=1"))
        assertTrue(line.contains("fpsMilli=200 readMaxMs=2 frameGapMaxMs=0 lastFrameAgeMs=4996"))
        assertTrue(line.contains("cryptoTotalUs=2 cryptoMaxUs=2 annexBTotalUs=3 annexBMaxUs=3 handoffTotalUs=4 handoffMaxUs=4"))
    }

    @Test fun emptyFrameDoesNotInflateFrameRateOrFrameAge() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats(output::add) { clock }
        stats.reading(); clock = 1_000_000_000L
        stats.received(VideoPipelineStats.Kind.FRAME, 0)
        stats.frame(0, 1000L, 2000L, 3000L)
        clock = 5_000_000_000L; stats.flush()
        assertTrue(output.single().contains("messages=1 frames=0 frameBytes=0 configs=0 emptyMessages=1 emptyFrames=1 otherMessages=0"))
        assertTrue(output.single().contains("fpsMilli=0 readMaxMs=1000 frameGapMaxMs=0 lastFrameAgeMs=none"))
    }

    @Test fun resetsWindowCountersButRetainsActualFrameGapAcrossEmptyMessages() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats(output::add) { clock }
        stats.reading(); clock = 1_000_000_000L
        stats.received(VideoPipelineStats.Kind.FRAME, 100); stats.frame(84, 1000L, 2000L, 3000L)
        clock = 5_000_000_000L; stats.flush()
        stats.reading(); clock = 6_000_000_000L
        stats.received(VideoPipelineStats.Kind.OTHER, 0)
        stats.reading(); clock = 7_000_000_000L
        stats.received(VideoPipelineStats.Kind.FRAME, 200); stats.frame(184, 4000L, 5000L, 6000L)
        clock = 10_000_000_000L; stats.flush()
        assertEquals(2, output.size)
        assertTrue(output.last().contains("messages=2 frames=1 frameBytes=184"))
        assertTrue(output.last().contains("frameGapMaxMs=6000 lastFrameAgeMs=3000"))
        assertTrue(output.last().contains("cryptoTotalUs=4 cryptoMaxUs=4 annexBTotalUs=5 annexBMaxUs=5 handoffTotalUs=6 handoffMaxUs=6"))
    }

    @Test fun aggregatesMaximaAndTotalsWithoutPerFrameLogging() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats(output::add) { clock }
        repeat(30) {
            stats.reading(); clock += 10_000_000L
            stats.received(VideoPipelineStats.Kind.FRAME, 120)
            stats.frame(104, 2000L, 3000L, 4000L)
            stats.flush()
        }
        assertTrue(output.isEmpty())
        stats.flush(ended = true)
        assertTrue(output.single().contains("windowMs=300 messages=30 frames=30 frameBytes=3120"))
        assertTrue(output.single().contains("fpsMilli=100000"))
        assertTrue(output.single().contains("cryptoTotalUs=60 cryptoMaxUs=2 annexBTotalUs=90 annexBMaxUs=3 handoffTotalUs=120 handoffMaxUs=4 ended=true"))
    }

    @Test fun finalFlushHandlesEmptyWindowAndReportingFailure() {
        var clock = 0L
        var attempts = 0
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats({ if (++attempts == 1) error("report unavailable") else output.add(it) }) { clock }
        stats.received(VideoPipelineStats.Kind.FRAME, 100); stats.frame(84, 1000L, 2000L, 3000L)
        clock = 5_000_000_000L; stats.flush()
        clock = 10_000_000_000L; stats.flush(ended = true)
        assertEquals(2, attempts)
        assertTrue(output.single().contains("windowMs=5000 messages=0 frames=0 frameBytes=0"))
        assertTrue(output.single().contains("cryptoTotalUs=0 cryptoMaxUs=0"))
    }

    @Test fun diagnosticNeverRetainsNegativeStageDurations() {
        val output = mutableListOf<String>()
        val stats = VideoPipelineStats(output::add) { 0L }
        stats.received(VideoPipelineStats.Kind.FRAME, 10); stats.frame(0, -1L, -2L, -3L)
        stats.flush(ended = true)
        assertTrue(output.single().contains("windowMs=1"))
        assertTrue(output.single().contains("cryptoTotalUs=0 cryptoMaxUs=0 annexBTotalUs=0 annexBMaxUs=0 handoffTotalUs=0 handoffMaxUs=0 ended=true"))
    }
}
