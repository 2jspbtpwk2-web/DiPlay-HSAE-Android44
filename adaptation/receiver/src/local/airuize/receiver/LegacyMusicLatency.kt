package local.airuize.receiver

import com.shilapi.xcertplay.media.MediaAudioBuffer

/** Legacy MEDIA-only output capacity. No PCM trimming, RTP timestamp or playback-rate changes. */
internal object LegacyMusicLatency {
    fun plan(sampleRate: Int, channels: Int, minimumBytes: Int, mediaMillis: Int): MediaAudioBuffer.Plan {
        val frameBytes = channels.coerceIn(1, 2) * 2
        val rateBytes = sampleRate.toLong() * frameBytes
        val start = maxOf(minimumBytes, 4096, (rateBytes * MediaAudioBuffer.sanitize(mediaMillis) / 1000L).toInt())
        val wanted = (rateBytes * (MediaAudioBuffer.sanitize(mediaMillis) + 200) / 1000L).toInt()
        // The original MEDIA path inherited minimum*4 from voice. On this car that was 836 ms
        // of capacity despite a 300 ms startup preference. Keep all requested startup protection.
        val capacity = maxOf(minimumBytes, 16 * 1024, wanted, start + 4096)
        return MediaAudioBuffer.Plan(((capacity + frameBytes - 1) / frameBytes) * frameBytes, start)
    }
}
