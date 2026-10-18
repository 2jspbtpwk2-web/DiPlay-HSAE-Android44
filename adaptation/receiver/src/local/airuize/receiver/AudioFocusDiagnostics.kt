package local.airuize.receiver

import java.util.ArrayDeque
import java.util.Locale

/** Numeric observations only; no AudioTrack, Context, media title or phone identifier is retained. */
internal class AudioFocusHistory(
    private val eventLimit: Int = 96,
    private val coordinatorLimit: Int = 8,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(eventLimit in 1..256 && coordinatorLimit in 1..32) }
    private val originMs = nowMs()
    private var nextId = 0L
    private val events = ArrayDeque<String>()
    private val states = LinkedHashMap<Long, String>()
    private var omitted = 0L
    private var requests = 0L
    private var granted = 0L
    private var requestErrors = 0L
    private var abandons = 0L
    private var abandonErrors = 0L
    private var focusChanges = 0L
    private var volumeCalls = 0L
    private var volumeErrors = 0L
    private var addedTracks = 0L
    private var removedTracks = 0L

    private fun label(value: String?): String = when (value?.toUpperCase(Locale.US)) {
        "MEDIA" -> "MEDIA"
        "PHONE" -> "PHONE"
        "ASSISTANT" -> "ASSISTANT"
        "NAVIGATION" -> "NAVIGATION"
        null -> "NONE"
        else -> "OTHER"
    }
    private fun errorLabel(value: Throwable?): String = value?.javaClass?.simpleName?.take(64) ?: "none"
    private fun add(id: Long, text: String) {
        events.addLast("+${(nowMs() - originMs).coerceAtLeast(0L)}ms coordinator=$id $text")
        while (events.size > eventLimit) { events.removeFirst(); omitted++ }
    }
    @Synchronized fun create(enabled: Boolean, navigationDuck: Boolean, volume: Int): Long {
        val id = ++nextId
        states[id] = "awaiting-tracks"
        while (states.size > coordinatorLimit) states.remove(states.keys.first())
        add(id, "created focusEnabled=$enabled navigationDuckEnabled=$navigationDuck volumePercent=${volume.coerceIn(0, 100)}")
        return id
    }
    @Synchronized fun tracks(id: Long, channel: String, added: Boolean) {
        if (added) addedTracks++ else removedTracks++
        add(id, "track-${if (added) "added" else "removed"} channel=${label(channel)}")
    }
    @Synchronized fun state(id: Long, channels: List<String>, focus: String?, duck: Boolean,
        navigation: Boolean, volume: Int) {
        if (!states.containsKey(id)) return
        val counts = listOf("MEDIA", "PHONE", "ASSISTANT", "NAVIGATION").joinToString(",") { kind ->
            "$kind=${channels.count { label(it) == kind }}"
        }
        states[id] = "tracks=${channels.size} channels={$counts} focus=${label(focus)} " +
            "focusDuck=$duck navigationDuck=$navigation volumePercent=${volume.coerceIn(0, 100)}"
    }
    @Synchronized fun request(id: Long, channel: String, gain: Int, result: Int?, error: Throwable? = null) {
        requests++
        if (result == 1 && error == null) granted++ else requestErrors++
        add(id, "focus-request channel=${label(channel)} gain=$gain result=${result ?: "unavailable"} error=${errorLabel(error)}")
    }
    @Synchronized fun abandon(id: Long, result: Int?, error: Throwable? = null) {
        abandons++
        if (result != 1 || error != null) abandonErrors++
        add(id, "focus-abandon result=${result ?: "unavailable"} error=${errorLabel(error)}")
    }
    @Synchronized fun change(id: Long, value: Int) {
        focusChanges++
        val name = when (value) { 1 -> "GAIN"; -1 -> "LOSS"; -2 -> "LOSS_TRANSIENT"; -3 -> "LOSS_TRANSIENT_CAN_DUCK"; else -> "OTHER" }
        add(id, "focus-change value=$value name=$name")
    }
    @Synchronized fun volume(id: Long, channel: String, expected: Float, result: Int?, error: Throwable? = null) {
        volumeCalls++
        if (result != 0 || error != null) volumeErrors++
        val permille = if (expected.isNaN() || expected.isInfinite()) -1 else (expected.coerceIn(0f, 1f) * 1000).toInt()
        add(id, "volume channel=${label(channel)} expectedPermille=$permille result=${result ?: "unavailable"} error=${errorLabel(error)}")
    }
    @Synchronized fun snapshot(): String = buildString {
        append("音频焦点/压音观察：clockOriginMs=$originMs clock=nanoTime/1000000 coordinatorsCreated=$nextId retained=${states.size}/$coordinatorLimit ")
        append("tracksAdded=$addedTracks tracksRemoved=$removedTracks requests=$requests granted=$granted requestFailureOrError=$requestErrors ")
        append("abandons=$abandons abandonFailureOrError=$abandonErrors focusChanges=$focusChanges volumeCalls=$volumeCalls volumeFailureOrError=$volumeErrors\n")
        append("仅记录既有焦点/压音行为；LOSS策略未改变。音量result=0只代表AudioTrack API返回成功，不证明扬声器实际音量。\n")
        states.forEach { (id, text) -> append("coordinator=$id $text\n") }
        append("recentEvents=${events.size}/$eventLimit olderEventsOmitted=$omitted\n")
        events.forEach { append(it).append('\n') }
    }
}

/** Process-wide bounded history remains available after a short Siri/phone/navigation stream stops. */
object AudioFocusDiagnostics {
    private val history = AudioFocusHistory()
    internal fun create(enabled: Boolean, navigationDuck: Boolean, volume: Int) = history.create(enabled, navigationDuck, volume)
    internal fun tracks(id: Long, channel: String, added: Boolean) = history.tracks(id, channel, added)
    internal fun state(id: Long, channels: List<String>, focus: String?, duck: Boolean, navigation: Boolean, volume: Int) =
        history.state(id, channels, focus, duck, navigation, volume)
    internal fun request(id: Long, channel: String, gain: Int, result: Int?, error: Throwable? = null) = history.request(id, channel, gain, result, error)
    internal fun abandon(id: Long, result: Int?, error: Throwable? = null) = history.abandon(id, result, error)
    internal fun change(id: Long, value: Int) = history.change(id, value)
    internal fun volume(id: Long, channel: String, expected: Float, result: Int?, error: Throwable? = null) = history.volume(id, channel, expected, result, error)
    fun snapshot(): String = history.snapshot()
}

/** A missing event is inconclusive when the user/phone never invoked that feature. */
object FeatureCoverageDiagnostics {
    fun describe(enabled: Boolean, requestedEvents: Long, progressEvents: Long, errors: Long): String = when {
        !enabled -> "未启用；本轮无法验证该功能"
        errors > 0 -> "记录到错误；请求=${requestedEvents.coerceAtLeast(0)} 进展=${progressEvents.coerceAtLeast(0)} 错误=$errors；需结合现场操作"
        progressEvents > 0 -> "有软件数据进展；请求=${requestedEvents.coerceAtLeast(0)} 进展=$progressEvents；不证明实际听感/识别或iPhone采用"
        requestedEvents > 0 -> "已请求，尚未记录数据进展；需区分未操作、设备未响应和采集受限"
        else -> "未观察到请求；本次未测试或手机未触发，不能判定功能故障"
    }
}
