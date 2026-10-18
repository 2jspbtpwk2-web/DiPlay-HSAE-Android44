package local.airuize.receiver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

/** Bounded GPS metadata observation. Never enables a provider or exports a location/NMEA sentence. */
class LegacyGpsProbe(context: Context, private val log: (String) -> Unit) : Closeable {
    private val qualityId = qualitySequence.incrementAndGet()
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cleanupState = GpsCleanupState()
    @Volatile private var running = false
    @Volatile private var closed = false
    private var startedAt = -1L
    private var endedAt = -1L
    private var callbacks = 0L
    private var accepted = 0L
    private var mode = "未启动"
    private var latest = "无回调"
    private var satellite = "未采样"
    private var lastError = "无"
    private var satelliteEvents = 0L
    @Volatile private var subscription = false
    @Volatile private var gpsSubscription = false
    @Volatile private var statusSubscription = false
    private var activeHandoffAttempted = false
    private var deadline = -1L
    private var firstFreshMs = -1L
    private var qualityErrors = 0L
    private var accuracySamples = 0L
    private var accuracyTotal = 0.0
    private var bestAccuracy = -1f
    private var worstAccuracy = -1f
    private val rejected = LongArray(GpsDiagnostics.Rejection.values().size)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running || closed) return
            val age = ageMillis(location)
            val rejection = GpsDiagnostics.rejection(location.provider, location.latitude, location.longitude,
                location.isFromMockProvider, age, location.time)
            synchronized(lock) {
                if(!running || closed) return
                callbacks++
                if(rejection == null) {
                    accepted++
                    if(location.hasAccuracy() && location.accuracy >= 0f && !location.accuracy.isNaN() && !location.accuracy.isInfinite()) {
                        val accuracy = location.accuracy
                        accuracySamples++; accuracyTotal += accuracy
                        bestAccuracy = if(bestAccuracy < 0f) accuracy else minOf(bestAccuracy, accuracy)
                        worstAccuracy = maxOf(worstAccuracy, accuracy)
                    }
                    if(firstFreshMs < 0) firstFreshMs = (SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0)
                } else rejected[rejection.ordinal]++
                latest = GpsDiagnostics.describe(location.provider, age,
                    if(location.hasAccuracy()) location.accuracy else null,
                    location.isFromMockProvider, location.hasSpeed(), location.hasBearing())
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }
    private val statusListener = GpsStatus.Listener {
        val observe = synchronized(lock) {
            if(!running || closed) false else { satelliteEvents++; true }
        }
        if(observe) updateSatellite()
    }
    private val tick = object : Runnable {
        override fun run() {
            if(!running || closed) return
            if(SystemClock.elapsedRealtime() >= deadline) { finish(false); return }
            // Relinquish this diagnostic's active GPS request when a real session starts.
            if(gpsSubscription && sessionActive() && !activeHandoffAttempted) {
                activeHandoffAttempted = true
                cleanupState.queued(true, false)
                try {
                    manager?.removeUpdates(listener)
                    subscription = false; gpsSubscription = false
                    cleanupState.locationReturned()
                    subscribe(true)
                } catch(error: Exception) {
                    failed(error); cleanupState.locationFailed(error)
                    synchronized(lock) { mode = "活动会话：诊断主动GPS注销失败，保留清理义务，结束时重试一次；未另加被动监听" }
                }
                cleanupState.finished()
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun start(durationMillis: Long = 60000L) {
        synchronized(lock) {
            // One observation per instance; a failed unregister must not be hidden by reuse.
            if(running || closed || startedAt >= 0) return
            running = true; startedAt = SystemClock.elapsedRealtime(); endedAt = -1L
            callbacks = 0; accepted = 0; satelliteEvents = 0; firstFreshMs = -1L
            activeHandoffAttempted = false
            for(i in rejected.indices) rejected[i] = 0
            deadline = startedAt + durationMillis.coerceIn(1000L, 120000L)
        }
        handler.post {
            if(!running || closed) return@post
            updateSatellite()
            val locations = manager
            if(locations == null) { synchronized(lock) { mode="LocationManager缺失" }; finish(false); return@post }
            try {
                statusSubscription = true // A vendor call may register then throw.
                statusSubscription = locations.addGpsStatusListener(statusListener)
            }
            catch(error: Exception) { failed(error) }
            subscribe(sessionActive())
            log("GPS诊断：有界元数据观察已开始；活动会话只用被动定位/卫星快照；不记录坐标或NMEA")
            handler.post(tick)
        }
    }
    private fun sessionActive(): Boolean = LegacyWiredService.instance?.activeSession != null
    private fun subscribe(passive: Boolean) {
        if(!running || closed) return
        val locations = manager ?: return
        try {
            val provider = if(passive) LocationManager.PASSIVE_PROVIDER else LocationManager.GPS_PROVIDER
            val exists = locations.allProviders.contains(provider)
            val enabled = exists && locations.isProviderEnabled(provider)
            synchronized(lock) { mode = if(passive) "会话活动：被动回调/卫星聚合" else "无活动会话：已启用GPS的有界订阅" }
            if(!enabled) { synchronized(lock) { mode += "（源缺失或未启用，不强开）" }; return }
            // Keep the cleanup obligation even if a vendor call registers then throws.
            subscription = true; gpsSubscription = !passive
            locations.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        } catch(error: Exception) { failed(error) }
    }
    private fun updateSatellite() {
        if(!running || closed) return
        try {
            val metadata = satellites(manager)
            synchronized(lock) { if(running && !closed) satellite = metadata }
        } catch(error: Exception) { failed(error) }
    }
    private fun failed(error: Throwable) { synchronized(lock) { qualityErrors++; lastError = LocationDiagnostics.exception(error) } }
    /** Numeric copy only. Does not start, extend or change this probe's subscriptions. */
    fun quality(): FeatureLearningPolicy.GpsQuality = synchronized(lock) {
        FeatureLearningPolicy.GpsQuality(qualityId, callbacks, accepted, rejected.sum(), qualityErrors,
            accuracySamples, accuracyTotal, bestAccuracy, worstAccuracy)
    }
    fun snapshot(): String = synchronized(lock) {
        val elapsed = if(startedAt < 0) -1 else ((if(endedAt >= 0) endedAt else SystemClock.elapsedRealtime())-startedAt).coerceAtLeast(0)
        "GPS有界观察 running=$running closed=$closed mode=$mode 定位监听=$subscription 主动GPS监听=$gpsSubscription 卫星监听=$statusSubscription 已观察Ms=$elapsed 回调=$callbacks 新鲜非模拟GPS=$accepted " +
            "首个新鲜回调Ms=$firstFreshMs（不是冷启动TTFF） 拒绝=[" +
            GpsDiagnostics.Rejection.values().joinToString(",") { it.name+"="+rejected[it.ordinal] } + "] " +
            "卫星事件=$satelliteEvents 卫星=[$satellite] 最近fix=[$latest] 最近错误=$lastError " +
            cleanupState.snapshot()+"；未向iPhone注入位置；未观察到不等于硬件不存在"
    }
    private fun finish(cancelled: Boolean) {
        synchronized(lock) {
            if(!running) return
            running = false; endedAt = SystemClock.elapsedRealtime()
        }
        handler.removeCallbacks(tick)
        cleanup()
        log("GPS诊断${if(cancelled) "取消" else "结束"}：${snapshot()}")
    }
    private fun cleanup() {
        cleanupState.queued(subscription, statusSubscription)
        if(subscription) try {
            manager?.removeUpdates(listener); cleanupState.locationReturned()
            subscription = false; gpsSubscription = false
        }
        catch(error: Exception) { failed(error); cleanupState.locationFailed(error) }
        if(statusSubscription) try {
            manager?.removeGpsStatusListener(statusListener); cleanupState.satelliteReturned()
            statusSubscription = false
        }
        catch(error: Exception) { failed(error); cleanupState.satelliteFailed(error) }
        cleanupState.finished()
    }
    override fun close() {
        synchronized(lock) {
            if(closed) return
            closed = true; if(running) { running = false; endedAt = SystemClock.elapsedRealtime() }
        }
        handler.removeCallbacks(tick)
        cleanupState.queued(subscription, statusSubscription)
        if(Looper.myLooper() == Looper.getMainLooper()) cleanup()
        else if(!handler.post { cleanup() }) cleanupState.queueRejected()
    }

    companion object {
        private val qualitySequence = java.util.concurrent.atomic.AtomicLong()
        private fun ageMillis(location: Location): Long = if(location.elapsedRealtimeNanos > 0)
            (SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000L else -1L
        private fun satellites(manager: LocationManager?): String {
            val status = manager?.getGpsStatus(null) ?: return "无GpsStatus缓存"
            val samples = ArrayList<Pair<Float, Boolean>>()
            val iterator = status.satellites.iterator()
            while(iterator.hasNext() && samples.size < 256) {
                val item = iterator.next()
                samples.add(item.snr to item.usedInFix())
            }
            return GpsDiagnostics.satelliteSummary(samples, status.timeToFirstFix)
        }
        /** Read cached metadata only: never subscribes, binds OEM services or changes settings. */
        fun snapshot(context: Context): String {
            val lines = ArrayList<String>()
            lines.add("GPS静态能力：设置vehicleGps=${ReceiverSettings.read(context).gps} " +
                "finePermission=${context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED} " +
                "coarsePermission=${context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED}")
            val locations = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if(locations == null) return lines.joinToString("\n")+"\nLocationManager缺失"
            for(name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val provider = locations.getProvider(name)
                    lines.add("provider=$name 存在=${provider!=null} enabled=${locations.isProviderEnabled(name)}" +
                        if(provider == null) "" else " accuracy=${provider.accuracy} power=${provider.powerRequirement} " +
                            "requiresSatellite=${provider.requiresSatellite()} supportsSpeed=${provider.supportsSpeed()} supportsBearing=${provider.supportsBearing()} supportsAltitude=${provider.supportsAltitude()}")
                    val cached = locations.getLastKnownLocation(name)
                    lines.add("$name 缓存=" + if(cached == null) "无" else GpsDiagnostics.describe(cached.provider, ageMillis(cached),
                        if(cached.hasAccuracy()) cached.accuracy else null, cached.isFromMockProvider, cached.hasSpeed(), cached.hasBearing()) +
                        " timestampValid=${cached.time>0} coordinateValid=${cached.latitude in -90.0..90.0 && cached.longitude in -180.0..180.0}")
                } catch(error: Exception) { lines.add("provider=$name 查询异常=${LocationDiagnostics.exception(error)}") }
            }
            try { lines.add("卫星缓存="+satellites(locations)) }
            catch(error: Exception) { lines.add("卫星缓存查询异常="+LocationDiagnostics.exception(error)) }
            lines.add("以上缓存不算实时通过；未开启定位源；不记录经纬度/路线/NMEA；iPhone实际采用仍需实车验证")
            return lines.joinToString("\n")
        }
    }
}
