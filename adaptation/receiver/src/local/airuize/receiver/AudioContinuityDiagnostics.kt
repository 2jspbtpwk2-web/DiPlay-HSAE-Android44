package local.airuize.receiver

/** Numeric, bounded music evidence. It cannot establish speaker routing or an audible underrun. */
internal class AudioContinuityDiagnostics(private val rate: Int, private val frameBytes: Int) {
    private var sequence: Int? = null
    private var lastSsrc: Int? = null
    private val retiredSources = java.util.ArrayDeque<Int>()
    private var ssrcRestarts = 0L
    private var expectedSample: Long? = null
    private var lastArrivalNs = Long.MIN_VALUE
    private var windowStartNs = Long.MIN_VALUE
    private var totalPackets = 0L
    private var sourceMissing = 0L
    private var timestampMissing = 0L
    private var late = 0L
    private var localDrops = 0L
    private var concealed = 0L
    private var discontinuities = 0L
    private var repairBudgetLimited = 0L
    private var estimatedUnderruns = 0L
    private var emptyPolls = 0L
    private var lastWasEmpty = false
    private var windowPackets = 0L
    private var windowMissing = 0L
    private var windowDrops = 0L
    private var windowWritten = 0L
    private var windowArrivalMaxNs = 0L
    private var windowQueueMaxNs = 0L
    private var windowWriteMaxNs = 0L
    private var windowWriteNs = 0L
    private var lastWindow = "音频区间：尚未满5秒"

    @Synchronized fun arrived(seq: Int, sample: Int, pcmFrames: Int, nowNs: Long, ssrc: Int = 0): Boolean {
        begin(nowNs)
        if (lastSsrc != null && lastSsrc != ssrc) {
            // A delayed packet from the just-retired source is not another source restart.
            if (retiredSources.contains(ssrc)) { late++; return false }
            retiredSources.addLast(lastSsrc!!)
            while (retiredSources.size > 4) retiredSources.removeFirst()
            sequence = null; expectedSample = null; ssrcRestarts++
        }
        lastSsrc = ssrc
        val before = sequence
        if (before != null) {
            val delta = (seq - before) and 0xffff
            if (delta == 0 || delta >= 0x8000) { late++; return false }
            val missing = (delta - 1).toLong(); sourceMissing += missing; windowMissing += missing
        }
        sequence = seq
        if (pcmFrames > 0) {
            val stamp = sample.toLong() and 0xffff_ffffL
            expectedSample?.let { expected ->
                val gap = (stamp - expected) and 0xffff_ffffL
                if (gap in 1L..0x7fff_ffffL) timestampMissing += gap
            }
            expectedSample = (stamp + pcmFrames) and 0xffff_ffffL
        }
        if (lastArrivalNs != Long.MIN_VALUE) windowArrivalMaxNs = maxOf(windowArrivalMaxNs, (nowNs - lastArrivalNs).coerceAtLeast(0))
        lastArrivalNs = nowNs; totalPackets++; windowPackets++
        return true
    }
    @Synchronized fun dropped() { localDrops++; windowDrops++ }
    @Synchronized fun dequeued(ageNs: Long) { windowQueueMaxNs = maxOf(windowQueueMaxNs, ageNs.coerceAtLeast(0)) }
    @Synchronized fun wrote(bytes: Int, elapsedNs: Long) {
        windowWritten += bytes; windowWriteNs += elapsedNs.coerceAtLeast(0)
        windowWriteMaxNs = maxOf(windowWriteMaxNs, elapsedNs.coerceAtLeast(0))
    }
    @Synchronized fun output(playing: Boolean, advancedFrames: Long, queuedBytes: Long) {
        val empty = playing && queuedBytes <= 0L
        if (empty && !lastWasEmpty && advancedFrames > 0) estimatedUnderruns++
        lastWasEmpty = empty
    }
    @Synchronized fun waitedEmpty() { emptyPolls++ }
    @Synchronized fun repaired(frames: Int, discontinuity: Boolean, budgetLimited: Boolean = false) {
        concealed += frames; if (discontinuity) discontinuities++
        if (budgetLimited) repairBudgetLimited++
    }

    @Synchronized fun summary(nowNs: Long = System.nanoTime()): String {
        begin(nowNs)
        val duration = (nowNs - windowStartNs).coerceAtLeast(0)
        if (duration >= 5_000_000_000L) {
            lastWindow = "音频区间：windowMs=${duration / 1_000_000L} RTP=$windowPackets sourceMissing=$windowMissing localDrops=$windowDrops " +
                "writtenBytes=$windowWritten arrivalGapMaxMs=${windowArrivalMaxNs / 1_000_000L} " +
                "queueAgeMaxMs=${windowQueueMaxNs / 1_000_000L} writeBlockMaxMs=${windowWriteMaxNs / 1_000_000L} writeWallMs=${windowWriteNs / 1_000_000L}"
            windowStartNs = nowNs; windowPackets = 0; windowMissing = 0; windowDrops = 0; windowWritten = 0
            windowArrivalMaxNs = 0; windowQueueMaxNs = 0; windowWriteMaxNs = 0; windowWriteNs = 0
        }
        return "音频连续性（本流累计）：接收=$totalPackets；接收序号缺包=$sourceMissing；迟到或重复拒绝=$late；本地队列拒绝=$localDrops；" +
            "接收PCM时间戳缺失帧=$timestampMissing；SSRC重启=$ssrcRestarts；有界静音修补帧=$concealed；未修补时间跳变=$discontinuities；" +
            "修补预算不足次数=$repairBudgetLimited；" +
            "播放头估计断粮=$estimatedUnderruns；空队列等待=$emptyPolls；rate=$rate frameBytes=$frameBytes（估计不等于实车听感）\n$lastWindow"
    }

    private fun begin(nowNs: Long) { if (windowStartNs == Long.MIN_VALUE) windowStartNs = nowNs }
}
