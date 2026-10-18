package local.airuize.receiver

/** Never repeats old music. Only short, explicit RTP timestamp gaps receive bounded silence/fades. */
internal class PcmGapRepair(private val sampleRate: Int, private val channels: Int) {
    data class Result(val missingFrames: Long, val concealedFrames: Int, val discontinuity: Boolean,
        val silence: ByteArray?, val pcm: ByteArray, val budgetLimited: Boolean = false)
    private var expected: Long? = null
    private var lastSsrc: Int? = null
    private var last = IntArray(channels.coerceIn(1, 2))
    private data class Repair(val endedAtFrame: Long, val frames: Int)
    private val recent = java.util.ArrayDeque<Repair>()
    private var repairedInWindow = 0
    private var mediaPosition = 0L

    fun process(sample: Int, pcmLittleEndian: ByteArray, ssrc: Int = 0): Result {
        if (lastSsrc != null && lastSsrc != ssrc) {
            expected = null; last.fill(0); recent.clear(); repairedInWindow = 0; mediaPosition = 0
        }
        lastSsrc = ssrc
        val frameBytes = channels.coerceIn(1, 2) * 2
        require(pcmLittleEndian.size % frameBytes == 0) { "PCM payload is not frame aligned" }
        val frames = pcmLittleEndian.size / frameBytes
        val timestamp = sample.toLong() and 0xffff_ffffL
        val previous = expected
        expected = (timestamp + frames) and 0xffff_ffffL
        val distance = if (previous == null) 0L else (timestamp - previous) and 0xffff_ffffL
        val missing = if (distance in 1L..0x7fff_ffffL) distance else 0L
        // RTP media frames, rather than wall-clock delivery, define the rolling one-second budget.
        // A network burst therefore cannot earn extra padding by arriving late. Large/reversed
        // timeline changes never allocate padding; an SSRC restart explicitly resets the budget.
        mediaPosition += missing
        while (recent.isNotEmpty() && mediaPosition - recent.peekFirst().endedAtFrame >= sampleRate) {
            repairedInWindow -= recent.removeFirst().frames
        }
        val maximum = (sampleRate / 10).coerceAtLeast(1)
        val candidate = if (missing in 1L..maximum.toLong()) missing.toInt() else 0
        val budgetLimited = candidate > 0 && (repairedInWindow + candidate > maximum || recent.size >= 128)
        val repair = if (budgetLimited) 0 else candidate
        if (repair > 0) { recent.addLast(Repair(mediaPosition, repair)); repairedInWindow += repair }
        mediaPosition += frames
        val silence = if (repair > 0) ByteArray(repair * frameBytes) else null
        if (silence != null) {
            val fade = minOf(repair, (sampleRate / 500).coerceAtLeast(1))
            for (frame in 0 until fade) for (channel in last.indices) {
                put(silence, frame * frameBytes + channel * 2, last[channel] * (fade - frame - 1) / fade)
            }
            val incomingFade = minOf(frames, (sampleRate / 500).coerceAtLeast(1))
            for (frame in 0 until incomingFade) for (channel in last.indices) {
                val offset = frame * frameBytes + channel * 2
                put(pcmLittleEndian, offset, get(pcmLittleEndian, offset) * (frame + 1) / incomingFade)
            }
        }
        if (frames > 0) for (channel in last.indices) last[channel] = get(pcmLittleEndian, (frames - 1) * frameBytes + channel * 2)
        return Result(missing, repair, distance != 0L && repair == 0, silence, pcmLittleEndian, budgetLimited)
    }

    private fun get(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    private fun put(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte(); bytes[offset + 1] = (value shr 8).toByte()
    }
}
