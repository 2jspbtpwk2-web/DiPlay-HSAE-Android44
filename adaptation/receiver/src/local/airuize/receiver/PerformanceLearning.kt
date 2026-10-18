package local.airuize.receiver

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Optional metadata observer. Every setting returned here is frozen by the connection owner.
 * This object never owns a socket/decoder, starts a thread, disconnects, or edits receiver-ui.
 */
internal object PerformanceLearning {
    private var preferences: SharedPreferences? = null
    private var device = "uninitialized"
    private var deviceLabel = "未初始化"
    private var capability = "尚未观察实际解码器"
    private var base: PerformanceLearningPolicy.Profile? = null
    private var nextTier = 0
    private var stableTier = -1
    private var confirmedTier = -1
    private var previousStableTier = -1
    private var previousConfirmedTier = -1
    private var lockedTier = 0
    private var automatic = true
    private var locked = false
    private var lastDecision = "尚无足够活动样本"
    private var storageError = "none"
    private var current: Active? = null
    private var lastCompletedTier = -1
    private var lastCompletedOutput = false
    private val touchSignals=LearningTouchSignals()
    private var ignoredSamples = 0L
    private var lastSampleMs = -1L
    private var lastResult = PerformanceLearningPolicy.Reason.EXCLUDED
    private var pendingPipeline: Pair<Long, PerformanceLearningPolicy.Pipeline>? = null
    private val evidence = PerformanceEvidence()
    private val failures = PerformanceFailureEvidence()

    private data class Active(val id: Long, val applied: PerformanceLearningPolicy.Profile,
        val tier: Int, val policy: PerformanceLearningPolicy.Session,
        val baseKey: String, var stable: Boolean = false, var rejected: Boolean = false)

