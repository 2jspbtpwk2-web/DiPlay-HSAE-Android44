package local.airuize.receiver

import java.util.ArrayDeque

/** Bounded rules only. No phone identity, coordinates, media, protocol changes or driver tuning. */
internal object PerformanceLearningPolicy {
    const val SCHEMA = 1
    const val BAD_WINDOWS = 4
    const val GOOD_WINDOWS = 12
    const val WARM_UP_MS = 15_000L

    data class Profile(val width: Int, val height: Int, val fps: Int) {
        fun key(): String = "${width}x${height}@$fps"
        companion object {
            fun decode(value: String): Profile? {
                val m = Regex("^(\\d{1,4})x(\\d{1,4})@(\\d{1,2})$").matchEntire(value) ?: return null
                val p = Profile(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                return p.takeIf { it.width in 1..4096 && it.height in 1..4096 && it.fps in 1..60 }
            }
        }
    }

    /** All counters and maxima belong to this interval, never to the receiver's whole lifetime. */
    data class Window(
        val atMs: Long, val durationMs: Long,
        val receivedFrames: Long, val releasedOutputs: Long, val receivedBytes: Long,
        val queueAgeMaxMs: Long, val inputTimeouts: Long, val decoderErrors: Long,
        val recoveryRequests: Long, val nativeFrames: Long,
        val fallbackFrames: Long = 0, val authRejected: Long = 0, val backendFailures: Long = 0,
        val cryptoTotalUs: Long = 0, val cryptoMaxUs: Long = 0,
        val surfaceValid: Boolean = true, val decoderActive: Boolean = true,
    ) {
        fun valid(): Boolean = atMs >= 0 && durationMs in 4_000L..10_000L &&
            listOf(receivedFrames, releasedOutputs, inputTimeouts, decoderErrors, recoveryRequests,
                nativeFrames, fallbackFrames, authRejected, backendFailures).all { it in 0L..1_000_000L } &&
            receivedBytes in 0L..268_435_456L && queueAgeMaxMs in 0L..60_000L &&
            cryptoTotalUs in 0L..(durationMs * 1_000L) && cryptoMaxUs in 0L..cryptoTotalUs

        fun encode(): String = listOf(atMs, durationMs, receivedFrames, releasedOutputs, receivedBytes,
            queueAgeMaxMs, inputTimeouts, decoderErrors, recoveryRequests, nativeFrames, fallbackFrames,
            authRejected, backendFailures, cryptoTotalUs, cryptoMaxUs,
            if (surfaceValid) 1 else 0, if (decoderActive) 1 else 0).joinToString(",")

        companion object {
            fun decode(value: String): Window? {
                if (value.length > 400) return null
                val values = value.split(',').map { it.toLongOrNull() ?: return null }
                if (values.size != 17 || values[15] !in 0L..1L || values[16] !in 0L..1L) return null
                val w = Window(values[0], values[1], values[2], values[3], values[4], values[5],
                    values[6], values[7], values[8], values[9], values[10], values[11], values[12],
                    values[13], values[14], values[15] == 1L, values[16] == 1L)
                return w.takeIf { it.valid() }
            }
        }
    }

    data class Environment(val visible: Boolean, val diagnostic: Boolean, val voice: Boolean,
        val uninterrupted: Boolean, val recentTouch: Boolean)

    enum class Reason {
        INVALID, EXCLUDED, WARM_UP, STATIC_OR_INSUFFICIENT, NO_SURFACE, NATIVE_NOT_VERIFIED,
        AUTH_FAILURE, BACKEND_FAILURE, DECODER_FAILURE, NATIVE_CRYPTO_BUSY, DECODE_BACKLOG,
        HEALTHY, INCONCLUSIVE,
    }

    data class Result(val reason: Reason, val learnable: Boolean = false, val anomaly: Boolean = false,
        val nextTier: Int? = null, val stableTier: Int? = null)

    /** Only resolution is varied. The existing frame rate, codec and portrait/landscape remain. */
    fun profiles(base: Profile): List<Profile> {
        if (base.width !in 320..1072 || base.height !in 320..1072 || base.fps !in 15..30) return emptyList()
        val result = arrayListOf(base)
        for (factor in listOf(8.0 / 9.0, 7.0 / 9.0)) {
            fun align(n: Int) = ((n * factor / 16.0 + 0.5).toInt() * 16).coerceAtLeast(16)
            val next = Profile(align(base.width), align(base.height), base.fps)
            val old = result.last()
            val ratioError = kotlin.math.abs(next.width.toDouble() / next.height - base.width.toDouble() / base.height)
            val sameOrientation = (next.width > next.height) == (base.width > base.height) &&
                (next.width == next.height) == (base.width == base.height)
            if (minOf(next.width, next.height) >= 360 && next.width <= old.width && next.height <= old.height &&
                next != old && sameOrientation && ratioError / (base.width.toDouble() / base.height) < 0.03) result.add(next)
        }
        return result
    }

