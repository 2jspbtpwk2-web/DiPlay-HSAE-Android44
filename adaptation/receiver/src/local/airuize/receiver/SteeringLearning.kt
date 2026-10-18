package local.airuize.receiver

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** One explicit, foreground wizard at a time. No Activity or unbounded ROOT process is retained. */
object SteeringLearning {
    private val active = AtomicReference<Run?>()
    private val boundaries = AtomicLong()
    @Volatile private var lastRun = "result=not-started"
    val isLearning: Boolean get() = active.get() != null
    val boundary: Long get() = boundaries.get()
    private const val PREFS = "receiver-steering-learning"
    fun begin(context: Context, callback: (SteeringLearningPolicy.Snapshot) -> Unit): Run? {
        val run = Run(callback)
        if (!active.compareAndSet(null, run)) return null
        boundaries.incrementAndGet()
        try { run.start() } catch (error: RuntimeException) { run.close(); throw error }
        return run
    }
    fun saved(context: Context): SteeringLearningPolicy.Mapping? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = LearningDevice.id()
            SteeringLearningPolicy.storedMapping(key, prefs.getString("boundDevice", null),
                prefs.getInt("$key.next", 0), prefs.getInt("$key.previous", 0))
        }.getOrNull()
    }
    /** Missing learned data keeps the established manually-enabled 0.57 mapping. A learned
     * profile from another firmware/device is never applied, even if its master switch persists. */
    fun translate(context: Context, raw: Int): Int? {
        if (isLearning || raw !in 4..5) return null
        val bound = try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("boundDevice", null) }
            catch (_: RuntimeException) { return null }
        return if (bound == null) raw else saved(context)?.translate(raw)
    }
    fun save(context: Context, run: Run, originalIneffective: Boolean, noDoubleSkip: Boolean): Boolean {
        return try {
            if (!originalIneffective || !noDoubleSkip || active.get() !== run) return false
            val mapping = run.snapshot().mapping ?: return false
            val key = LearningDevice.id()
            val written = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("boundDevice", key).putInt("$key.next", mapping.nextRaw).putInt("$key.previous", mapping.previousRaw).commit()
            if (!written) return false
            val enabled = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).edit().putBoolean("steeringMedia", true).commit()
            if(enabled) run.markSaved()
            run.close()
            enabled
        } catch (_: RuntimeException) { false }
    }
    fun restoreDefault(context: Context): Boolean = try {
        active.get()?.close()
        val key = LearningDevice.id()
        // Disable forwarding first; a failed profile removal must never claim success.
        val disabled = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).edit().putBoolean("steeringMedia", false).commit()
        val cleared = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("boundDevice").remove("$key.next").remove("$key.previous").commit()
        disabled && cleared
    } catch (_: RuntimeException) { false }
    /** Bounded capability/result metadata only; never retains an OEM event log or key trajectory. */
    fun snapshot(context: Context): String = try {
        val mapping = saved(context)
        val enabled = try {
            context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).getBoolean("steeringMedia", false).toString()
        } catch (error: RuntimeException) { "unavailable-" + error.javaClass.simpleName.take(48) }
        val state = active.get()?.snapshot()
        "SteeringLearning deviceKey=${LearningDevice.id()} learning=$isLearning stage=${state?.stage?.name ?: "IDLE"} " +
            "stageAccepted=${state?.count ?: 0}/2 savedMapping=${if(mapping == null) "none-or-incompatible" else "nextRaw=${mapping.nextRaw},previousRaw=${mapping.previousRaw}"} " +
            "steeringMedia=$enabled confirmationsRequired=true；仅本机固件映射；未证明实车切歌；无原始按键轨迹\n" +
            "SteeringLearningLast $lastRun\n" + SteeringMediaDiagnostics.snapshot()
    } catch (error: Exception) { "SteeringLearning snapshot unavailable=" + error.javaClass.simpleName.take(64) }

    class Run internal constructor(callback: (SteeringLearningPolicy.Snapshot) -> Unit) : Closeable {
        private val policy = SteeringLearningPolicy()
        private val callback = AtomicReference<((SteeringLearningPolicy.Snapshot) -> Unit)?>(callback)
        private val stopped = AtomicBoolean()
        private val source = HsaeFiniteKeySnapshot()
        private val pairs = HsaeMediaKeyPolicy()
        @Volatile private var shortPairs=0L
        @Volatile private var saved=false
        private var worker: Thread? = null
        internal fun start() {
            policy.start(SystemClock.elapsedRealtime())
            pairs.reset(this, System.currentTimeMillis())
            worker = Thread({ observe() }, "legacy-steering-learning").apply { isDaemon = true; start() }
        }
        fun snapshot(): SteeringLearningPolicy.Snapshot = policy.snapshot(SystemClock.elapsedRealtime()).also { retain(it) }
        fun statusText():String=source.statusText()+"；完整短按 $shortPairs 次"
        private fun retain(value:SteeringLearningPolicy.Snapshot) {
            if(active.get()!==this) return
            lastRun="result=${if(saved) "SAVED" else value.stage.name} stageCount=${value.count}/2 shortPairs=$shortPairs ${source.summary()} reason=${value.reason.take(120)}"
        }
        private fun publish(value: SteeringLearningPolicy.Snapshot) {
            retain(value)
            if (!stopped.get()) try { callback.get()?.invoke(value) } catch (_: Exception) { close() }
        }
        private fun collecting(): Boolean = !stopped.get() && snapshot().stage.let {
            it == SteeringLearningPolicy.Stage.NEXT || it == SteeringLearningPolicy.Stage.PREVIOUS
        }
        private fun observe() {
            try {
                if (!HsaeFiniteKeySnapshot.supported()) {
                    publish(policy.fail("本机不是已支持的HSAE按键来源；未启动ROOT读取", SystemClock.elapsedRealtime())); return
                }
                while (collecting()) {
                    publish(policy.tickDelivery(SystemClock.elapsedRealtime()))
                    if (!collecting()) break
                    source.read({ collecting() }) { event ->
                        if (!collecting()) return@read
                        val raw = pairs.accept(event, this, System.currentTimeMillis(), SystemClock.elapsedRealtime(), true, true, false) ?: return@read
                        shortPairs++
                        val before = snapshot().stage
                        val update = policy.acceptObserved(raw, SystemClock.elapsedRealtime(), System.currentTimeMillis()-event.wallMillis)
                        if (update.stage != before) pairs.reset(this, System.currentTimeMillis())
                        publish(update)
                    }
                    if (collecting()) Thread.sleep(600)
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: HsaeKeyRootChannel.Failure) {
                if(!stopped.get()) publish(policy.fail(source.statusText(), SystemClock.elapsedRealtime()))
            } catch (_: Exception) {
                if(!stopped.get()) publish(policy.fail("读取异常，请点重新学习；仍失败时导出问题报告", SystemClock.elapsedRealtime()))
            } finally {
                source.close()
                if (snapshot().stage != SteeringLearningPolicy.Stage.READY) {
                    callback.set(null); stopped.set(true)
                    if (active.compareAndSet(this, null)) boundaries.incrementAndGet()
                }
            }
        }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            callback.set(null) // Release the foreground UI callback before waking/closing any IO.
            val final=policy.snapshot(SystemClock.elapsedRealtime())
            val cancelled=policy.cancel(SystemClock.elapsedRealtime())
            retain(if(saved || final.stage==SteeringLearningPolicy.Stage.FAILED) final else cancelled)
            source.close(); worker?.interrupt()
            if (active.compareAndSet(this, null)) boundaries.incrementAndGet()
        }
        internal fun markSaved() { saved=true }
    }
}
