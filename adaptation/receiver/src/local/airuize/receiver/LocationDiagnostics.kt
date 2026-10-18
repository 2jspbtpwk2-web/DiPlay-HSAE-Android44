package local.airuize.receiver

/** Bounded metadata only. Entries retain no Context, Location, coordinates or NMEA. */
object LocationDiagnostics {
    interface Entry { fun snapshot(): String }
    private val entries = ArrayList<Entry>()
    private var sequence = 0L
    private fun add(create: (Long) -> Entry): Entry = synchronized(entries) {
        val entry = create(++sequence)
        if (entries.size == 8) entries.removeAt(0)
        entries.add(entry)
        entry
    }
    fun gps(link: String): Gps = add { Gps(it, linkLabel(link)) } as Gps
    fun can(link: String, mapped: Boolean): Can = add { Can(it, linkLabel(link), mapped) } as Can
    fun snapshot(): String {
        val current = synchronized(entries) { entries.toList() }
        return if (current.isEmpty()) "定位实例：尚未建立（不等于没有GPS硬件或手机不会订阅）"
        else current.joinToString("\n") { it.snapshot() }
    }
    /** Latest provider metadata only; never subscribes or returns a Location. */
    fun latestGpsQuality(): FeatureLearningPolicy.GpsQuality? =
        synchronized(entries) { entries.filterIsInstance<Gps>().lastOrNull() }?.quality()
    private fun linkLabel(link: String) = when (link) { "USB", "Bluetooth", "Wi-Fi" -> link; else -> "其他" }
    internal fun exception(error: Throwable): String = error.javaClass.simpleName
        .take(64).replace(Regex("[^A-Za-z0-9_$]"), "_").ifEmpty { "Exception" }
    internal fun age(now: Long, then: Long): Long = if (then >= 0 && now >= then) now - then else -1

    class Gps internal constructor(private val id: Long, private val link: String,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) : Entry {
        val registration = GpsRegistrationState()
        private var requests = 0L
        private var components = "未请求"
        private var starts = 0L
        private var startState = "未启动"
        private var running = false
        private var closed = false
        private var stops = 0L
        private var cached = 0L
        private var cachedAccepted = 0L
        private var callbacks = 0L
        private var accepted = 0L
        private var encoded = 0L
        private var canUsed = 0L
        private val rejected = LongArray(GpsDiagnostics.Rejection.values().size)
        private var latestMeta = "未采样"
        private var lastUpdateAt = -1L
        private var lastEncodeAt = -1L
        private var errors = 0L
        private var lastError = "无"
        private var accuracySamples = 0L
        private var accuracyTotal = 0.0
        private var bestAccuracy = -1f
        private var worstAccuracy = -1f
        @Synchronized fun requested(ids: Set<Int>) {
            requests++
            components = ids.filter { it in 0..65535 }.sorted().take(16).joinToString(",")
            if (ids.size > 16) components += ",省略"
        }
        @Synchronized fun started(state: StartState) {
            starts++; startState = state.name
            running = state == StartState.STARTED || state == StartState.ALREADY_RUNNING
        }
        @Synchronized fun fix(cached: Boolean, rejection: GpsDiagnostics.Rejection?, provider: String?, ageMillis: Long,
            accuracy: Float?, mock: Boolean, speed: Boolean, bearing: Boolean) {
            if (cached) this.cached++ else callbacks++
            if (rejection == null) {
                accepted++; if(cached) cachedAccepted++; lastUpdateAt = clock()
                if(accuracy != null && accuracy >= 0f && !accuracy.isNaN() && !accuracy.isInfinite()) {
                    accuracySamples++; accuracyTotal += accuracy
                    bestAccuracy = if(bestAccuracy < 0f) accuracy else minOf(bestAccuracy, accuracy)
                    worstAccuracy = maxOf(worstAccuracy, accuracy)
                }
            }
            else rejected[rejection.ordinal]++
            latestMeta = GpsDiagnostics.describe(provider, ageMillis, accuracy, mock, speed, bearing)
        }
        @Synchronized fun expired() { rejected[GpsDiagnostics.Rejection.AGE.ordinal]++ }
        @Synchronized fun encoded(can: Boolean) { encoded++; if (can) canUsed++; lastEncodeAt = clock() }
        @Synchronized fun failed(stage: FailureStage, error: Throwable) {
            errors++; lastError = stage.name + ":" + exception(error)
        }
        @Synchronized fun stopped() { stops++; running = false }
        @Synchronized fun closed() { closed = true; running = false }
        @Synchronized fun quality() = FeatureLearningPolicy.GpsQuality(id, cached+callbacks, accepted,
            rejected.sum(), errors, accuracySamples, accuracyTotal, bestAccuracy, worstAccuracy)
        @Synchronized override fun snapshot(): String = "GPS实例#$id link=$link 请求=$requests components=[$components] " +
            "启动=$starts 启动状态=$startState running=$running closed=$closed 停止=$stops " +
            "缓存检查=$cached 缓存采用=$cachedAccepted 实时回调=$callbacks 实时接受=${accepted-cachedAccepted} 接受合计=$accepted 拒绝=[" +
            GpsDiagnostics.Rejection.values().joinToString(",") { it.name + "=" + rejected[it.ordinal] } + "] " +
            "最近有效fix距今Ms=${age(clock(), lastUpdateAt)} 编码=$encoded CAN采用=$canUsed " +
            "最近编码距今Ms=${age(clock(), lastEncodeAt)} 错误=$errors 最近错误=$lastError 最近fix=[$latestMeta] " +
            registration.snapshot()+"；编码不等于iPhone采用"
    }
    enum class StartState { STARTED, ALREADY_RUNNING, CLOSED, MANAGER_MISSING, GPS_DISABLED, FAILED }
    enum class FailureStage { CACHE, START, STOP, ENCODE, PROVIDER_STATUS }

