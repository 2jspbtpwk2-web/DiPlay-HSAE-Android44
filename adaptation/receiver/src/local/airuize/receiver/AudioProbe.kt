package local.airuize.receiver

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Real audio-output probe: actually plays a 440->880 Hz sweep through an AudioTrack at the head
 * unit's native 44.1 kHz stereo and verifies the playback head position advances and the track
 * reaches PLAYING. Head advancing is the authoritative proof that AudioFlinger accepted and is
 * rendering the data (vs a silent underrun). It never changes volume/mode/SCO.
 */
object AudioProbe {
    private const val SAMPLE_RATE = 44100

    private fun sweep(freqStart: Double, freqEnd: Double, durationSec: Double): ByteArray {
        val frames = (SAMPLE_RATE * durationSec).toInt()
        val out = ByteArray(frames * 4) // stereo, 16-bit PCM
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / frames
            val freq = freqStart + (freqEnd - freqStart) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val v = (sin(phase) * 0.6 * 32767.0).toInt()
            val lsb = v and 255; val msb = (v shr 8) and 255
            val o = i * 4
            out[o] = lsb.toByte(); out[o + 1] = msb.toByte()
            out[o + 2] = lsb.toByte(); out[o + 3] = msb.toByte()
        }
        return out
    }

    fun run(context: Context, log: (String) -> Unit): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        log("音频出声检测开始：44100Hz 立体声 PCM16；mode=${am.mode} music音量=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}（不改音量/mode/SCO）")
        val data = sweep(440.0, 880.0, 1.5)
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(if (minBuf > 0) minBuf else 8192, SAMPLE_RATE * 4) // >= 1 s stereo
        var track: AudioTrack? = null
        try {
            track = AudioTrack(
                AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM
            )
            log("AudioTrack建立：state=${track.state}(1=INITIALIZED) minBuf=$minBuf bufSize=$bufSize")
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                log("音频检测失败：AudioTrack 未初始化（AudioFlinger 拒绝建轨，可能句柄/内存不足 status -12）")
                return false
            }
            track.play()
            val h0 = track.playbackHeadPosition
            val s0 = track.playState
            var written = 0
            while (written < data.size) {
                val w = track.write(data, written, minOf(data.size - written, 16384))
                if (w <= 0) break
                written += w
            }
            Thread.sleep(1200) // let the tail render so the head advances
            val h1 = track.playbackHeadPosition
            val s1 = track.playState
            val advanced = h1 > h0
            val expectedFrames = data.size / 4
            log("音频检测：播放前 playState=$s0 head=$h0；播放后 playState=$s1(3=PLAYING) head=$h1；写入字节=$written/${data.size}；期望帧≈$expectedFrames 实际推进=${h1 - h0}")
            val ok = s1 == AudioTrack.PLAYSTATE_PLAYING && advanced
            log(
                if (ok) "音频检测结论：软件写入和播放头已推进（$h0→$h1），不证明扬声器实际出声。应听到升调提示音；若无声，仍需检查OEM音源路由、音量、功放及静音状态，不能据此判定硬件故障。"
                else "音频检测结论：异常（播放头未推进或未进入PLAYING，属 AudioFlinger/underrun/路由问题）"
            )
            runCatching { track.stop() }
            return ok
        } catch (t: Throwable) {
            log("音频检测异常(已拦截)：${t.javaClass.simpleName}:${t.message}")
            return false
        } finally {
            runCatching { track?.release() }
        }
    }
}
