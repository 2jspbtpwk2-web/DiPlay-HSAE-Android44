package local.airuize.receiver

import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Bounded metadata only. A PCM buffer is inspected synchronously and is never retained. */
class MicrophoneDiagnostics(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val originMs = clockMs()
    private val history = ArrayList<Stream>(8)
    private var nextId = 0L
    private var omitted = 0L

    private fun elapsedMs(): Long = (clockMs() - originMs).coerceAtLeast(0L)

    @JvmOverloads @Synchronized fun begin(audioType: String, source: Int, sampleRate: Int, channels: Int,
        codec: String, frameMillis: Int, frameBytes: Int, samplesPerPacket: Int, bitrate: Int?, capture: Boolean = true): Stream {
        if (history.size == 8) { history.removeAt(0); omitted++ }
        return Stream(++nextId, AudioDiagnostics.purpose(audioType), source, sampleRate, channels,
            when (codec) { "LPCM", "OPUS", "AAC_LC" -> codec; else -> "其他" }, frameMillis,
            frameBytes, samplesPerPacket, bitrate, capture, elapsedMs(), ::elapsedMs).also { history.add(it) }
    }

    @Synchronized fun snapshot(): String {
        val now = elapsedMs()
        return "麦克风逐流证据：保留=${history.size}/8；更早流省略=$omitted；时间相对本接收器 clockOriginMs=$originMs clock=nanoTime/1000000；" +
            "仅统计PCM16电平，不保存声音；非零或UDP发送成功不证明iPhone收到或Siri识别。\n" +
            if (history.isEmpty()) "尚无实际麦克风启动请求；请结合会话开关、SETUP与首个下行音频包判断。"
            else history.joinToString("\n") { it.snapshot(now) }
    }

    enum class Phase { CREATED, SOCKET_BIND, ENCODER_OPEN, SYNTHETIC_WAIT, RECORD_BUFFER, RECORD_OPEN, RECORD_START,
        RECORD_READ, ENCODE, PACKET_SEAL, UDP_SEND, FINISHED }

    class Stream internal constructor(private val id: Long, private val purpose: String,
        private val source: Int, private val requestedRate: Int, private val requestedChannels: Int,
        private val codec: String, private val frameMillis: Int, private val frameBytes: Int,
        private val samplesPerPacket: Int, private val bitrate: Int?, private val capture: Boolean, private val createdMs: Long,
        private val elapsedMs: () -> Long) {
        private var phase = Phase.CREATED
        private var endedMs = -1L
        private var endReason = "尚未结束"
        private var requestedStop = "无"
        private var failurePhase = "无"
        private var errorType = "无"
        private var errors = 0L
        private var cleanupErrors = 0L
        private var lastCleanup = "无"
        private var cleanupErrorType = "无"
        private var networkBound = false
        private var localFamily = 0
        private var remoteFamily = 0
        private var localScope = false
        private var remoteScope = false
        private var destinationValid = false
        private var minBufferBytes = -1
        private var bufferBytes = -1
        private var recordState = -1
        private var recordingState = -1
        private var actualRate = -1
        private var actualChannels = -1
        private var sessionId = -1
        private var recordStartedMs = -1L
        private var readCalls = 0L
        private var positiveReads = 0L
        private var zeroReads = 0L
        private var negativeReads = 0L
        private var readExceptions = 0L
        private var lastReadCode = 0
        private var readBytes = 0L
        private var firstReadMs = -1L
        private var lastReadMs = -1L
        private var readMaxNs = 0L
        private var frames = 0L
        private var syntheticFrames = 0L
        private var syntheticBytes = 0L
        private var nonZeroFrames = 0L
        private var nearSilentFrames = 0L
        private var oddPcmFrames = 0L
        private var pcmSamples = 0L
        private var zeroSamples = 0L
        private var clippedSamples = 0L
        private var peak = 0
        private var squareSum = 0.0
        // Five recent monotonic second buckets; only primitive aggregates are stored.
        private val bucketSecond = LongArray(5) { Long.MIN_VALUE }
        private val bucketSamples = LongArray(5)
        private val bucketSquares = DoubleArray(5)
        private val bucketPeak = IntArray(5)
        private val bucketZero = LongArray(5)
        private val bucketClipped = LongArray(5)
        private val bucketFrames = LongArray(5)
        private val bucketQuiet = LongArray(5)
        private var encoderName = if (codec == "OPUS") "尚未打开" else "无需编码器"
        private var encoderCalls = 0L
        private var encoderTimeouts = 0L
        private var encoderUnits = 0L
        private var encoderEmpty = 0L
        private var encoderMaxNs = 0L
        private var packets = 0L
        private var sendFailures = 0L
        private var sentBytes = 0L
        private var sendMaxNs = 0L
        private var firstSendMs = -1L
        private var lastSendMs = -1L

        @Synchronized fun phase(value: Phase) { if (endedMs < 0) phase = value }
        @Synchronized fun socketBound(localFamily: Int, remoteFamily: Int, localScoped: Boolean,
            remoteScoped: Boolean, destinationValid: Boolean) {
            networkBound = true; this.localFamily = localFamily; this.remoteFamily = remoteFamily
            localScope = localScoped; remoteScope = remoteScoped; this.destinationValid = destinationValid
        }
        @Synchronized fun encoderOpened(name: String) { encoderName = safeType(name) }
        @Synchronized fun encoderInputTimeout() { encoderTimeouts++ }
        @Synchronized fun encoded(units: Int, elapsedNs: Long) {
            encoderCalls++; encoderUnits += units; if (units == 0) encoderEmpty++
            encoderMaxNs = maxOf(encoderMaxNs, elapsedNs.coerceAtLeast(0L))
        }
        @Synchronized fun buffer(minimum: Int, chosen: Int) { minBufferBytes = minimum; bufferBytes = chosen }
        @Synchronized fun recordOpened(state: Int, sampleRate: Int, channels: Int, session: Int) {
            recordState = state; actualRate = sampleRate; actualChannels = channels; sessionId = session
        }
        @Synchronized fun recordStarted(state: Int) {
            recordingState = state; if (state == 3 && recordStartedMs < 0) recordStartedMs = elapsedMs()
        }
        @Synchronized fun recordingState(state: Int) { recordingState = state }
        @Synchronized fun syntheticFrame(bytes: Int) { syntheticFrames++; syntheticBytes += bytes.coerceAtLeast(0) }
        @Synchronized fun read(result: Int, elapsedNs: Long) {
            readCalls++; lastReadCode = result; readMaxNs = maxOf(readMaxNs, elapsedNs.coerceAtLeast(0L))
            when {
                result > 0 -> {
                    positiveReads++; readBytes += result
                    val now = elapsedMs(); if (firstReadMs < 0) firstReadMs = now; lastReadMs = now
                }
                result == 0 -> zeroReads++
                else -> negativeReads++
            }
        }
        @Synchronized fun readException(elapsedNs: Long) {
            readCalls++; readExceptions++; lastReadCode = Int.MIN_VALUE
            readMaxNs = maxOf(readMaxNs, elapsedNs.coerceAtLeast(0L))
        }

        /** Complete little-endian PCM16 frame; returns nonzero without a second payload scan. */
        @Synchronized fun pcm(pcm: ByteArray): Boolean {
            var framePeak = 0; var samples = 0L; var zeros = 0L; var clipped = 0L; var squares = 0.0
            var offset = 0
            while (offset + 1 < pcm.size) {
                val sample = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
                val magnitude = if (sample < 0) -sample else sample
                framePeak = maxOf(framePeak, magnitude); samples++
                if (sample == 0) zeros++
                if (magnitude >= 32767) clipped++
                squares += sample.toDouble() * sample
                offset += 2
            }
            frames++; if (framePeak > 0) nonZeroFrames++
            // <=32/32768, about -60 dBFS peak. This is a numeric threshold, not speech detection.
            val quiet = framePeak <= 32
            if (quiet) nearSilentFrames++
            if (pcm.size and 1 != 0) oddPcmFrames++
            pcmSamples += samples; zeroSamples += zeros; clippedSamples += clipped
            squareSum += squares; peak = maxOf(peak, framePeak)
            val second = elapsedMs() / 1000L; val slot = (second % 5).toInt()
            if (bucketSecond[slot] != second) {
                bucketSecond[slot] = second; bucketSamples[slot] = 0; bucketSquares[slot] = 0.0
                bucketPeak[slot] = 0; bucketZero[slot] = 0; bucketClipped[slot] = 0
                bucketFrames[slot] = 0; bucketQuiet[slot] = 0
            }
            bucketSamples[slot] += samples; bucketSquares[slot] += squares
            bucketPeak[slot] = maxOf(bucketPeak[slot], framePeak); bucketZero[slot] += zeros
            bucketClipped[slot] += clipped; bucketFrames[slot]++; if (quiet) bucketQuiet[slot]++
            return framePeak > 0
        }
        @Synchronized fun sent(bytes: Int, elapsedNs: Long) {
            packets++; sentBytes += bytes; sendMaxNs = maxOf(sendMaxNs, elapsedNs.coerceAtLeast(0L))
            val now = elapsedMs(); if (firstSendMs < 0) firstSendMs = now; lastSendMs = now
        }
        @Synchronized fun sendFailed(elapsedNs: Long) {
            sendFailures++; sendMaxNs = maxOf(sendMaxNs, elapsedNs.coerceAtLeast(0L))
        }
        @Synchronized fun failure(error: Throwable) {
            errors++; failurePhase = phase.name; errorType = safeType(error.javaClass.simpleName)
        }
        @Synchronized fun cleanupFailed(operation: String, error: Throwable) {
            cleanupErrors++
            lastCleanup = when (operation) { "record-stop", "record-release", "socket-close", "encoder-close" -> operation; else -> "其他" }
            cleanupErrorType = safeType(error.javaClass.simpleName)
        }
        @Synchronized fun closeRequested(reason: String) {
            if (requestedStop == "无") requestedStop = safeReason(reason)
        }
        @Synchronized fun finish(reason: String) {
            if (endedMs >= 0) return
            endedMs = elapsedMs(); endReason = safeReason(reason); phase = Phase.FINISHED
        }

        @Synchronized internal fun snapshot(now: Long): String {
            val second = now / 1000L
            var recentSamples = 0L; var recentSquares = 0.0; var recentPeak = 0
            var recentZeros = 0L; var recentClipped = 0L; var recentFrames = 0L; var recentQuiet = 0L
            for (slot in 0 until 5) {
                if (bucketSecond[slot] == Long.MIN_VALUE || second - bucketSecond[slot] !in 0L..4L) continue
                recentSamples += bucketSamples[slot]; recentSquares += bucketSquares[slot]
                recentPeak = maxOf(recentPeak, bucketPeak[slot]); recentZeros += bucketZero[slot]
                recentClipped += bucketClipped[slot]; recentFrames += bucketFrames[slot]; recentQuiet += bucketQuiet[slot]
            }
            return "麦克风流#$id：用途=$purpose；阶段=$phase；建立=${time(createdMs)}；录音开始=${time(recordStartedMs)}；结束=${time(endedMs)}；结束原因=$endReason；停止请求=$requestedStop；" +
                "capture=$capture syntheticSilence=${!capture}；source=$source；协商=$codec/${requestedRate}Hz/${requestedChannels}ch；frameMs=$frameMillis；frameBytes=$frameBytes；samplesPerPacket=$samplesPerPacket；bitrate=${bitrate ?: -1}；" +
                "UDP已bind=$networkBound；本地族=$localFamily/含scope=$localScope；对端族=$remoteFamily/含scope=$remoteScope；目标端口有效=$destinationValid；" +
                "minBuffer=$minBufferBytes；buffer=$bufferBytes；AudioRecord.state=$recordState；recordingState=$recordingState；实际=${actualRate}Hz/${actualChannels}ch；session=$sessionId；" +
                "read正/零/负=$positiveReads/$zeroReads/$negativeReads；read抛异常=$readExceptions；readCalls=$readCalls；lastReadCode=$lastReadCode(-2147483648=抛异常)；readBytes=$readBytes；readMaxUs=${readMaxNs / 1000L}；首读取=${time(firstReadMs)}；末读取=${time(lastReadMs)}；" +
                "合成静音帧=$syntheticFrames/$syntheticBytes bytes（不计为录音）；PCM帧=$frames；非零帧=$nonZeroFrames；近静音帧(peak<=32)=$nearSilentFrames；奇数字节帧=$oddPcmFrames；" +
                level("累计电平", pcmSamples, squareSum, peak, zeroSamples, clippedSamples) + "；" +
                level("近5个单调秒桶", recentSamples, recentSquares, recentPeak, recentZeros, recentClipped) + "；近窗口帧/近静音=$recentFrames/$recentQuiet；" +
                "编码器=$encoderName；编码调用=$encoderCalls；输入超时=$encoderTimeouts；编码单元=$encoderUnits；空输出=$encoderEmpty；编码MaxUs=${encoderMaxNs / 1000L}；" +
                "UDP发送=$packets/$sentBytes bytes；send失败=$sendFailures；sendMaxUs=${sendMaxNs / 1000L}；首发送=${time(firstSendMs)}；末发送=${time(lastSendMs)}；末发送年龄Ms=${if (lastSendMs < 0) -1 else (now - lastSendMs).coerceAtLeast(0L)}；" +
                "错误=$errors；错误阶段=$failurePhase；错误类型=$errorType；清理错误=$cleanupErrors；末清理操作=$lastCleanup；清理错误类型=$cleanupErrorType"
        }

        private fun time(value: Long): String = if (value < 0) "未发生" else "+${value}ms"
        private fun level(label: String, samples: Long, squares: Double, peak: Int, zero: Long, clipped: Long): String {
            if (samples <= 0) return "$label=未采到完整PCM16样本"
            val rms = sqrt(squares / samples)
            val db = if (rms == 0.0) "负无穷" else String.format(Locale.US, "%.1f", 20.0 * log10(rms / 32768.0))
            return "$label: samples=$samples peak=$peak rms=${String.format(Locale.US, "%.1f", rms)} rmsDbFS=$db zero=$zero clip=$clipped"
        }
        private fun safeType(value: String): String = value.take(100).map { if (it.isLetterOrDigit() || it in "._-$") it else '_' }.joinToString("")
        private fun safeReason(value: String): String = when (value) {
            "replaced", "stream-stop", "receiver-close", "close", "stopped", "completed", "error", "opus-unavailable" -> value
            else -> "其他"
        }
    }
}
