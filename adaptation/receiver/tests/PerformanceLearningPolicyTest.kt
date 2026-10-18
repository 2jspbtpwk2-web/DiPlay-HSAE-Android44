package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class PerformanceLearningPolicyTest {
    private val yes = PerformanceLearningPolicy.Environment(true, false, false, true, true)
    private fun good(at: Long) = PerformanceLearningPolicy.Window(at, 5_000, 100, 100,
        300_000, 20, 0, 0, 0, 100, cryptoTotalUs = 100_000, cryptoMaxUs = 5_000)
    private fun bad(at: Long) = good(at).copy(queueAgeMaxMs = 230)
    private fun warmed(tier: Int = 0, count: Int = 3): PerformanceLearningPolicy.Session {
        val s = PerformanceLearningPolicy.Session(0, tier, count)
        for (at in listOf(5_000L, 10_000L, 15_000L)) s.observe(good(at), yes)
        return s
    }

    @Test fun portraitProfilesLowerOnlyResolutionAndKeepTheFrameRate() {
        val profiles = PerformanceLearningPolicy.profiles(PerformanceLearningPolicy.Profile(720, 864, 30))
        assertEquals(listOf(PerformanceLearningPolicy.Profile(720, 864, 30),
            PerformanceLearningPolicy.Profile(640, 768, 30), PerformanceLearningPolicy.Profile(560, 672, 30)), profiles)
    }

    @Test fun everyCandidatePreservesOrientationAndAlignedConservativeEdges() {
        for ((w, h) in listOf(720 to 864, 1072 to 608, 720 to 960, 640 to 640)) {
            val candidates = PerformanceLearningPolicy.profiles(PerformanceLearningPolicy.Profile(w, h, 25))
            assertTrue(candidates.size in 1..3)
            candidates.drop(1).forEach {
                assertEquals(0, it.width % 16); assertEquals(0, it.height % 16)
                assertTrue(it.width <= w && it.height <= h)
                assertEquals(w > h, it.width > it.height); assertEquals(w == h, it.width == it.height)
                assertEquals(25, it.fps)
                assertTrue(kotlin.math.abs(it.width.toDouble() / it.height - w.toDouble() / h) / (w.toDouble() / h) < 0.03)
            }
        }
    }

    @Test fun highFrameRatesAndUnboundedDimensionsAreNotAutomaticallyChanged() {
        for (p in listOf(PerformanceLearningPolicy.Profile(720, 864, 60),
            PerformanceLearningPolicy.Profile(1920, 1080, 30), PerformanceLearningPolicy.Profile(-1, 864, 30)))
            assertTrue(PerformanceLearningPolicy.profiles(p).isEmpty())
    }

    @Test fun noCandidateShrinksBelowTheMinimumShortEdge() {
        assertEquals(1, PerformanceLearningPolicy.profiles(PerformanceLearningPolicy.Profile(368, 512, 30)).size)
    }

    @Test fun fourConsecutiveActivityWindowsScheduleExactlyOneLowerTier() {
        val s = warmed()
        for (at in listOf(20_000L, 25_000L, 30_000L)) assertNull(s.observe(bad(at), yes).nextTier)
        assertEquals(1, s.observe(bad(35_000), yes).nextTier)
        for (at in 40_000L..100_000L step 5_000L) assertNull(s.observe(bad(at), yes).nextTier)
        assertEquals(17, s.anomalyWindows)
    }

    @Test fun aSecondSessionCanLowerOnlyItsOwnNextSingleTier() {
        val s = warmed(1)
        var result = s.observe(bad(20_000), yes)
        for (at in listOf(25_000L, 30_000L, 35_000L)) result = s.observe(bad(at), yes)
        assertEquals(2, result.nextTier)
    }

    @Test fun lowestTierHasNoFurtherAutomaticDescent() {
        val s = warmed(2)
        for (at in 20_000L..60_000L step 5_000L) assertNull(s.observe(bad(at), yes).nextTier)
    }

    @Test fun anUnrecognizedOrManualTierNeverGetsAResolutionDecision() {
        val s = warmed(-1)
        for (at in 20_000L..60_000L step 5_000L) assertNull(s.observe(bad(at), yes).nextTier)
    }

    @Test fun receiveRateAloneDoesNotTreatAStaticMapAsBad() {
        val s = warmed()
        for (at in 20_000L..100_000L step 5_000L) {
            val result = s.observe(bad(at).copy(receivedFrames = 2, releasedOutputs = 1, nativeFrames = 2), yes)
            assertEquals(PerformanceLearningPolicy.Reason.STATIC_OR_INSUFFICIENT, result.reason)
            assertFalse(result.anomaly); assertNull(result.nextTier)
        }
    }

    @Test fun noRealTouchMeansNoLearningEvenWhenVideosArriveRapidly() {
        val s = warmed()
        val result = s.observe(bad(20_000), yes.copy(recentTouch = false))
        assertEquals(PerformanceLearningPolicy.Reason.STATIC_OR_INSUFFICIENT, result.reason)
        assertFalse(result.learnable)
    }

    @Test fun tinyRepeatedPayloadsAreInsufficientActivity() {
        val result = warmed().observe(bad(20_000).copy(receivedBytes = 100), yes)
        assertEquals(PerformanceLearningPolicy.Reason.STATIC_OR_INSUFFICIENT, result.reason)
    }

    @Test fun backgroundDiagnosticsVoiceAndPartialForegroundWindowsAreExcluded() {
        for (env in listOf(yes.copy(visible = false), yes.copy(diagnostic = true), yes.copy(voice = true),
            yes.copy(uninterrupted = false))) {
            val result = warmed().observe(bad(20_000), env)
            assertEquals(PerformanceLearningPolicy.Reason.EXCLUDED, result.reason)
            assertFalse(result.learnable); assertFalse(result.anomaly)
        }
    }

    @Test fun excludedWindowBreaksAnOtherwiseConsecutiveBadRun() {
        val s = warmed()
        s.observe(bad(20_000), yes); s.observe(bad(25_000), yes)
        s.observe(bad(30_000), yes.copy(diagnostic = true))
        for (at in listOf(35_000L, 40_000L, 45_000L)) assertNull(s.observe(bad(at), yes).nextTier)
        assertEquals(1, s.observe(bad(50_000), yes).nextTier)
    }

    @Test fun nativeAbsenceProducesEvidenceButCannotBeHiddenByLoweringQuality() {
        val s = warmed()
        for (at in 20_000L..60_000L step 5_000L) {
            val result = s.observe(bad(at).copy(nativeFrames = 0, fallbackFrames = 100), yes)
            assertEquals(PerformanceLearningPolicy.Reason.NATIVE_NOT_VERIFIED, result.reason)
            assertTrue(result.anomaly); assertFalse(result.learnable); assertNull(result.nextTier)
        }
    }

    @Test fun mostFramesMustActuallyUseNativeNotJustLoadItsLibrary() {
        assertEquals(PerformanceLearningPolicy.Reason.NATIVE_NOT_VERIFIED,
            warmed().observe(bad(20_000).copy(nativeFrames = 89), yes).reason)
        assertTrue(warmed().observe(bad(20_000).copy(nativeFrames = 90), yes).learnable)
    }

    @Test fun authenticationFailuresNeverChooseAResolutionWorkaround() {
        val s = warmed()
        for (at in 20_000L..60_000L step 5_000L) {
            val r = s.observe(bad(at).copy(authRejected = 1), yes)
            assertEquals(PerformanceLearningPolicy.Reason.AUTH_FAILURE, r.reason)
            assertTrue(r.anomaly); assertFalse(r.learnable); assertNull(r.nextTier)
        }
    }

    @Test fun nativeBackendAndDecoderFailuresRequireInvestigationNotQualityLearning() {
        assertEquals(PerformanceLearningPolicy.Reason.BACKEND_FAILURE,
            warmed().observe(bad(20_000).copy(backendFailures = 1), yes).reason)
        assertEquals(PerformanceLearningPolicy.Reason.DECODER_FAILURE,
            warmed().observe(bad(20_000).copy(decoderErrors = 1), yes).reason)
    }

    @Test fun aMissingSurfaceIsNotAFrameRateFailure() {
        for (w in listOf(bad(20_000).copy(surfaceValid = false), bad(20_000).copy(decoderActive = false)))
            assertEquals(PerformanceLearningPolicy.Reason.NO_SURFACE, warmed().observe(w, yes).reason)
    }

    @Test fun nativeCryptoCanBeAttributedOnlyWhenBothTotalAndPeakShowLoad() {
        assertEquals(PerformanceLearningPolicy.Reason.NATIVE_CRYPTO_BUSY,
            warmed().observe(good(20_000).copy(cryptoTotalUs = 4_000_000, cryptoMaxUs = 200_000), yes).reason)
        assertEquals(PerformanceLearningPolicy.Reason.INCONCLUSIVE,
            warmed().observe(good(20_000).copy(cryptoTotalUs = 4_000_000, cryptoMaxUs = 5_000), yes).reason)
    }

    @Test fun outputCountWithoutBacklogDoesNotClaimTrueScreenFramesOrAStall() {
        val r = warmed().observe(good(20_000).copy(releasedOutputs = 5), yes)
        assertEquals(PerformanceLearningPolicy.Reason.INCONCLUSIVE, r.reason)
        assertFalse(r.anomaly); assertNull(r.nextTier)
    }

    @Test fun twelveHealthyActivityWindowsMarkLocalStabilityWithoutRaisingQuality() {
        val s = warmed(1)
        for (at in 20_000L..70_000L step 5_000L) assertNull(s.observe(good(at), yes).stableTier)
        val result = s.observe(good(75_000), yes)
        assertEquals(1, result.stableTier); assertNull(result.nextTier)
        assertEquals(12, s.healthyWindows)
    }

    @Test fun healthyWindowClearsBadHysteresis() {
        val s = warmed()
        s.observe(bad(20_000), yes); s.observe(bad(25_000), yes); s.observe(good(30_000), yes)
        for (at in listOf(35_000L, 40_000L, 45_000L)) assertNull(s.observe(bad(at), yes).nextTier)
    }

    @Test fun badWindowClearsGoodHysteresis() {
        val s = warmed()
        for (at in 20_000L..65_000L step 5_000L) s.observe(good(at), yes)
        s.observe(bad(70_000), yes)
        assertNull(s.observe(good(75_000), yes).stableTier)
    }

    @Test fun warmUpStartsAtVideoNotAtAnEarlyUsbAttempt() {
        val s = PerformanceLearningPolicy.Session(0, 0, 3)
        assertEquals(PerformanceLearningPolicy.Reason.WARM_UP, s.observe(bad(60_000), yes).reason)
        assertEquals(PerformanceLearningPolicy.Reason.WARM_UP, s.observe(bad(65_000), yes).reason)
        assertEquals(PerformanceLearningPolicy.Reason.WARM_UP, s.observe(bad(70_000), yes).reason)
        assertTrue(s.observe(bad(75_000), yes).learnable)
    }

    @Test fun duplicateOrReversedWindowsNeverAccumulateASecondDecision() {
        val s = warmed()
        s.observe(bad(20_000), yes)
        assertEquals(PerformanceLearningPolicy.Reason.INVALID, s.observe(bad(20_000), yes).reason)
        assertEquals(PerformanceLearningPolicy.Reason.INVALID, s.observe(bad(19_000), yes).reason)
        for (at in listOf(25_000L, 30_000L, 35_000L)) assertNull(s.observe(bad(at), yes).nextTier)
    }

    @Test fun aLongSamplingGapBreaksTheBadStreak() {
        val s = warmed()
        s.observe(bad(20_000), yes); s.observe(bad(25_000), yes); s.observe(bad(30_000), yes)
        assertNull(s.observe(bad(50_000), yes).nextTier)
    }

    @Test fun invalidCountersAndOverflowTextAreRejected() {
        assertFalse(good(20_000).copy(receivedFrames = -1).valid())
        assertFalse(good(20_000).copy(durationMs = 100_000).valid())
        assertFalse(good(20_000).copy(cryptoTotalUs = Long.MAX_VALUE).valid())
        assertFalse(good(20_000).copy(cryptoMaxUs = 200_000).valid())
        assertNull(PerformanceLearningPolicy.Window.decode("9223372036854775808,".repeat(17)))
    }

    @Test fun windowStorageRoundTripsOnlyTypedMetadata() {
        val w = bad(20_000).copy(cryptoTotalUs = 1_234_567, cryptoMaxUs = 100_000)
        assertEquals(w, PerformanceLearningPolicy.Window.decode(w.encode()))
        assertNull(PerformanceLearningPolicy.Window.decode(w.encode() + ",secret"))
        assertNull(PerformanceLearningPolicy.Window.decode(w.encode().replace(",1,1", ",2,1")))
    }

    private fun pipeline() = "VideoPipeline windowMs=5000 messages=100 frames=100 frameBytes=300000 " +
        "configs=0 emptyMessages=0 emptyFrames=0 otherMessages=0 fpsMilli=20000 readMaxMs=100 " +
        "frameGapMaxMs=200 lastFrameAgeMs=5 cryptoTotalUs=4000000 cryptoMaxUs=200000 " +
        "annexBTotalUs=2000 annexBMaxUs=50 handoffTotalUs=3000 handoffMaxUs=80 ended=false"

    @Test fun pipelineParserAcceptsOnlyTheExactWhitelistedStructure() {
        assertEquals(PerformanceLearningPolicy.Pipeline(5000, 100, 300000, 4_000_000, 200_000),
            PerformanceLearningPolicy.parsePipeline(pipeline()))
        assertNotNull(PerformanceLearningPolicy.parsePipeline(pipeline().replace("lastFrameAgeMs=5", "lastFrameAgeMs=none")))
        for (line in listOf("prefix " + pipeline(), pipeline() + " raw=secret", pipeline().replace("frames=100", "frames=-1"),
            pipeline().replace("ended=false", "ended=true"), pipeline().replace("windowMs=5000", "windowMs=5000000"),
            pipeline().replace("cryptoTotalUs=4000000", "cryptoTotalUs=999999999999")))
            assertNull(PerformanceLearningPolicy.parsePipeline(line))
    }
}
