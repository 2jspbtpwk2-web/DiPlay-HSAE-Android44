package local.airuize.receiver

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider

/** One provider per iAP link; stopping Bluetooth must not stop the Wi-Fi provider. */
class LegacyCarPlayLocationProvider(context: Context, private val link: String,
    private val log: (String) -> Unit, private val vehicleSpeed: () -> Double? = { null }) : Iap2LocationProvider {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val diagnostics = LocationDiagnostics.gps(link)
    private var running = false
    private var closed = false
    private val latest = LatestGpsFix<Location>()
    private var updates = 0L
    private var encoded = 0L
    private var seeded = 0L
    private var rejected = 0L
    private var canSpeedUsed = 0L
    private fun age(location: Location): Long = if (location.elapsedRealtimeNanos > 0)
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 else -1
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = synchronized(this@LegacyCarPlayLocationProvider) {
            if (running && !closed) {
                val rejectedReason = recordFix(location, false)
                if (rejectedReason == null) {
                    updates++
                    if (updates == 1L || updates % 30 == 0L) log("车载GPS[$link]有效更新=$updates；ageMs=${age(location)}；有方向=${location.hasBearing()}（不记录位置）")
                } else { rejected++ }
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = synchronized(this@LegacyCarPlayLocationProvider) { latest.clear() }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }
    override fun onRequested(components: Set<Int>) { diagnostics.requested(components); log("车载GPS[$link]手机定位订阅已收到；请求项数量=${components.size}；提供GGA/RMC；CAN车速由车辆信息读取层提供") }
    private fun recordFix(location: Location, cached: Boolean): GpsDiagnostics.Rejection? {
        val age = age(location)
        val invalid = GpsDiagnostics.rejection(location.provider, location.latitude, location.longitude,
            location.isFromMockProvider, age, location.time)
        val offered = latest.offer(Location(location), location.elapsedRealtimeNanos, invalid == null)
        val reason = if (offered == LatestGpsFix.Result.OUT_OF_ORDER) GpsDiagnostics.Rejection.ORDER else invalid
        diagnostics.fix(cached, reason, location.provider, age, if(location.hasAccuracy()) location.accuracy else null,
            location.isFromMockProvider, location.hasSpeed(), location.hasBearing())
        return reason
    }
    fun diagnosticSnapshot(): String = diagnostics.snapshot()
    @Synchronized override fun start(): Boolean {
        if (closed) { diagnostics.started(LocationDiagnostics.StartState.CLOSED); return false }
        if (running) { diagnostics.started(LocationDiagnostics.StartState.ALREADY_RUNNING); return true }
        val gps = manager ?: run { diagnostics.started(LocationDiagnostics.StartState.MANAGER_MISSING); return false }
        try {
            if(diagnostics.registration.needsRemoval()) {
                // A prior failed STOP still owns this listener; do not layer another request on it.
                try { gps.removeUpdates(listener); diagnostics.registration.removalReturned() }
                catch(error: Exception) {
                    diagnostics.registration.removalFailed(error)
                    diagnostics.failed(LocationDiagnostics.FailureStage.STOP,error)
                    diagnostics.started(LocationDiagnostics.StartState.FAILED)
                    return false
                }
            }
            if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) { diagnostics.started(LocationDiagnostics.StartState.GPS_DISABLED); log("车载GPS[$link]未启用，暂不发送位置"); return false }
            // Match upstream seedLastKnownLocations, but accept only fresh real GPS.
            // This lets StartLocationInformation receive a fix immediately rather than
            // waiting for a one-second callback; STOP still cancels all subsequent data.
            try {
                val cached = gps.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (cached != null && recordFix(cached, true) == null) {
                    seeded++
                    log("车载GPS[$link]按原仓库预置新鲜GPS缓存；ageMs=${age(cached)}；缓存采用=$seeded（不等于新回调）")
                }
            } catch (error: Exception) { diagnostics.failed(LocationDiagnostics.FailureStage.CACHE,error); log("车载GPS[$link]缓存读取异常=${error.javaClass.simpleName}；继续等待实时GPS") }
            running = true
            diagnostics.registration.beforeRequest()
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            diagnostics.started(LocationDiagnostics.StartState.STARTED)
            log("车载GPS[$link]已开始订阅；仅新鲜GPS缓存或实时更新，不用network/模拟位置")
            return true
        } catch (error: Exception) {
            running = false; latest.clear()
            diagnostics.started(LocationDiagnostics.StartState.FAILED)
            diagnostics.failed(LocationDiagnostics.FailureStage.START,error)
            // A vendor LocationManager may register then throw; remove our own listener only.
            if(diagnostics.registration.needsRemoval()) try {
                gps.removeUpdates(listener); diagnostics.registration.removalReturned()
            } catch(cleanup: Exception) {
                diagnostics.registration.removalFailed(cleanup)
                diagnostics.failed(LocationDiagnostics.FailureStage.STOP,cleanup)
            }
            log("车载GPS[$link]订阅失败=${error.javaClass.simpleName}")
            return false
        }
    }
    @Synchronized override fun latestNmea(): String? {
        if (!running || closed) return null
        val location = latest.current() ?: return null
        if (!GpsDiagnostics.freshGps(location.provider, location.latitude, location.longitude, location.isFromMockProvider, age(location))) {
            latest.clear(); rejected++; diagnostics.expired(); log("车载GPS[$link]位置过期，暂停发送等待新位置"); return null
        }
        val canSpeed=vehicleSpeed()
        if(canSpeed!=null) canSpeedUsed++
        return LegacyLocationEncoder.encode(CarPlayLocationFix(location.latitude, location.longitude,
            altitudeMeters = null, // Android altitude datum is not established as NMEA MSL altitude.
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMetersPerSecond = canSpeed ?: if (location.hasSpeed()) location.speed.toDouble() else null,
            timestampMillis = location.time)).also {
                encoded++
                diagnostics.encoded(canSpeed != null)
                if (encoded == 1L || encoded % 30 == 0L) log("车载GPS[$link]有效NMEA提供=$encoded；CAN速度采用=$canSpeedUsed；本次速度来源=${if(canSpeed!=null) "ABS/CAN" else "GPS或缺失"}；手机实际使用仍需验证")
            }
    }
    @Synchronized override fun stop() {
        val wasRunning = running
        running = false; latest.clear()
        diagnostics.stopped()
        if (diagnostics.registration.needsRemoval()) {
            try { manager?.removeUpdates(listener); diagnostics.registration.removalReturned() }
            catch (error: Exception) {
                diagnostics.registration.removalFailed(error)
                diagnostics.failed(LocationDiagnostics.FailureStage.STOP,error); log("车载GPS[$link]停止异常=${error.javaClass.simpleName}")
            }
        }
        if(wasRunning) {
            log("车载GPS[$link]停止；有效更新=$updates；缓存采用=$seeded；NMEA提供=$encoded；拒绝=$rejected")
        }
    }
    @Synchronized override fun close() { closed = true; stop(); diagnostics.closed() }
}