    /** One decision per session. No method disconnects, reconfigures or modifies a running decoder. */
    class Session(val startedMs: Long, val tier: Int, private val tierCount: Int) {
        private var lastAtMs = -1L
        private var firstVideoAtMs = -1L
        private var bad = 0
        private var good = 0
        private var decided = false
        var eligibleWindows = 0; private set
        var healthyWindows = 0; private set
        var anomalyWindows = 0; private set
        var outputSeen = false; private set

        fun invalidateStreak() { bad = 0; good = 0 }

        fun observe(w: Window, environment: Environment): Result {
            if (!w.valid() || w.atMs <= lastAtMs || w.atMs < startedMs ||
                (lastAtMs >= 0 && w.atMs - lastAtMs < w.durationMs / 2)) {
                invalidateStreak(); return Result(Reason.INVALID)
            }
            val continuous = lastAtMs < 0 || w.atMs - lastAtMs <= 12_000L
            lastAtMs = w.atMs
            if (!continuous) { bad = 0; good = 0 }
            if (!environment.visible || environment.diagnostic || environment.voice || !environment.uninterrupted) {
                bad = 0; good = 0; return Result(Reason.EXCLUDED)
            }
            if (!w.surfaceValid || !w.decoderActive) {
                bad = 0; good = 0; return Result(Reason.NO_SURFACE)
            }
            if (firstVideoAtMs < 0 && w.receivedFrames > 0) firstVideoAtMs = w.atMs
            if (w.receivedFrames > 0 && w.releasedOutputs > 0) outputSeen = true
            if (firstVideoAtMs < 0 || w.atMs - firstVideoAtMs < WARM_UP_MS) return Result(Reason.WARM_UP)
            // A static map's low rate is not a performance failure. Recent interaction plus real
            // video activity is required for a resolution decision, including a healthy decision.
            if (!environment.recentTouch || w.receivedFrames * 1_000L < w.durationMs * 6L || w.receivedBytes < 32_768L) {
                bad = 0; good = 0; return Result(Reason.STATIC_OR_INSUFFICIENT)
            }
            if (w.authRejected > 0) return blocked(Reason.AUTH_FAILURE, true)
            if (w.backendFailures > 0) return blocked(Reason.BACKEND_FAILURE, true)
            if (w.fallbackFrames > 0 || w.nativeFrames * 10L < w.receivedFrames * 9L)
                return blocked(Reason.NATIVE_NOT_VERIFIED, true)
            if (w.decoderErrors > 0) return blocked(Reason.DECODER_FAILURE, true)
            eligibleWindows++
            val cryptoBusy = w.cryptoTotalUs * 100L >= w.durationMs * 1_000L * 65L && w.cryptoMaxUs >= 100_000L
            val backlog = w.queueAgeMaxMs >= 200L || w.inputTimeouts >= 2L || w.recoveryRequests > 0L ||
                (w.queueAgeMaxMs >= 100L && w.releasedOutputs * 10L < w.receivedFrames * 7L)
            if (cryptoBusy || backlog) {
                bad++; good = 0; anomalyWindows++
                val lower = if (!decided && bad >= BAD_WINDOWS && tier in 0 until tierCount - 1) tier + 1 else null
                if (lower != null) decided = true
                return Result(if (cryptoBusy) Reason.NATIVE_CRYPTO_BUSY else Reason.DECODE_BACKLOG, true, true, lower)
            }
            val healthy = w.queueAgeMaxMs < 100L && w.inputTimeouts == 0L && w.recoveryRequests == 0L &&
                w.releasedOutputs * 10L >= w.receivedFrames * 8L &&
                w.cryptoTotalUs * 100L < w.durationMs * 1_000L * 50L
            bad = 0
            if (!healthy) { good = 0; return Result(Reason.INCONCLUSIVE, true) }
            good++; healthyWindows++
            return Result(Reason.HEALTHY, true, stableTier = if (good >= GOOD_WINDOWS) tier else null)
        }

