package local.airuize.receiver

import android.content.Context
import android.media.MediaCodecList
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaAudioBuffer

/** Values are validated before any protocol advertisement, regardless of UI input. */
data class ReceiverSettings(
    val width: Int = 1024, val height: Int = 600, val fps: Int = 30,
    val scale: Int = 10, val physicalMm: Int = 200, val physicalHeight: Boolean = false,
    val inset: Int = 0, val bufferMillis: Int = 300, val volume: Int = 100,
    val navigationDuck: Boolean = false, val audioFocus: Boolean = true,
    val networkMode: String = "saved", val language: String = "zh",
    val hevc: Boolean = false, val opus: Boolean = false,
    val reconnect: Boolean = true, val bootConnect: Boolean = false,
    val background: Boolean = true, val microphone: Boolean = true, val gps: Boolean = true,
    val insetTop: Int = -1, val insetBottom: Int = -1, val insetLeft: Int = -1, val insetRight: Int = -1,
    val drawOutside: Boolean = true, val rightHandDrive: Boolean = false,
    val fullScreen:Boolean=true,val mediaChannel:Int=0,val navigationChannel:Int=0,
    val displayMode: String = LegacyDisplayPolicy.MANUAL,
    val availableWidth: Int = 0, val availableHeight: Int = 0,
    val hardwareWidth: Int = 0, val hardwareHeight: Int = 0,
) {
    fun displaySummary(): String {
        val d = display()
        return "显示适配：模式=${LegacyDisplayPolicy.label(displayMode)}；应用可用区=${availableWidth}x${availableHeight}；硬件屏=${hardwareWidth}x${hardwareHeight}；通告=${d.widthPixels}x${d.heightPixels}；fps=${d.fps}；OEM/系统保留区=${if (hardwareWidth > 0 && hardwareHeight > 0) "${(hardwareHeight - availableHeight).coerceAtLeast(0)}px高/${(hardwareWidth - availableWidth).coerceAtLeast(0)}px宽（不属于CarPlay画面）" else "未知"}"
    }
    fun display(): AirPlayDisplayConfig {
        val pixels = CarPlayDisplayScale.apply(AirPlayDisplayConfig(width, height), scale)
        val mm = AirPlayDisplaySettings.resolvePhysicalSizeMm(pixels.widthPixels, pixels.heightPixels, width, height,
            physicalMm, if (physicalHeight) AirPlayPhysicalSizeBasis.HEIGHT else AirPlayPhysicalSizeBasis.WIDTH)
        val edge = inset.coerceIn(0, minOf(pixels.widthPixels, pixels.heightPixels) / 4)
        return pixels.copy(fps = fps, widthPhysicalMm = mm.widthMm, heightPhysicalMm = mm.heightMm,
            safeArea = AirPlayInsets((if(insetTop<0) edge else insetTop.coerceIn(0,pixels.heightPixels/4)),
                (if(insetBottom<0) edge else insetBottom.coerceIn(0,pixels.heightPixels/4)),
                (if(insetLeft<0) edge else insetLeft.coerceIn(0,pixels.widthPixels/4)),
                (if(insetRight<0) edge else insetRight.coerceIn(0,pixels.widthPixels/4))), safeAreaDrawOutside=drawOutside)
    }
    companion object {
        fun read(context: Context): ReceiverSettings {
            val p = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
            val dm = context.resources.displayMetrics
            val hardware = android.util.DisplayMetrics()
            runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(hardware) }
            val rawWidth = if (p.contains("width")) p.getInt("width", 1024) else null
            val rawHeight = if (p.contains("height")) p.getInt("height", 600) else null
            val lowResolution = p.getBoolean("lowResolution", false)
            val mode = LegacyDisplayPolicy.mode(p.getString("displayMode", null), rawWidth, rawHeight, lowResolution)
            val size = LegacyDisplayPolicy.resolve(mode, dm.widthPixels, dm.heightPixels,
                rawWidth ?: if (lowResolution) 800 else 1024, rawHeight ?: if (lowResolution) 480 else 600)
            return ReceiverSettings(
                size.width, size.height,
                AirPlayDisplaySettings.sanitizeFps(p.getInt("fps", 30)), if (mode == LegacyDisplayPolicy.MANUAL) CarPlayDisplayScale.sanitize(p.getInt("scale", 10)) else 10,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(p.getInt("physicalMm", 200)), p.getBoolean("physicalHeight", false),
                p.getInt("inset", 0).coerceIn(0, 120), MediaAudioBuffer.sanitize(p.getInt("bufferMillis", 300)),
                p.getInt("volume", 100).coerceIn(0, 100), p.getBoolean("navigationDuck", false), p.getBoolean("audioFocus", true),
                p.getString("networkMode", "saved")!!.takeIf { it in listOf("saved", "app", "direct", "router") } ?: "saved",
                p.getString("language", "zh")!!.takeIf { it in listOf("system", "zh", "en", "ar", "es", "ru") } ?: "en",
                p.getBoolean("hevc", false), p.getBoolean("opus", false), p.getBoolean("reconnect", true),
                p.getBoolean("bootConnect", false), p.getBoolean("background", true),
                p.getBoolean("microphonePcm", true), p.getBoolean("vehicleGps", true),
                p.getInt("insetTop",-1),p.getInt("insetBottom",-1),p.getInt("insetLeft",-1),p.getInt("insetRight",-1),
                p.getBoolean("drawOutside",true),p.getBoolean("rightHandDrive",false),
                p.getBoolean("fullScreen",true),AudioOverridePolicy.sanitize(p.getInt("mediaChannel",0)),AudioOverridePolicy.sanitize(p.getInt("navigationChannel",0)),
                mode, dm.widthPixels, dm.heightPixels, hardware.widthPixels, hardware.heightPixels)
        }
    }
}

