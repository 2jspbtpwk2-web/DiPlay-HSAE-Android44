package local.airuize.receiver

import com.shilapi.xcertplay.airplay.ScreenFrameCrypto

/** Converts cumulative counters to intervals. A reset/gap starts a new baseline. */
internal class LearningTelemetry {
    data class Counts(val received: Long, val released: Long, val bytes: Long,
        val timeouts: Long, val errors: Long, val recoveries: Long, val queueAgeMs: Long)
    private var previous: Counts? = null
    private var crypto: ScreenFrameCrypto.Counters? = null
    private var at = -1L
    @Synchronized fun sample(now: Long, count: Counts, native: ScreenFrameCrypto.Counters,
        surface: Boolean, decoder: Boolean): PerformanceLearningPolicy.Window? {
        val old = previous; val oldNative = crypto; val duration = now - at
        previous = count; crypto = native; at = now
        if (old == null || oldNative == null || duration !in 4_000L..10_000L) return null
        val values = longArrayOf(count.received-old.received, count.released-old.released,
            count.bytes-old.bytes, count.timeouts-old.timeouts, count.errors-old.errors,
            count.recoveries-old.recoveries, native.nativeFrames-oldNative.nativeFrames,
            native.fallbackFrames-oldNative.fallbackFrames, native.authRejected-oldNative.authRejected,
            native.backendFailures-oldNative.backendFailures)
        if (values.any { it < 0L } || count.queueAgeMs < 0L) return null
        return PerformanceLearningPolicy.Window(now, duration, values[0], values[1], values[2],
            count.queueAgeMs, values[3], values[4], values[5], values[6], values[7], values[8],
            values[9], surfaceValid=surface, decoderActive=decoder)
    }
}