        private fun blocked(reason: Reason, anomaly: Boolean): Result {
            bad = 0; good = 0
            if (anomaly) anomalyWindows++
            return Result(reason, anomaly = anomaly)
        }
    }

    data class Pipeline(val durationMs: Long, val frames: Long, val frameBytes: Long,
        val cryptoTotalUs: Long, val cryptoMaxUs: Long)

    // This is the only accepted free-text input. Its complete structure and all keys are fixed.
    // Unknown keys, suffixes, negative/overflow numbers and arbitrary logs are rejected.
    private val pipelinePattern = Regex("^VideoPipeline windowMs=(\\d{1,7}) messages=(\\d{1,9}) frames=(\\d{1,9}) frameBytes=(\\d{1,12}) " +
        "configs=(\\d{1,9}) emptyMessages=(\\d{1,9}) emptyFrames=(\\d{1,9}) otherMessages=(\\d{1,9}) " +
        "fpsMilli=(\\d{1,9}) readMaxMs=(\\d{1,9}) frameGapMaxMs=(\\d{1,9}) lastFrameAgeMs=(?:none|\\d{1,9}) " +
        "cryptoTotalUs=(\\d{1,12}) cryptoMaxUs=(\\d{1,12}) annexBTotalUs=(\\d{1,12}) annexBMaxUs=(\\d{1,12}) " +
        "handoffTotalUs=(\\d{1,12}) handoffMaxUs=(\\d{1,12}) ended=false$")

    fun parsePipeline(message: String): Pipeline? {
        if (message.length > 800) return null
        val m = pipelinePattern.matchEntire(message) ?: return null
        val n = m.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        val duration = n[0]; val frames = n[2]; val bytes = n[3]; val total = n[11]; val peak = n[12]
        if (duration !in 4_000L..10_000L || frames !in 0L..1_000_000L || bytes !in 0L..268_435_456L ||
            total !in 0L..duration * 1_000L || peak !in 0L..total) return null
        return Pipeline(duration, frames, bytes, total, peak)
    }
}

/** A small journal: four pre-windows plus the trigger plus three post-windows, three episodes. */
internal class PerformanceEvidence {
    data class Entry(val window: PerformanceLearningPolicy.Window, val reason: PerformanceLearningPolicy.Reason,
        val session: Long = 0, val profile: PerformanceLearningPolicy.Profile? = null) {
        fun encode(): String = "${reason.name}:$session:${profile?.key() ?: "none"}:" + window.encode()
        companion object {
            fun decode(line: String): Entry? {
                val parts = line.split(':')
                if (parts.size != 4) return null
                val reason = PerformanceLearningPolicy.Reason.values().firstOrNull { it.name == parts[0] } ?: return null
                val session = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                val profile = if (parts[2] == "none") null else PerformanceLearningPolicy.Profile.decode(parts[2]) ?: return null
                return PerformanceLearningPolicy.Window.decode(parts[3])?.let { Entry(it, reason, session, profile) }
            }
        }
    }
    private val before = ArrayDeque<Entry>()
    private val episodes = ArrayDeque<MutableList<Entry>>()
    private var remaining = 0
    private var lastTrigger = -120_000L
    var droppedEpisodes = 0; private set
    var revision = 0L; private set

    fun add(window: PerformanceLearningPolicy.Window, result: PerformanceLearningPolicy.Result,
        session: Long = 0, profile: PerformanceLearningPolicy.Profile? = null) {
        if (!window.valid() || session < 0 || result.reason == PerformanceLearningPolicy.Reason.INVALID ||
            (profile != null && PerformanceLearningPolicy.Profile.decode(profile.key()) == null)) return
        val entry = Entry(window, result.reason, session, profile)
        if (remaining > 0 && episodes.isNotEmpty()) {
            episodes.peekLast().add(entry); remaining--; revision++
        } else if (result.anomaly && window.atMs - lastTrigger >= 120_000L) {
            if (episodes.size >= 3) { episodes.removeFirst(); droppedEpisodes++ }
            val episode = before.toMutableList(); episode.add(entry); episodes.addLast(episode)
            lastTrigger = window.atMs; remaining = 3; revision++
        }
        before.addLast(entry)
        while (before.size > 4) before.removeFirst()
    }

    /** Cross-session pre-context must never look like it belongs to a new session. */
    fun sessionBoundary() { before.clear(); remaining = 0; lastTrigger = -120_000L }