    /** The device key contains firmware/ABI/API/schema only, supplied by LearningDevice. */
    @Synchronized fun initialize(context: Context, deviceId: String = LearningDevice.id(),
        label: String = LearningDevice.label()) {
        val safeId = deviceId.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) } ?: "invalid-device"
        if (preferences != null && device == safeId) return
        current = null
        device = safeId
        deviceLabel = label.filter { it.isLetterOrDigit() || it in " ._-/()" }.take(160).ifEmpty { "未知设备" }
        evidence.restore("PLE1\n")
        failures.restore("PLF1\n")
        capability = "尚未观察实际解码器"
        base = null; nextTier = 0; stableTier = -1; confirmedTier = -1
        previousStableTier = -1; previousConfirmedTier = -1; lockedTier = 0
        automatic = true; locked = false; lastCompletedTier = -1; lastCompletedOutput = false
        lastDecision = "尚无足够活动样本"; storageError = "none"
        pendingPipeline = null; touchSignals.reset(SystemClock.elapsedRealtime()); lastSampleMs = -1L
        try {
            val p = context.applicationContext.getSharedPreferences("performance-learning-v1", Context.MODE_PRIVATE)
            preferences = p
            if (p.getInt("schema", 0) != PerformanceLearningPolicy.SCHEMA || p.getString("device", "") != safeId) {
                p.edit().clear().putInt("schema", PerformanceLearningPolicy.SCHEMA).putString("device", device)
                    .putString("deviceLabel", deviceLabel).apply()
            } else {
                // All persisted fields are decoded as bounded whitelist values; arbitrary preference text
                // is never copied into the diagnostic output.
                automatic = p.getBoolean("automatic", true); locked = p.getBoolean("locked", false)
                base = decodeProfile(p.getString("base", "") ?: "")
                val limit = base?.let { PerformanceLearningPolicy.profiles(it).lastIndex } ?: 0
                nextTier = p.getInt("nextTier", 0).coerceIn(0, limit.coerceAtLeast(0))
                stableTier = p.getInt("stableTier", -1).takeIf { it in 0..limit } ?: -1
                confirmedTier = p.getInt("confirmedTier", -1).takeIf { it in 0..limit } ?: -1
                previousStableTier = p.getInt("previousStableTier", -1).takeIf { it in 0..limit } ?: -1
                previousConfirmedTier = p.getInt("previousConfirmedTier", -1).takeIf { it in 0..limit } ?: -1
                lockedTier = p.getInt("lockedTier", nextTier).coerceIn(0, limit.coerceAtLeast(0))
                val codec = p.getString("decoder", "") ?: ""
                if (safeCodec(codec)) capability = "decoder=$codec nativeSelfTest=${p.getBoolean("nativeSelfTest", false)}"
                evidence.restore(p.getString("evidence", "") ?: "")
                failures.restore(p.getString("failures", "") ?: "")
            }
        } catch (error: Exception) {
            val damaged = preferences
            storageError = error.javaClass.simpleName; preferences = null
            // A partially decoded/corrupt profile must never influence the next advertisement.
            base = null; nextTier = 0; stableTier = -1; confirmedTier = -1
            previousStableTier = -1; previousConfirmedTier = -1; lockedTier = 0; locked = false
            automatic = true; capability = "尚未观察实际解码器（档案存储读取失败）"
            evidence.restore("PLE1\n"); failures.restore("PLF1\n")
            lastDecision = "存储不可用，保留内存观察；下一连接先使用界面原档"
            if (error is ClassCastException && damaged != null) {
                // A malformed optional preference is repairable independently of pairing/main UI.
                try {
                    damaged.edit().clear().putInt("schema", PerformanceLearningPolicy.SCHEMA)
                        .putString("device", device).putString("deviceLabel", deviceLabel).apply()
                    preferences = damaged
                    lastDecision = "损坏的可选性能档案已重置；使用界面原档，配对及主设置保持原值"
                } catch (_: Exception) { /* In-memory defaults remain usable if storage itself failed. */ }
            }
        }
    }

    /** Record static capability once per firmware key, after an actual decoder has been created. */
    @Synchronized fun recordCapabilities(decoderName: String, nativeSelfTest: Boolean) {
        if (!capability.startsWith("尚未") || !safeCodec(decoderName)) return
        capability = "decoder=$decoderName nativeSelfTest=$nativeSelfTest"
        persist { it.putString("decoder", decoderName).putBoolean("nativeSelfTest", nativeSelfTest) }
    }

    /** Call once before creating the connection. It cannot mutate the active session's settings. */
    @Synchronized fun resolveSettings(settings: ReceiverSettings): ReceiverSettings {
        if (settings.displayMode != LegacyDisplayPolicy.AUTO || settings.scale != 10 || settings.hevc) return settings
        val requested = PerformanceLearningPolicy.Profile(settings.width, settings.height, settings.fps)
        val choices = PerformanceLearningPolicy.profiles(requested)
        if (choices.isEmpty()) return settings
        if (base != requested) {
            base = requested; nextTier = 0; stableTier = -1; confirmedTier = -1; lockedTier = 0
            previousStableTier = -1; previousConfirmedTier = -1
            lastCompletedTier = -1; lastCompletedOutput = false
            lastDecision = "基础显示档变化，旧评分失效；从界面原档重新观察"
            saveState()
        }
        val selected = if (!automatic) 0 else if (locked) lockedTier else nextTier
        val chosen = choices[selected.coerceIn(0, choices.lastIndex)]
        return settings.copy(width = chosen.width, height = chosen.height)
    }

    /** sessionId must be the owner's generation; stale callbacks are discarded below. */
    @Synchronized fun beginSession(sessionId: Long, applied: ReceiverSettings) {
        val actual = PerformanceLearningPolicy.Profile(applied.width, applied.height, applied.fps)
        val choices = base?.let { PerformanceLearningPolicy.profiles(it) } ?: emptyList()
        val tier = if (applied.displayMode == LegacyDisplayPolicy.AUTO && applied.scale == 10 && !applied.hevc)
            choices.indexOf(actual) else -1
        current = Active(sessionId, actual, tier, PerformanceLearningPolicy.Session(SystemClock.elapsedRealtime(), tier, choices.size), base?.key() ?: "")
        lastSampleMs = -1L; pendingPipeline = null
        touchSignals.owner(sessionId,SystemClock.elapsedRealtime())
        evidence.sessionBoundary()
    }

    @Synchronized fun endSession(sessionId: Long) {
        val s = current?.takeIf { it.id == sessionId } ?: return
        val matches = s.baseKey == base?.key()
        lastCompletedTier = if (matches) s.tier else -1; lastCompletedOutput = matches && s.policy.outputSeen
        if (s.stable && !s.rejected && s.tier >= 0 && matches) {
            if (stableTier != s.tier) previousStableTier = stableTier
            stableTier = s.tier
            lastDecision = "本地阶段指标连续稳定；非实车流畅度自动证明"
        }
        current = null; pendingPipeline = null; touchSignals.owner(null,SystemClock.elapsedRealtime())
        evidence.sessionBoundary()
        saveState()
    }

    /** Only the owner may call this after checking generation; no exception message is accepted. */
    @Synchronized fun noteFailure(sessionId: Long, stage: String) {
        val active = current
        if (active != null && active.id != sessionId) return
        if (failures.add(sessionId, SystemClock.elapsedRealtime(), stage)) saveState()
    }

    /** Supply true visibility, not merely Activity existence. Calls are cheap and contain no I/O. */
    fun activity(visible:Boolean,diagnostic:Boolean,voice:Boolean) {
        touchSignals.activity(visible,diagnostic,voice,SystemClock.elapsedRealtime())
    }

    /** Count a genuine CarPlay touch/drag only; no coordinates, payloads or gesture path. */
    fun touch() {touchSignals.record(SystemClock.elapsedRealtime())}

    /** Strict, fixed VideoPipeline metadata only. It is parsed then discarded, never stored raw. */
    @Synchronized fun pipelineDiagnostic(sessionId: Long, message: String) {
        if (current?.id != sessionId) return
        val parsed = PerformanceLearningPolicy.parsePipeline(message) ?: return
        pendingPipeline = SystemClock.elapsedRealtime() to parsed
    }

    /** At most one fresh interval is accepted every four seconds. Never invoke from a per-frame loop. */
    @Synchronized fun observe(sessionId: Long, window: PerformanceLearningPolicy.Window) {
        val s = current?.takeIf { it.id == sessionId } ?: return
        val now = SystemClock.elapsedRealtime()
        if (!window.valid() || window.atMs > now + 250L || now - window.atMs > 15_000L ||
            (lastSampleMs >= 0L && window.atMs - lastSampleMs < 4_000L)) {
            ignoredSamples++; s.policy.invalidateStreak(); return
        }
        lastSampleMs = window.atMs
        val pipeline = pendingPipeline
        pendingPipeline = null // A pipeline interval can contribute to only one observation.
        val matching = pipeline?.takeIf { kotlin.math.abs(it.first - window.atMs) <= 750L &&
            kotlin.math.abs(it.second.durationMs - window.durationMs) <= 1_000L &&
            kotlin.math.abs(it.second.frames - window.receivedFrames) <= maxOf(3L, window.receivedFrames / 5L) }?.second
        val w = if (window.cryptoTotalUs == 0L && matching != null) window.copy(
            cryptoTotalUs = matching.cryptoTotalUs, cryptoMaxUs = matching.cryptoMaxUs) else window
        val interaction=touchSignals.snapshot()
        val ownerMatches=interaction.owner==s.id
        val lastTouchMs=interaction.lastTouchMs
        val environment = PerformanceLearningPolicy.Environment(ownerMatches && interaction.visible, interaction.diagnostic, interaction.voice,
            ownerMatches && interaction.changedAtMs <= w.atMs - w.durationMs,
            ownerMatches && lastTouchMs >= 0 && w.atMs >= lastTouchMs - 250L && w.atMs - lastTouchMs <= 15_000L)
        val result = s.policy.observe(w, environment)
        lastResult = result.reason
        val before = evidence.revision
        evidence.add(w, result, s.id, s.applied)
        if (result.stableTier != null && s.tier >= 0 && !s.rejected) s.stable = true
        if (result.nextTier != null && s.tier >= 0 && automatic && !locked && !s.rejected && s.baseKey == base?.key()) {
            nextTier = result.nextTier
            lastDecision = "${result.reason}连续${PerformanceLearningPolicy.BAD_WINDOWS}个活动窗口；下次连接仅降一档分辨率"
            saveState()
        } else if (evidence.revision != before) saveState()
    }

    /** Disabling returns to the user's original AUTO canvas on the next connection only. */
    @Synchronized fun setAutomatic(enabled: Boolean) { automatic = enabled; saveState() }

    /** Lock the current applied tier, not a future untested recommendation. */
    @Synchronized fun setLocked(enabled: Boolean) {
        if (enabled) lockedTier = current?.tier?.takeIf { it >= 0 }
            ?: lastCompletedTier.takeIf { it >= 0 } ?: 0
        locked = enabled; saveState()
    }

    /** Explicit user satisfaction is kept separate from automatic stage-metric stability. */
    @Synchronized fun confirmCurrent(works: Boolean): Boolean {
        val s = current
        val tier = s?.tier ?: lastCompletedTier
        val output = s?.policy?.outputSeen ?: lastCompletedOutput
        if (tier < 0 || !output || (s != null && s.baseKey != base?.key())) return false
        if (works) {
            s?.rejected = false
            if (confirmedTier != tier) previousConfirmedTier = confirmedTier
            if (stableTier != tier) previousStableTier = stableTier
            confirmedTier = tier; stableTier = tier
            lastDecision = "用户标记当前实际使用档满意；不是程序自动证明车机显示/触摸延迟"
            saveState()
        } else {
            s?.rejected = true
            // An explicit rejection must not immediately restore that same rejected tier.
            if (confirmedTier == tier) confirmedTier = previousConfirmedTier.takeIf { it != tier } ?: -1
            if (stableTier == tier) stableTier = previousStableTier.takeIf { it != tier } ?: -1
            if (previousConfirmedTier == tier) previousConfirmedTier = -1
            if (previousStableTier == tier) previousStableTier = -1
            rollback()
        }
        return true
    }

    /** Restore a confirmed/stable/original tier and lock it, all for the next connection. */
    @Synchronized fun rollback() {
        nextTier = if (confirmedTier >= 0) confirmedTier else if (stableTier >= 0) stableTier else 0
        lockedTier = nextTier; locked = true
        lastDecision = "已回退并锁定；仅下次连接生效，不自动断开当前连接"
        saveState()
    }

    /** Clear only this optional performance profile; never clear pairing or receiver-ui. */
    @Synchronized fun reset() {
        nextTier = 0; stableTier = -1; confirmedTier = -1; lockedTier = 0; locked = false
        previousStableTier = -1; previousConfirmedTier = -1
        current?.let { it.rejected = true; it.stable = false }
        lastCompletedTier = -1; lastCompletedOutput = false
        lastDecision = "性能档案已重置；当前连接不变，下次从界面原档观察"
        evidence.restore("PLE1\n")
        failures.restore("PLF1\n")
        saveState()
    }

    @Synchronized fun isAutomatic(): Boolean = automatic
    @Synchronized fun isLocked(): Boolean = locked

    /** Read-only product wording. Detailed counters stay in the report, not in the setup flow. */
    @Synchronized fun userSummary(): String {
        val choices=base?.let { PerformanceLearningPolicy.profiles(it) } ?: emptyList()
        fun name(tier:Int)=choices.getOrNull(tier)?.let { "${it.width}×${it.height}" } ?: "按显示设置"
        val s=current
        val interaction=touchSignals.snapshot()
        val next=if(!automatic) 0 else if(locked) lockedTier else nextTier
        val phase=when {
            !automatic -> "自动优化已关闭，沿用显示设置。"
            locked -> "当前档位已锁定，不再自动调整。"
            s==null -> "等待连接手机；连接后返回CarPlay操作地图或列表。"
            s.tier<0 -> "当前连接未参加自动优化；如已开启，请拔线重连后再操作。"
            nextTier!=s.tier -> "已建议新档位，下次拔线重连后使用；当前画面不变。"
            !interaction.visible || interaction.diagnostic -> "工具和诊断页面暂停评分；返回CarPlay连续操作约40秒。"
            interaction.voice -> "正在语音或通话，结束后继续观察。"
            lastResult==PerformanceLearningPolicy.Reason.NATIVE_NOT_VERIFIED -> "视频加速尚未确认，已记录信息，暂不自动降画质。"
            lastResult==PerformanceLearningPolicy.Reason.AUTH_FAILURE || lastResult==PerformanceLearningPolicy.Reason.BACKEND_FAILURE -> "发现播放链路异常，请导出问题简报。"
            lastResult==PerformanceLearningPolicy.Reason.STATIC_OR_INSUFFICIENT -> "画面变化或操作样本不足，请继续滑动地图或列表。"
            else -> "正在观察正常操作；需要连续有效样本后才会建议调整。"
        }
        val currentText=s?.applied?.let { "${it.width}×${it.height}" } ?: "未连接"
        return "当前连接画面：$currentText\n自动模式下次画质：${name(next)}\n$phase\n已观察有效片段：${s?.policy?.eligibleWindows ?: 0}；仅自动显示模式采用，拔线重连后生效。"
    }

    @Synchronized fun status(): String {
        val choices = base?.let { PerformanceLearningPolicy.profiles(it) } ?: emptyList()
        fun name(tier: Int) = choices.getOrNull(tier)?.key() ?: "无"
        val applied = current?.applied?.key() ?: "未连接"
        val selected = if (!automatic) 0 else if (locked) lockedTier else nextTier
        return "性能学习：自动选档=$automatic 手动锁定=$locked 当前=$applied 下次=${name(selected)} " +
            "本地指标稳定=${name(stableTier)} 用户确认=${name(confirmedTier)}\n$lastDecision\n" +
            "仅自动显示模式、H.264/原帧率15～30、实际前台非诊断/语音、近期触摸及充分活动视频、native正常时评分。" +
            "只在下次连接改变分辨率，不自动重连；输出释放不是屏幕FPS，发送耗时不是触摸到画面的延迟。"
    }

    @Synchronized fun snapshot(): String = status() + "\n设备档案schema=${PerformanceLearningPolicy.SCHEMA} key=$device label=$deviceLabel $capability" +
        "\n窗口元数据：lastReason=$lastResult touches=${touchSignals.totalTouches()} ignoredSamples=$ignoredSamples storageError=$storageError" +
        " eligible=${current?.policy?.eligibleWindows ?: 0} healthy=${current?.policy?.healthyWindows ?: 0} anomalies=${current?.policy?.anomalyWindows ?: 0}\n" +
        evidence.snapshot() + "\n" + failures.snapshot()

    @Synchronized fun exportText(): String = snapshot().take(40_960)

    private fun saveState() = persist { editor ->
        editor.putString("base", base?.key() ?: "").putInt("nextTier", nextTier).putInt("stableTier", stableTier)
            .putInt("confirmedTier", confirmedTier).putInt("lockedTier", lockedTier)
            .putInt("previousStableTier", previousStableTier).putInt("previousConfirmedTier", previousConfirmedTier)
            .putBoolean("automatic", automatic).putBoolean("locked", locked).putString("evidence", evidence.encode())
            .putString("failures", failures.encode())
    }

    private fun persist(write: (SharedPreferences.Editor) -> Unit) {
        try { preferences?.edit()?.let { write(it); it.apply() } }
        catch (error: Exception) { storageError = error.javaClass.simpleName }
    }

    private fun safeCodec(codec: String) = codec.length in 1..128 && codec.startsWith("OMX.") &&
        codec.all { it.isLetterOrDigit() || it in "._-" }

    private fun decodeProfile(value: String): PerformanceLearningPolicy.Profile? {
        val result = PerformanceLearningPolicy.Profile.decode(value) ?: return null
        return result.takeIf { PerformanceLearningPolicy.profiles(it).isNotEmpty() }
    }
}
