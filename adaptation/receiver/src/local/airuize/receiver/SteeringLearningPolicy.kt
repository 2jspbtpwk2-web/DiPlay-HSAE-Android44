package local.airuize.receiver

/** Only complete, known HSAE short-press pairs enter this policy; no OEM payload is retained. */
class SteeringLearningPolicy(private val stageMillis: Long = 25_000L) {
    enum class Stage { IDLE, NEXT, PREVIOUS, READY, FAILED, CANCELLED }
    data class Mapping(val nextRaw: Int, val previousRaw: Int) {
        fun valid() = nextRaw in 4..5 && previousRaw in 4..5 && nextRaw != previousRaw
        fun translate(raw: Int): Int? = if (!valid()) null else when (raw) { nextRaw -> 4; previousRaw -> 5; else -> null }
    }
    data class Snapshot(val stage: Stage, val count: Int, val remainingMillis: Long, val mapping: Mapping?, val reason: String)
    companion object {
        fun storedMapping(currentDevice: String, boundDevice: String?, next: Int, previous: Int): Mapping? =
            if (currentDevice.isNotBlank() && currentDevice == boundDevice) Mapping(next, previous).takeIf { it.valid() } else null
    }
    private var stage = Stage.IDLE
    private var nextRaw = 0
    private var previousRaw = 0
    private var count = 0
    private var deadline = 0L
    private var stageBegan = 0L
    private var lastPress = Long.MIN_VALUE
    private var reason = ""

    @Synchronized fun start(nowMillis: Long): Snapshot {
        require(stageMillis in 5_000L..120_000L)
        stage = Stage.NEXT; nextRaw = 0; previousRaw = 0; count = 0
        stageBegan=nowMillis;deadline = nowMillis + stageMillis; lastPress = Long.MIN_VALUE; reason = ""
        return snapshot(nowMillis)
    }
    @Synchronized fun accept(raw: Int, nowMillis: Long): Snapshot {
        tick(nowMillis)
        return acceptObserved(raw,nowMillis,0L)
    }
    /** Only the finite reader calls this with a previously freshness-checked event age.
     * Deadlines remain monotonic; batched physical presses retain their real spacing. */
    @Synchronized fun acceptObserved(raw: Int, nowMillis: Long, eventAgeMillis: Long): Snapshot {
        tickDelivery(nowMillis)
        if (stage != Stage.NEXT && stage != Stage.PREVIOUS || raw !in 4..5) return snapshot(nowMillis)
        if (eventAgeMillis !in 0L..1800L) return snapshot(nowMillis)
        val pressedAt = nowMillis - eventAgeMillis
        if(pressedAt<stageBegan || pressedAt>=deadline) return snapshot(nowMillis)
        if (lastPress != Long.MIN_VALUE && pressedAt - lastPress < 350L) return snapshot(nowMillis)
        lastPress = pressedAt
        val previous = if (stage == Stage.NEXT) nextRaw else previousRaw
        if (previous != 0 && previous != raw) return fail("同一步骤的两次信号不同，请重学", nowMillis)
        if (stage == Stage.PREVIOUS && raw == nextRaw) return fail("两个方向收到相同信号，请重学", nowMillis)
        if (stage == Stage.NEXT) nextRaw = raw else previousRaw = raw
        count++
        if (count == 2) {
            if (stage == Stage.NEXT) { stage = Stage.PREVIOUS; count = 0; stageBegan=nowMillis;deadline = nowMillis + stageMillis; lastPress = Long.MIN_VALUE }
            else stage = Stage.READY
        }
        return snapshot(nowMillis)
    }
    @Synchronized fun tick(nowMillis: Long): Snapshot {
        if ((stage == Stage.NEXT || stage == Stage.PREVIOUS) && nowMillis >= deadline)
            return fail("本步未收到两次短按。请看下方读取状态，再点重新学习", nowMillis)
        return snapshot(nowMillis)
    }
    /** The finite reader may deliver a fresh physical press shortly after its deadline.
     * The event itself must still be before that deadline; the grace is never extended. */
    @Synchronized fun tickDelivery(nowMillis:Long):Snapshot {
        if((stage==Stage.NEXT || stage==Stage.PREVIOUS) && nowMillis>=deadline+1800L)
            return fail("本步未收到两次短按。请看下方读取状态，再点重新学习",nowMillis)
        return snapshot(nowMillis)
    }
    @Synchronized fun fail(message: String, nowMillis: Long): Snapshot {
        stage = Stage.FAILED; reason = message.take(120); return snapshot(nowMillis)
    }
    @Synchronized fun cancel(nowMillis: Long): Snapshot { stage = Stage.CANCELLED; reason = "已取消，未保存"; return snapshot(nowMillis) }
    @Synchronized fun snapshot(nowMillis: Long): Snapshot = Snapshot(stage, count,
        if (stage == Stage.NEXT || stage == Stage.PREVIOUS) (deadline - nowMillis).coerceAtLeast(0) else 0,
        if (stage == Stage.READY) Mapping(nextRaw, previousRaw).takeIf { it.valid() } else null, reason)
}