object CodecCapabilities {
    fun names(mime: String, encoder: Boolean = false): List<String> = try {
        val raw = (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }
            .filter { it.isEncoder == encoder && it.supportedTypes.any { type -> type.equals(mime, true) } }.map { it.name }
        // On the i.MX6 head unit the Freescale VPU H.264 decoder must win over the Google software
        // decoder; software H.264 on the dual A9 drops frames. Order hardware first, software last.
        if (mime.startsWith("video/")) raw.sortedBy { codecScore(it) } else raw
    } catch (_: Exception) { emptyList() }
    private fun codecScore(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("freescale") -> 0
            n.contains(".hw") || n.contains("hw-based") || n.contains("hardware") -> 1
            n.startsWith("omx.") && !n.contains("google") && !n.contains(".sw") && !n.contains("soft") -> 2
            n.contains("google") || n.contains(".sw") || n.contains("soft") -> 9
            else -> 5
        }
    }
    fun hevc() = names("video/hevc").isNotEmpty()
    fun opus() = names("audio/opus").isNotEmpty() && names("audio/opus", true).isNotEmpty()
}

/** Mirrors upstream focus arbitration; navigation ducking is an explicit optional extension. */
class LegacyAudioCoordinator(context: Context?, private val settings: ReceiverSettings, private val log: (String)->Unit) {
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    private val diagnosticId = AudioFocusDiagnostics.create(settings.audioFocus, settings.navigationDuck, settings.volume)
    private val tracks = LinkedHashMap<android.media.AudioTrack, com.shilapi.xcertplay.media.AudioChannel>()
    private var focus: com.shilapi.xcertplay.media.AudioChannel? = null
    private var duck = false
    private val listener = android.media.AudioManager.OnAudioFocusChangeListener { change -> synchronized(this) {
        AudioFocusDiagnostics.change(diagnosticId, change)
        if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) duck = false
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) duck = true
        volumes(); log("Audio focus change=$change tracks=${tracks.size}")
    } }
    @Synchronized internal fun acquire(track: android.media.AudioTrack, channel: com.shilapi.xcertplay.media.AudioChannel) {
        AudioFocusDiagnostics.tracks(diagnosticId, channel.name, true)
        tracks[track] = channel; refresh(); volumes()
    }
    @Synchronized fun release(track: android.media.AudioTrack) {
        tracks.remove(track)?.let { AudioFocusDiagnostics.tracks(diagnosticId, it.name, false) }
        refresh(); volumes()
    }
    private fun refresh() {
        val next = tracks.values.filter { it != com.shilapi.xcertplay.media.AudioChannel.NAVIGATION }.maxByOrNull {
            when(it) { com.shilapi.xcertplay.media.AudioChannel.MEDIA -> 3; com.shilapi.xcertplay.media.AudioChannel.PHONE -> 2; else -> 1 }
        }
        if (next == focus) return
        if (focus != null) {
            try { AudioFocusDiagnostics.abandon(diagnosticId, manager?.abandonAudioFocus(listener)) }
            catch (error: Exception) { AudioFocusDiagnostics.abandon(diagnosticId, null, error); throw error }
        }
        focus = next
        if (next == null || manager == null || !settings.audioFocus) return
        val gain = when(next) {
            com.shilapi.xcertplay.media.AudioChannel.MEDIA -> android.media.AudioManager.AUDIOFOCUS_GAIN
            com.shilapi.xcertplay.media.AudioChannel.PHONE -> android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val result = try { manager.requestAudioFocus(listener, android.media.AudioManager.STREAM_MUSIC, gain) }
            catch (error: Exception) { AudioFocusDiagnostics.request(diagnosticId, next.name, gain, null, error); throw error }
        AudioFocusDiagnostics.request(diagnosticId, next.name, gain, result)
        log("Audio focus channel=$next result=$result")
    }
    private fun volumes() {
        val navigation = settings.navigationDuck && tracks.values.any { it == com.shilapi.xcertplay.media.AudioChannel.NAVIGATION }
        AudioFocusDiagnostics.state(diagnosticId, tracks.values.map { it.name }, focus?.name, duck, navigation, settings.volume)
        tracks.forEach { (track, channel) ->
            val volume = settings.volume / 100f * if (duck || navigation && channel == com.shilapi.xcertplay.media.AudioChannel.MEDIA) 0.2f else 1f
            runCatching { track.setStereoVolume(volume, volume) }
                .onSuccess { AudioFocusDiagnostics.volume(diagnosticId, channel.name, volume, it) }
                .onFailure { AudioFocusDiagnostics.volume(diagnosticId, channel.name, volume, null, it) }
        }
    }
}