    fun encode(): String = "PLE1\n" + episodes.joinToString("\n--\n") { episode -> episode.joinToString("\n") { it.encode() } }

    fun restore(value: String) {
        before.clear(); episodes.clear(); remaining = 0; lastTrigger = -120_000L
        droppedEpisodes = 0; revision++
        if (value.length > 32_768 || !value.startsWith("PLE1\n")) return
        val body = value.removePrefix("PLE1\n")
        if (body.isEmpty()) return
        val chunks = body.split("\n--\n")
        if (chunks.size > 3) return
        val decoded = chunks.map { chunk ->
            val lines = chunk.split('\n')
            if (lines.size !in 1..8) return
            lines.map { Entry.decode(it) ?: return }
        }
        decoded.forEach { episodes.addLast(it.toMutableList()) }
    }

    fun snapshot(): String = buildString {
        append("异常自动留证：最多3段，每段前4/触发1/后3窗口；episodes=${episodes.size} dropped=$droppedEpisodes\n")
        episodes.forEachIndexed { index, episode ->
            append("episode=${index + 1} windows=${episode.size}\n")
            episode.forEach { e ->
                val w = e.window
                append("generation=${e.session} profile=${e.profile?.key() ?: "unknown"} monoMs=${w.atMs} intervalMs=${w.durationMs} reason=${e.reason} rx=${w.receivedFrames} bytes=${w.receivedBytes} ")
                append("outputReleased=${w.releasedOutputs} queueAgeMaxMs=${w.queueAgeMaxMs} inputTimeouts=${w.inputTimeouts} decoderErrors=${w.decoderErrors} ")
                append("recoveries=${w.recoveryRequests} nativeFrames=${w.nativeFrames} fallback=${w.fallbackFrames} authRejected=${w.authRejected} backendFailures=${w.backendFailures} ")
                append("cryptoTotalUs=${w.cryptoTotalUs} cryptoMaxUs=${w.cryptoMaxUs} surfaceValid=${w.surfaceValid} decoderActive=${w.decoderActive}\n")
            }
        }
        append("outputReleased仅输出缓冲释放计数；不是屏幕实测FPS或端到端触摸延迟；不保存画面/音频/坐标/原始日志。")
    }
}

/** Discrete connection events complement the video journal, including failures before a sink exists. */
internal class PerformanceFailureEvidence {
    enum class Stage {
        CONTROL_FAILURE, SESSION_ENDED, CONNECTION_FAILURE, VIDEO_FAILURE, AUDIO_FAILURE,
        NCM_FAILURE, TLS_FAILURE, PAIRING_FAILURE, TUN_FAILURE, IAP2_FAILURE, USB_FAILURE,
    }
    private data class Event(val session: Long, val atMs: Long, val stage: Stage)
    private val events = ArrayDeque<Event>()
    private var duplicateEvents = 0L

    fun add(session: Long, atMs: Long, stageName: String): Boolean {
        if (session < 0 || atMs < 0 || stageName.length > 40) return false
        val stage = Stage.values().firstOrNull { it.name == stageName } ?: return false
        val last = events.peekLast()
        if (last != null && last.session == session && last.stage == stage && atMs >= last.atMs && atMs - last.atMs < 10_000L) {
            duplicateEvents++; return false
        }
        if (events.size >= 24) events.removeFirst()
        events.addLast(Event(session, atMs, stage)); return true
    }

    fun encode(): String = "PLF1\n" + events.joinToString("\n") { "${it.session},${it.atMs},${it.stage.name}" }

    fun restore(value: String) {
        events.clear(); duplicateEvents = 0
        if (value.length > 4_096 || !value.startsWith("PLF1\n")) return
        val body = value.removePrefix("PLF1\n")
        if (body.isEmpty()) return
        val lines = body.split('\n')
        if (lines.size > 24) return
        val decoded = lines.map { line ->
            val parts = line.split(',')
            if (parts.size != 3) return
            val session = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return
            val at = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return
            val stage = Stage.values().firstOrNull { it.name == parts[2] } ?: return
            Event(session, at, stage)
        }
        decoded.forEach { events.addLast(it) }
    }

    fun snapshot(): String = "连接事件自动留证：最多24项 count=${events.size} coalesced=$duplicateEvents；" +
        "SESSION_ENDED仅说明结束，不自动判定故障；时间是事件发生当时的单调时间，可能跨应用/设备重启。\n" +
        events.joinToString("\n") { "generation=${it.session} monoMs=${it.atMs} event=${it.stage.name}" }
}
