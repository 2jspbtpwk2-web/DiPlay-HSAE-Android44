package local.airuize.receiver

import kotlin.math.sqrt
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Decisions use bounded numeric evidence. No PCM, coordinates, routes or protocol state are kept. */
object FeatureLearningPolicy {
    /** -1 is local calibration; positive counts preserve existing simultaneous live uplinks. */
    class MicrophoneLeaseGate {
        private val owners=AtomicInteger()
        fun tryCalibration():Closeable? = if(owners.compareAndSet(0,-1)) lease(true) else null
        fun tryLive():Closeable? {
            while(true) {
                val n=owners.get()
                if(n<0 || n==Int.MAX_VALUE) return null
                if(owners.compareAndSet(n,n+1)) return lease(false)
            }
        }
        private fun lease(calibration:Boolean):Closeable {
            val closed=AtomicBoolean()
            return Closeable {
                if(closed.compareAndSet(false,true)) {
                    if(calibration) owners.compareAndSet(-1,0) else owners.decrementAndGet()
                }
            }
        }
        fun state():Int=owners.get()
    }
    val microphoneSources = listOf(6, 7)
    val audioBuffers = listOf(300, 500, 1000)
    const val MICROPHONE_WINDOW_MS = 3000L
    const val GPS_WINDOW_MS = 30000L

    data class PcmEvidence(val reads: Long = 0, val bytes: Long = 0, val samples: Long = 0,
        val peak: Int = 0, val squares: Double = 0.0, val zeros: Long = 0, val clipped: Long = 0,
        val readErrors: Long = 0, val error: String = "") {
        val rms: Double get() = if(samples > 0) sqrt(squares / samples) else 0.0
        val usable: Boolean get() = reads > 0 && bytes >= samples*2 && samples >= 800 &&
            samples <= 320000 && peak in 0..32768 && squares >= 0.0 && !squares.isNaN() && !squares.isInfinite() &&
            zeros in 0..samples && clipped in 0..samples && readErrors == 0L && error.isEmpty()
        val allZero: Boolean get() = samples > 0 && zeros == samples
        val clipping: Boolean get() = samples > 0 && clipped.toDouble() / samples > 0.01
    }
    class PcmAccumulator {
        private var reads = 0L; private var bytes = 0L; private var samples = 0L
        private var peak = 0; private var squares = 0.0; private var zeros = 0L; private var clips = 0L
        private var errors = 0L; private var error = ""
        /** Reads only the valid prefix; never retains the supplied buffer. */
        fun read(data: ByteArray, count: Int) {
            if(count <= 0 || count > data.size) { errors++; return }
            if(count and 1 != 0) errors++
            reads++; bytes += count
            var offset = 0
            while(offset + 1 < count) {
                val sample = ((data[offset].toInt() and 255) or (data[offset+1].toInt() shl 8)).toShort().toInt()
                val magnitude = if(sample < 0) -sample else sample
                samples++; peak = maxOf(peak, magnitude); squares += sample.toDouble()*sample
                if(sample == 0) zeros++
                if(magnitude >= 32767) clips++
                offset += 2
            }
        }
        fun failed(type: String) { errors++; error = safeType(type) }
        fun evidence() = PcmEvidence(reads, bytes, samples, peak, squares, zeros, clips, errors, error)
    }
    data class GpsQuality(val runId: Long = 0, val callbacks: Long = 0, val fresh: Long = 0,
        val rejected: Long = 0, val errors: Long = 0, val accuracySamples: Long = 0,
        val accuracyTotal: Double = 0.0, val bestAccuracy: Float = -1f, val worstAccuracy: Float = -1f) {
        val meanAccuracy: Double get() = if(accuracySamples > 0) accuracyTotal / accuracySamples else -1.0
    }
    fun source(purpose: String, fallback: Int, enabled: Boolean, confirmed: Boolean,
        candidate: Int, quiet: PcmEvidence, speech: PcmEvidence): Int =
        if(enabled && confirmed && purpose in listOf("speechrecognition", "telephony") &&
            candidate in microphoneSources && quiet.usable && speech.usable && !speech.allZero && !speech.clipping)
            candidate else fallback

    /** A change in the main settings always wins over a remembered learning selection. */
    fun buffer(current: Int, original: Int, selected: Int, enabled: Boolean): Int =
        if(enabled && current == original && selected in audioBuffers) selected else current

    fun newGpsEvidence(previous: GpsQuality, current: GpsQuality): Boolean = current.runId > 0 &&
        (current.runId != previous.runId || current.callbacks != previous.callbacks ||
            current.errors != previous.errors || current.rejected != previous.rejected)

    fun safeType(value: String): String = value.take(64).map {
        if(it.isLetterOrDigit() || it in "_$.-") it else '_'
    }.joinToString("")
}
