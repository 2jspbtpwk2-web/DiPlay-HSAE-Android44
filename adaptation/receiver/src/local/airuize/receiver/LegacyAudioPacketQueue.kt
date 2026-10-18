package local.airuize.receiver

import java.util.ArrayDeque

/** Bounded RTP ownership. Music uses a time budget because 64 short PCM packets are only 479 ms. */
internal class LegacyAudioPacketQueue(
    media: Boolean, private val pcm: Boolean, private val rate: Int,
    private val channels: Int, bufferMillis: Int,
) {
    data class Packet(val bytes: ByteArray, val sample: Int, val ssrc: Int, val arrivedNs: Long)
    private val lock = java.lang.Object()
    private val packets = ArrayDeque<Packet>()
    val capacityPackets = if (media) 256 else 64
    val budgetMillis = if (media) maxOf(1000, bufferMillis.coerceIn(300, 1000) + 400) else 0
    private val frameBudget = rate.toLong() * budgetMillis / 1000L
    private val byteBudget = if (media) minOf(2 * 1024 * 1024L,
        maxOf(256 * 1024L, frameBudget * channels.coerceIn(1, 2) * 2 + capacityPackets * 12L)) else 256 * 1024L
    private var bytes = 0L
    private var frames = 0L
    private var closed = false
    val size: Int get() = synchronized(lock) { packets.size }
    val queuedBytes: Long get() = synchronized(lock) { bytes }
    val queuedMillis: Long get() = synchronized(lock) {
        if (rate <= 0 || packets.isEmpty()) 0L
        else if (pcm) frames * 1000L / rate
        else (((packets.peekLast().sample.toLong() - packets.peekFirst().sample.toLong()) and 0xffff_ffffL)
            .coerceAtMost(rate.toLong() * 10L)) * 1000L / rate
    }
    data class Backlog(val queuedMillis: Long, val oldestAgeMillis: Long)
    fun backlog(nowNs: Long): Backlog = synchronized(lock) {
        Backlog(queuedMillis, if (packets.isEmpty()) 0L else ((nowNs - packets.peekFirst().arrivedNs) / 1_000_000L).coerceAtLeast(0))
    }

    /** Caller lends immutable authenticated RTP; one copy is retained only after space is available. */
    fun offer(source: ByteArray, sample: Int, ssrc: Int, arrivedNs: Long): Boolean = synchronized(lock) {
        if (closed || source.size <= 12 || source.size > 64 * 1024 || packets.size >= capacityPackets ||
            bytes + source.size > byteBudget) return@synchronized false
        val nextFrames = if (pcm) (source.size - 12).toLong() / (channels.coerceIn(1, 2) * 2) else 0L
        if (budgetMillis > 0 && pcm && frames + nextFrames > frameBudget) return@synchronized false
        if (budgetMillis > 0 && !pcm && packets.isNotEmpty()) {
            val distance = (sample.toLong() - packets.peekFirst().sample.toLong()) and 0xffff_ffffL
            if (distance > frameBudget && distance < 0x8000_0000L) return@synchronized false
        }
        packets.addLast(Packet(source.copyOf(), sample, ssrc, arrivedNs)); bytes += source.size; frames += nextFrames
        lock.notifyAll(); true
    }

    fun poll(timeoutMillis: Long): Packet? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMillis.coerceIn(0, 1000) * 1_000_000L
        while (!closed && packets.isEmpty()) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return@synchronized null
            lock.wait(left / 1_000_000L, (left % 1_000_000L).toInt())
        }
        if (packets.isEmpty()) return@synchronized null
        packets.removeFirst().also {
            bytes -= it.bytes.size
            if (pcm) frames -= (it.bytes.size - 12).toLong() / (channels.coerceIn(1, 2) * 2)
        }
    }

    fun close() = synchronized(lock) { closed = true; packets.clear(); bytes = 0; frames = 0; lock.notifyAll() }
}
