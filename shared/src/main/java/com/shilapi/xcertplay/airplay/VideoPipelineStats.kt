package com.shilapi.xcertplay.airplay

/**
 * Bounded receive-thread aggregates. Empty protocol messages and configuration records are
 * counted separately from nonempty video frames; socket wait is not called decoder latency.
 * No packet contents, endpoint addresses, header values, or media timestamps are retained.
 */
internal class VideoPipelineStats(
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    enum class Kind { FRAME, CONFIG, OTHER }

    private var windowStart = nowNs()
    private var readStarted = windowStart
    private var messageReceived = windowStart
    private var messages = 0L
    private var frames = 0L
    private var frameBytes = 0L
    private var configs = 0L
    private var emptyMessages = 0L
    private var emptyFrames = 0L
    private var otherMessages = 0L
    private var maxReadNs = 0L
    private var lastFrameNs: Long? = null
    private var maxFrameGapNs = 0L
    private var cryptoTotalNs = 0L
    private var cryptoMaxNs = 0L
    private var annexBTotalNs = 0L
    private var annexBMaxNs = 0L
    private var handoffTotalNs = 0L
    private var handoffMaxNs = 0L

    fun reading() { readStarted = nowNs() }

    fun received(kind: Kind, bodyBytes: Int) {
        messageReceived = nowNs()
        maxReadNs = maxOf(maxReadNs, (messageReceived - readStarted).coerceAtLeast(0L))
        messages++
        if (bodyBytes == 0) emptyMessages++
        when (kind) {
            Kind.CONFIG -> configs++
            Kind.OTHER -> otherMessages++
            Kind.FRAME -> Unit
        }
    }

    /** Called after one frame has completed decryption, conversion and the sink callback. */
    fun frame(payloadBytes: Int, cryptoNs: Long, annexBNs: Long, handoffNs: Long) {
        if (payloadBytes > 0) {
            frames++
            frameBytes += payloadBytes.toLong()
            lastFrameNs?.let { maxFrameGapNs = maxOf(maxFrameGapNs, (messageReceived - it).coerceAtLeast(0L)) }
            lastFrameNs = messageReceived
        } else emptyFrames++
        val crypto = cryptoNs.coerceAtLeast(0L)
        val annexB = annexBNs.coerceAtLeast(0L)
        val handoff = handoffNs.coerceAtLeast(0L)
        cryptoTotalNs += crypto; cryptoMaxNs = maxOf(cryptoMaxNs, crypto)
        annexBTotalNs += annexB; annexBMaxNs = maxOf(annexBMaxNs, annexB)
        handoffTotalNs += handoff; handoffMaxNs = maxOf(handoffMaxNs, handoff)
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        val elapsedNs = (now - windowStart).coerceAtLeast(0L)
        if (!ended && elapsedNs < WINDOW_NS) return
        val windowMs = (elapsedNs / 1_000_000L).coerceAtLeast(1L)
        val lastFrameAgeMs = lastFrameNs?.let { (now - it).coerceAtLeast(0L) / 1_000_000L }
        runCatching {
            report("VideoPipeline windowMs=$windowMs messages=$messages frames=$frames frameBytes=$frameBytes " +
                "configs=$configs emptyMessages=$emptyMessages emptyFrames=$emptyFrames otherMessages=$otherMessages " +
                "fpsMilli=${frames * 1_000_000L / windowMs} readMaxMs=${maxReadNs / 1_000_000L} " +
                "frameGapMaxMs=${maxFrameGapNs / 1_000_000L} lastFrameAgeMs=${lastFrameAgeMs ?: "none"} " +
                "cryptoTotalUs=${cryptoTotalNs / 1000L} cryptoMaxUs=${cryptoMaxNs / 1000L} " +
                "annexBTotalUs=${annexBTotalNs / 1000L} annexBMaxUs=${annexBMaxNs / 1000L} " +
                "handoffTotalUs=${handoffTotalNs / 1000L} handoffMaxUs=${handoffMaxNs / 1000L} ended=$ended")
        }
        windowStart = now
        messages = 0L; frames = 0L; frameBytes = 0L; configs = 0L
        emptyMessages = 0L; emptyFrames = 0L; otherMessages = 0L
        maxReadNs = 0L; maxFrameGapNs = 0L
        cryptoTotalNs = 0L; cryptoMaxNs = 0L
        annexBTotalNs = 0L; annexBMaxNs = 0L
        handoffTotalNs = 0L; handoffMaxNs = 0L
    }

    private companion object { const val WINDOW_NS = 5_000_000_000L }
}
