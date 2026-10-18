package local.airuize.receiver

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2LocationDiagnostics
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2LocationReporter
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocationDiagnosticsTest {
    @Test fun failedGpsUnregisterRetainsOwnershipEvenWhenLocalCollectionStopped() {
        val registration = GpsRegistrationState()
        registration.beforeRequest()
        registration.removalFailed(IOException("private-cleanup"))
        assertTrue(registration.needsRemoval())
        assertTrue(registration.snapshot().contains("unregisterApi=FAILED"))
        // A later close is still obliged to attempt removal, independently of running state.
        registration.removalReturned()
        assertFalse(registration.needsRemoval())
        assertTrue(registration.snapshot().contains("unregisterApi=RETURNED"))
        assertFalse(registration.snapshot().contains("private-cleanup"))
    }
    @Test fun ambiguousGpsRegistrationCreatesCleanupObligationBeforeTheVendorCall() {
        val registration = GpsRegistrationState()
        assertFalse(registration.needsRemoval())
        registration.beforeRequest()
        // No successful registration return is required: a vendor can register then throw.
        assertTrue(registration.needsRemoval())
        assertTrue(registration.snapshot().contains("unregisterApi=NOT_CALLED"))
    }
    @Test fun canUnbindRecordsQueuedReturnedFailedAndRejectedWithoutGuessingSuccess() {
        val diagnostics = CanUnbindDiagnostics()
        val success = diagnostics.begin()
        success.beforeBind(); success.bound(true); success.queued()
        assertTrue(success.snapshot().contains("obligation=true unbindApi=PENDING"))
        success.returned()
        assertFalse(success.needsUnbind())
        assertTrue(success.snapshot().contains("unbindApi=RETURNED"))
        val failed = diagnostics.begin()
        failed.beforeBind(); failed.queued(); failed.failed(IOException("private-unbind-message"))
        assertTrue(failed.needsUnbind())
        assertTrue(failed.snapshot().contains("unbindApi=FAILED error=IOException"))
        assertFalse(failed.snapshot().contains("private-unbind-message"))
        val rejected = diagnostics.begin()
        rejected.beforeBind(); rejected.bound(true); rejected.queued(); rejected.queueRejected()
        assertTrue(rejected.needsUnbind())
        assertTrue(rejected.snapshot().contains("unbindApi=NOT_RUN"))
        val notBound = diagnostics.begin()
        notBound.bindingQueueRejected()
        assertFalse(notBound.needsUnbind())
        assertTrue(notBound.snapshot().contains("未执行bind"))
    }
    @Test fun canCleanupIsPerBindingAndLateCallbacksCannotOverwriteNewerOwnership() {
        val diagnostics = CanUnbindDiagnostics()
        val first = diagnostics.begin().also { it.beforeBind(); it.bound(true); it.queued() }
        val second = diagnostics.begin().also { it.beforeBind(); it.bound(true) }
        first.returned()
        assertFalse(first.needsUnbind()); assertTrue(second.needsUnbind())
        assertTrue(second.snapshot().contains("unbindApi=NOT_CALLED"))
        repeat(20) { diagnostics.begin() }
        val text = diagnostics.snapshot()
        assertEquals(8, Regex("binding#").findAll(text).count())
    }
    @Test fun queuedCleanupDoesNotClaimPlatformUnregistrationAlreadyReturned() {
        val state = GpsCleanupState()
        state.queued(true, true)
        val pending = state.snapshot()
        assertTrue(pending.contains("cleanupPending=true"))
        assertTrue(pending.contains("locationUnregisterApi=PENDING"))
        assertTrue(pending.contains("satelliteUnregisterApi=PENDING"))
        state.locationReturned(); state.satelliteReturned(); state.finished()
        val done = state.snapshot()
        assertTrue(done.contains("cleanupPending=false"))
        assertTrue(done.contains("locationUnregisterApi=RETURNED"))
        assertTrue(done.contains("satelliteUnregisterApi=RETURNED"))
    }
    @Test fun cleanupFailureRemainsVisibleAfterLocalListenerReferencesAreReleased() {
        val state = GpsCleanupState()
        state.queued(true, true)
        state.locationFailed(IOException("private-position-message"))
        state.satelliteReturned(); state.finished()
        // A later no-op cleanup must not convert a prior failure into apparent success.
        state.queued(false, false); state.finished()
        val text = state.snapshot()
        assertTrue(text.contains("cleanupPending=false"))
        assertTrue(text.contains("locationUnregisterApi=FAILED"))
        assertTrue(text.contains("satelliteUnregisterApi=RETURNED"))
        assertTrue(text.contains("locationCleanupError=IOException"))
        assertFalse(text.contains("private-position-message"))
    }
    @Test fun failedQueueSubmissionIsExplicitAndNoSubscriptionIsInvented() {
        val state = GpsCleanupState()
        state.queued(false, false); state.queueRejected()
        val text = state.snapshot()
        assertTrue(text.contains("cleanupPending=false"))
        assertTrue(text.contains("locationUnregisterApi=NOT_REQUIRED"))
        assertTrue(text.contains("主线程未接受注销任务"))
        val registered = GpsCleanupState()
        registered.queued(true, false); registered.queueRejected()
        assertTrue(registered.snapshot().contains("locationUnregisterApi=NOT_RUN"))
    }
    @Test fun gpsRejectionReasonsRetainExistingFreshnessLimits() {
        assertNull(GpsDiagnostics.rejection("gps", 30.0, 110.0, false, 0, 1))
        assertNull(GpsDiagnostics.rejection("gps", -90.0, 180.0, false, 10000, 1))
        assertEquals(GpsDiagnostics.Rejection.PROVIDER, GpsDiagnostics.rejection("network", 30.0, 110.0, false, 1, 1))
        assertEquals(GpsDiagnostics.Rejection.COORDINATES, GpsDiagnostics.rejection("gps", Double.NaN, 110.0, false, 1, 1))
        assertEquals(GpsDiagnostics.Rejection.COORDINATES, GpsDiagnostics.rejection("gps", 30.0, Double.POSITIVE_INFINITY, false, 1, 1))
        assertEquals(GpsDiagnostics.Rejection.MOCK, GpsDiagnostics.rejection("gps", 30.0, 110.0, true, 1, 1))
        assertEquals(GpsDiagnostics.Rejection.AGE, GpsDiagnostics.rejection("gps", 30.0, 110.0, false, 10001, 1))
        assertEquals(GpsDiagnostics.Rejection.AGE, GpsDiagnostics.rejection("gps", 30.0, 110.0, false, -1, 1))
        assertEquals(GpsDiagnostics.Rejection.TIMESTAMP, GpsDiagnostics.rejection("gps", 30.0, 110.0, false, 1, 0))
    }
    @Test fun gpsSnapshotHasSeparateCacheCallbackAndEncodingEvidence() {
        var now = 1000L
        val gps = LocationDiagnostics.Gps(1, "USB", { now })
        gps.requested(setOf(0, 1, 4)); gps.started(LocationDiagnostics.StartState.STARTED)
        gps.fix(true, null, "gps", 10, 5f, false, true, true)
        gps.fix(false, null, "gps", 20, 6f, false, false, false)
        gps.fix(false, GpsDiagnostics.Rejection.MOCK, "gps", 30, 7f, true, true, false)
        gps.encoded(true); now = 1500
        val text = gps.snapshot()
        assertTrue(text.contains("缓存检查=1 缓存采用=1 实时回调=2 实时接受=1"))
        assertTrue(text.contains("MOCK=1")); assertTrue(text.contains("编码=1 CAN采用=1"))
        assertTrue(text.contains("最近有效fix距今Ms=500"))
        assertTrue(text.contains("编码不等于iPhone采用"))
        gps.stopped(); gps.closed()
        assertTrue(gps.snapshot().contains("running=false closed=true"))
    }
    @Test fun snapshotRejectsPrivateProviderLinkAndExceptionMessage() {
        val gps = LocationDiagnostics.gps("private-link-123")
        gps.fix(false, GpsDiagnostics.Rejection.PROVIDER, "private-provider-456", 1, Float.NaN, false, false, false)
        gps.failed(LocationDiagnostics.FailureStage.ENCODE, IOException("private-location-NMEA-789"))
        gps.requested((-100..100).toSet())
        val text = gps.snapshot()
        assertFalse(text.contains("private")); assertFalse(text.contains("789"))
        assertTrue(text.contains("link=其他")); assertTrue(text.contains("ENCODE:IOException"))
        assertTrue(text.contains("省略")); assertTrue(text.length < 1800)
    }
    @Test fun registryRetainsOnlyEightIndependentMetadataEntries() {
        repeat(20) { LocationDiagnostics.can("USB", it % 2 == 0).started() }
        val text = LocationDiagnostics.snapshot()
        assertEquals(8, text.lines().size)
        assertTrue(text.lines().all { it.startsWith("CAN实例#") })
    }
    @Test fun canZeroSpeedIsDistinctFromFailureAndStaleHistory() {
        var now = 1000L
        val can = LocationDiagnostics.Can(2, "USB", false, { now })
        can.started(); can.binding(true); can.connection(true)
        can.read(5, true, true, false); now = 1600
        val good = can.snapshot()
        assertTrue(good.contains("有效=1 拒绝=0 有效零速=1"))
        assertTrue(good.contains("最近有效样本距今Ms=600"))
        can.read(1001, false, false, false)
        assertTrue(can.snapshot().contains("读取超时后弃用"))
        assertTrue(can.snapshot().contains("最近有效样本距今Ms=-1"))
        can.failed(IOException("private-route")); can.stopped(true)
        val failed = can.snapshot()
        assertTrue(failed.contains("running=false closed=true"))
        assertTrue(failed.contains("最近结果=IOException")); assertFalse(failed.contains("private"))
    }
    @Test fun satelliteSummaryIsBoundedAndRejectsInvalidSnr() {
        val summary = GpsDiagnostics.satelliteSummary(listOf(20f to true, 40f to false,
            Float.NaN to true, Float.POSITIVE_INFINITY to false, -1f to false, 101f to false), 1500)
        assertTrue(summary.contains("可见=6 使用=2 SNR有效=2 SNR平均=30.0 SNR最大=40"))
        assertTrue(summary.contains("缓存TTFFms=1500"))
        assertTrue(GpsDiagnostics.satelliteSummary(List(1000) { 10f to true }, 0).contains("可见=256 使用=256"))
        assertTrue(GpsDiagnostics.satelliteSummary(emptyList(), -1).contains("SNR平均=未知"))
    }
    private class Provider : Iap2LocationProvider {
        var running = false
        var calls = 0
        var starts = 0
        var stops = 0
        var failAt = ""
        var fix: String? = "private-synthetic-NMEA"
        override fun onRequested(components: Set<Int>) { if(failAt == "request") throw IOException("private-request") }
        override fun start(): Boolean { starts++; if(failAt == "start") throw IOException("private-start"); running = true; return true }
        override fun latestNmea(): String? { calls++; if(failAt == "fix") throw IOException("private-fix"); return fix }
        override fun stop() { stops++; running = false; if(failAt == "stop") throw IOException("private-stop") }
    }
    @Test fun providerFailureIsIsolatedAndNextTickRecoversAtExistingInterval() {
        var now = 0L
        val provider = Provider().apply { failAt = "fix" }
        val logs = ArrayList<String>()
        val reporter = Iap2LocationReporter(provider, { logs.add(it) }, nanoTime = { now })
        val sent = ArrayList<Iap2Frame>()
        assertTrue(reporter.handle(Iap2Frame(0xfffa, byteArrayOf())) { sent.add(it) })
        assertTrue(sent.isEmpty()); assertEquals(1, provider.calls)
        provider.failAt = ""
        now = 999_000_000L; reporter.tick { sent.add(it) }; assertTrue(sent.isEmpty())
        now = 1_000_000_000L; reporter.tick { sent.add(it) }; assertEquals(1, sent.size)
        assertEquals(0xfffb, sent.single().messageId)
        val diagnostic = Iap2LocationDiagnostics.snapshot().lines().last()
        assertTrue(diagnostic.contains("provider异常=1")); assertTrue(diagnostic.contains("0xFFFB成功发送=1"))
        assertFalse(diagnostic.contains("private")); assertFalse(logs.joinToString().contains("private"))
    }
    @Test fun requestAndStartAndStopFailuresDoNotEscapeOptionalProvider() {
        for(stage in listOf("request", "start", "stop")) {
            val provider = Provider().apply { failAt = stage }
            val logs = ArrayList<String>()
            val reporter = Iap2LocationReporter(provider, { logs.add(it) })
            reporter.handle(Iap2Frame(0xfffa, byteArrayOf())) {}
            reporter.handle(Iap2Frame(0xfffc, byteArrayOf())) {}
            assertFalse(provider.running)
            assertFalse(logs.joinToString().contains("private"))
        }
    }
    @Test fun realTransportSendFailureStillPropagatesAndDoesNotCountAsSent() {
        val reporter = Iap2LocationReporter(Provider(), {})
        val expected = IOException("private-wire-error")
        try {
            reporter.handle(Iap2Frame(0xfffa, byteArrayOf())) { throw expected }
            fail("Real transport failure swallowed")
        } catch(actual: IOException) { assertSame(expected, actual) }
        val text = Iap2LocationDiagnostics.snapshot().lines().last()
        assertTrue(text.contains("0xFFFB成功发送=0 发送异常=1"))
        assertFalse(text.contains("private"))
    }
    @Test fun emptyAndMissingFixNeverBuildOrSendAFrame() {
        for(fix in listOf(null, "")) {
            val provider = Provider().apply { this.fix = fix }
            val reporter = Iap2LocationReporter(provider, {})
            reporter.handle(Iap2Frame(0xfffa, byteArrayOf())) { fail("Empty fix sent") }
            val text = Iap2LocationDiagnostics.snapshot().lines().last()
            assertTrue(text.contains("无可用fix=1")); assertTrue(text.contains("0xFFFB成功发送=0"))
        }
    }
    @Test fun sharedRequestStopIsAppliedWithoutRepeatingSubscription() {
        val shared = com.shilapi.xcertplay.transport.Iap2LocationRequest()
        val bt = Provider(); val wifi = Provider()
        val first = Iap2LocationReporter(bt, {}, shared)
        val next = Iap2LocationReporter(wifi, {}, shared, true)
        first.handle(Iap2Frame(0xfffa, byteArrayOf())) {}
        next.tick {}; assertTrue(wifi.running)
        first.handle(Iap2Frame(0xfffc, byteArrayOf())) {}
        next.tick {}; assertFalse(wifi.running)
        assertEquals(1, wifi.starts)
    }
    @Test fun reporterHistoryIsBoundedToEightInstances() {
        repeat(20) { Iap2LocationReporter(null, {}) }
        val text = Iap2LocationDiagnostics.snapshot()
        assertEquals(8, text.lines().size); assertTrue(text.length < 5000)
    }
}