    class Can internal constructor(private val id: Long, private val link: String, private val mapped: Boolean,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) : Entry {
        val unbind = CanUnbindDiagnostics()
        private var running = false
        private var closed = false
        private var bound = false
        private var connected = false
        private var descriptor = "未查询"
        private var reads = 0L
        private var accepted = 0L
        private var rejected = 0L
        private var pascd = 0L
        private var zeroSpeed = 0L
        private var lastSampleAt = -1L
        private var lastReadMs = -1L
        private var maxReadMs = 0L
        private var lastResult = "未读取"
        private var gearKnown = false
        @Synchronized fun started() { running = true }
        @Synchronized fun binding(ok: Boolean) { bound = ok }
        @Synchronized fun connection(matches: Boolean) {
            connected = matches; descriptor = if (matches) "匹配" else "不匹配"
        }
        @Synchronized fun disconnected() { connected = false; lastSampleAt = -1L; gearKnown = false }
        @Synchronized fun read(elapsed: Long, valid: Boolean, zero: Boolean, gearMapped: Boolean) {
            reads++; lastReadMs = elapsed.coerceAtLeast(0); maxReadMs = maxOf(maxReadMs, lastReadMs)
            gearKnown = gearMapped
            if (valid) { accepted++; lastSampleAt = clock(); if (zero) zeroSpeed++; lastResult = "有效" }
            else { rejected++; lastSampleAt = -1L; lastResult = if (elapsed > 1000) "读取超时后弃用" else "无有效读数" }
        }
        @Synchronized fun failed(error: Throwable) { rejected++; lastSampleAt = -1L; lastResult = exception(error) }
        @Synchronized fun provided() { pascd++ }
        @Synchronized fun stopped(close: Boolean = false) {
            running = false; bound = false; connected = false; lastSampleAt = -1L; gearKnown = false
            if (close) closed = true
        }
        @Synchronized override fun snapshot(): String = "CAN实例#$id link=$link running=$running closed=$closed " +
            "绑定=$bound 服务连接=$connected 接口=$descriptor 挡位映射有效=$mapped 当前挡位已映射=$gearKnown " +
            "读取=$reads 有效=$accepted 拒绝=$rejected 有效零速=$zeroSpeed 最近读取Ms=$lastReadMs 最大读取Ms=$maxReadMs " +
            "最近有效样本距今Ms=${age(clock(), lastSampleAt)} 最近结果=$lastResult PASCD提供=$pascd " +
            unbind.snapshot()+"；绑定字段是本地状态；系统解绑结果见unbindApi；无CAN帧时间戳；不是GPS速度；未额外轮询OEM"
    }
}
