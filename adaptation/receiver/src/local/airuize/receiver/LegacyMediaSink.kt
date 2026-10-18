package local.airuize.receiver

import android.media.AudioTrack
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** API18 codecs are owned exclusively by their workers, including configure/stop/release. */
class LegacyMediaSink(private val log: (String) -> Unit, private val rendered: () -> Unit,
    private val videoWidth: Int = 800, private val videoHeight: Int = 480,
    private val settings: ReceiverSettings = ReceiverSettings(), context: android.content.Context? = null) : MediaSink, Closeable {
    private val surfaceState=VideoSurfaceState<Surface>()
    private val appContext=context?.applicationContext
    private val learningTelemetry=LearningTelemetry()
    private val configuration = VideoConfigState()
    @Volatile private var videoCodec = VideoCodec.H264
    private val coordinator = LegacyAudioCoordinator(context, settings, log)
    @Volatile private var recovery: (() -> Unit)? = null
    private data class QueuedVideo(val bytes:ByteArray,val receivedNs:Long)
    private val video = ArrayBlockingQueue<QueuedVideo>(16)
    private val resync = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    val diagnostics = MediaDiagnostics()
    private val microphoneNegotiation = MicrophoneNegotiationDiagnostics()
    private val recoveryExecutor = VideoRecoveryExecutor { error ->
        diagnostics.recoveryRequestErrors.incrementAndGet()
        log("关键帧请求失败：${error.javaClass.simpleName}；解码器继续运行，等待后续重试")
    }
    private val audio = HashMap<AudioStreamId, LegacyAudio>()
    @Volatile private var audioReaders: List<LegacyAudio> = emptyList()
    @Volatile private var audioChanging = false
    private val microphones = HashMap<AudioStreamId, LegacyMicrophone>()
    private val worker = Thread({ videoLoop() }, "legacy-avc").apply { isDaemon = true; start() }
    private data class Streams(val audio: List<LegacyAudio>, val microphones: Int)
    private fun streamSnapshot(): Streams = synchronized(this) { Streams(audio.values.toList(), microphones.size) }
    fun diagnosticSummary(): String = diagnosticSummaries().first
    /** Freeze only stream references under the ingress lock. Formatting must not delay RTP offers.
     * A periodic observer reuses each audio summary for the full report and the short snapshot. */
    fun diagnosticSummaries(): Pair<String, String> {
        val streams = streamSnapshot()
        val summaries = streams.audio.map { it.summary() }
        val crypto = AudioFrameCrypto.diagnosticSnapshot()
        val full = diagnostics.summary() + "\n媒体当前状态：当前音频流=${streams.audio.size}；当前输入上行流（含合成静音）=${streams.microphones}；Surface有效=${surfaceState.snapshot.target?.isValid == true}；视频编码=$videoCodec；视频配置就绪=${configuration.snapshot!=null}；配置字节=${configuration.snapshot?.size ?: 0}；目标=${videoWidth}x${videoHeight}；fps=${settings.fps}；视频队列=${video.size}/16；解码器活动=${diagnostics.decoderActive}；解码器=${diagnostics.decoderName}；解码输出格式=${diagnostics.decodedFormat}；等待关键帧=${diagnostics.waitingForKeyFrame}" +
            summaries.joinToString("\n", prefix = if (summaries.isEmpty()) "" else "\n") +
            "\n" + microphoneNegotiation.snapshot() + "\n" + diagnostics.microphone.snapshot() +
            "\n" + ScreenFrameCrypto.diagnosticSnapshot() + "\n" + ScreenCryptoNative.diagnosticSnapshot() + "\n" + crypto
        val brief = ("当前音频流=${streams.audio.size}；$crypto\n" + summaries.take(4).joinToString("\n")).take(8192)
        return full to brief
    }
    fun isMediaPlaybackActive():Boolean = !closed.get() && !audioChanging && audioReaders.any { it.mediaPlaybackActive() }
    fun isVoicePlaybackActive():Boolean = !closed.get() && (audioChanging || audioReaders.any { it.nonMediaPlaybackActive() })
    /** Metadata only: no device probe, capture, network read or PCM contents. */
    fun audioDiagnosticSummary(): String {
        val streams = streamSnapshot()
        return ("当前音频流=${streams.audio.size}；${AudioFrameCrypto.diagnosticSnapshot()}\n" +
            streams.audio.take(4).joinToString("\n") { it.summary() }).take(8192)
    }
    internal fun learningWindow():PerformanceLearningPolicy.Window? = learningTelemetry.sample(
        android.os.SystemClock.elapsedRealtime(), diagnostics.learningCounters(), ScreenFrameCrypto.counters(),
        !closed.get() && surfaceState.snapshot.target?.isValid == true, diagnostics.decoderActive)
    fun setSurface(value: Surface?, force:Boolean=false) {
        if (closed.get()) return
        if(surfaceState.attach(value,force)) {
            resync.set(true)
            log("视频Surface更新：generation=${surfaceState.snapshot.generation}；有效=${value?.isValid==true}；强制恢复=$force")
        }
    }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { if (!closed.get()) recovery = handler }
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (closed.get()) return
        require(codec == VideoCodec.H264 || CodecCapabilities.hevc()) { "No HEVC decoder on this firmware" }
        if (videoCodec != codec) { videoCodec = codec; resync.set(true) }
    }
    override fun onVideoConfig(type: Int, codecData: ByteArray) { if (!closed.get() && configuration.update(codecData)) resync.set(true) }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed.get()) return
        if(naluBytes.isEmpty()) return
        val accepted = naluBytes.size <= 2 * 1024 * 1024 && video.offer(QueuedVideo(naluBytes.copyOf(),System.nanoTime()))
        if (diagnostics.receivedVideo(naluBytes.size, video.size)) log("USB first video packet；首个视频帧数据到达接收器；不代表已解码或显示")
        if (!accepted) {
            diagnostics.videoDropped.incrementAndGet()
            clearVideoQueue(); resync.set(true)
        }
    }
    @Synchronized override fun onAudioStarted(id: AudioStreamId, format: com.shilapi.xcertplay.airplay.AudioFormat, firstSample: Int) {
        if (closed.get()) return
        audioChanging = true
        try {
            audio.remove(id)?.close()
            if (format.codec == AudioCodecKind.OPUS && !CodecCapabilities.opus()) { log("Opus codec unavailable"); return }
            log("音频流开始：type=${id.type}；codec=${format.codec}；rate=${format.sampleRate}；channels=${format.channels}；用途=${AudioDiagnostics.purpose(format.audioType)}")
            audio[id] = LegacyAudio(format, firstSample, log, diagnostics, settings, coordinator)
        } finally { audioReaders = audio.values.toList(); audioChanging = false }
    }
    @Synchronized override fun onAudioRtp(id: AudioStreamId, format: com.shilapi.xcertplay.airplay.AudioFormat, rtp: ByteArray, sample: Int) {
        if (diagnostics.receivedAudio()) log("USB first audio packet；首个解密音频RTP到达接收器；不代表已写入或播放")
        val stream = audio[id]
        if (stream == null) diagnostics.audioNoStream.incrementAndGet() else stream.offer(rtp, sample)
    }
    @Synchronized override fun onAudioStopped(id: AudioStreamId) {
        audioChanging = true
        try { audio.remove(id)?.close() }
        finally { audioReaders = audio.values.toList(); audioChanging = false }
    }
    override fun onMicrophoneDiagnostic(id: AudioStreamId, status: String) {
        if (!closed.get()) microphoneNegotiation.record(id.type, AudioDiagnostics.purpose(id.audioType), status)
    }
    @Synchronized override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed.get()) return
        microphones.remove(id)?.stop("replaced")
        val originalSource = microphoneAudioSource(config.audioType)
        val selectedSource = if(settings.microphone) runCatching {
            appContext?.let { FeatureLearning.resolvedMicrophoneSource(it,config.audioType,originalSource) } ?: originalSource
        }.getOrDefault(originalSource) else -1
        val evidence = diagnostics.microphone.begin(config.audioType, selectedSource,
            config.sampleRate, config.channels, config.codec.name, config.frameMillis, config.frameBytes,
            config.samplesPerPacket, config.bitrate, capture=settings.microphone)
        if (config.codec == AudioCodecKind.OPUS && !CodecCapabilities.opus()) {
            evidence.finish("opus-unavailable"); log("Opus encoder unavailable"); return
        }
        log("麦克风选路：capture=${settings.microphone} syntheticSilence=${!settings.microphone}；用途=${AudioDiagnostics.purpose(config.audioType)}；AudioSource=$selectedSource；codec=${config.codec}；rate=${config.sampleRate}；channels=${config.channels}；目标为iPhone上行（不记录地址或密钥）")
        val app=appContext
        microphones[id] = LegacyMicrophone(config, log, diagnostics, evidence, captureEnabled=settings.microphone,
            selectedSource=selectedSource, sourceFailed={ source ->
                if(app!=null) runCatching { FeatureLearning.microphoneSourceFailed(app,config.audioType,source) }
            })
    }
    @Synchronized override fun onMicrophoneStopped(id: AudioStreamId) { microphones.remove(id)?.stop("stream-stop") }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        recovery = null; recoveryExecutor.close()
        clearVideoQueue(); worker.interrupt()
        audioReaders = emptyList()
        audio.values.forEach { it.close() }; audio.clear()
        microphones.values.forEach { it.stop("receiver-close") }; microphones.clear()
        log(diagnostics.summary())
    }
    private fun clearVideoQueue() {
        val removed = ArrayList<QueuedVideo>(16)
        video.drainTo(removed)
        diagnostics.videoQueueCleared.addAndGet(removed.size.toLong())
    }
    private fun videoLoop() {
        // Best effort scheduling only; vendor permission/policy failure cannot stop playback.
        diagnostics.videoDisplayPriorityRequested=runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);true
        }.getOrDefault(false)
        diagnostics.videoWorkerPriority=runCatching {
            android.os.Process.getThreadPriority(android.os.Process.myTid())
        }.getOrNull()
        var decoder: MediaCodec? = null
        var oldSurface: Surface? = null
        var oldGeneration = -1L
        var recoveryFailures=0
        var oldConfig: ByteArray? = null
        var oldCodec = VideoCodec.H264
        var inputs: Array<ByteBuffer>? = null
        val latency=VideoLatencyPolicy()
        val epochNs=System.nanoTime()
        var lastPtsUs=-1L
        var recoveryStartedNs=epochNs
        var pending: QueuedVideo? = null
        var pendingOutputPtsUs=-1L
        val info = MediaCodec.BufferInfo()
        fun release() {
            decoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            decoder = null; inputs = null
            diagnostics.decoderActive = false
        }
        val outputBatch = LatestDecodedOutputBatch { index, reason ->
            // Only this worker changes decoder, and each drain clears ownership in finally.
            val codec = checkNotNull(decoder)
            val releaseStarted=System.nanoTime()
            var releaseFinished=releaseStarted
            try {
                // The timestamp overload is API21+. Boolean release is available on this firmware.
                codec.releaseOutputBuffer(index, reason == LatestDecodedOutputBatch.ReleaseKind.RENDER)
            } catch (error: Exception) {
                diagnostics.videoOutputReleaseErrors.incrementAndGet()
                throw error
            } finally {
                releaseFinished=System.nanoTime()
                diagnostics.videoOutputRelease(releaseFinished-releaseStarted)
            }
            when (reason) {
                LatestDecodedOutputBatch.ReleaseKind.RENDER -> {
                    diagnostics.videoSubmittedAge(epochNs,pendingOutputPtsUs,releaseFinished)
                    if (diagnostics.outputVideo()) log("USB first decoded video output released；解码输出缓冲已提交Surface；不证明屏幕实际显示")
                    rendered()
                }
                LatestDecodedOutputBatch.ReleaseKind.SUPERSEDED -> diagnostics.videoSupersededOutputs.incrementAndGet()
                LatestDecodedOutputBatch.ReleaseKind.RECOVERY -> diagnostics.videoRecoveryDiscardedOutputs.incrementAndGet()
                LatestDecodedOutputBatch.ReleaseKind.METADATA -> diagnostics.videoMetadataOutputs.incrementAndGet()
                LatestDecodedOutputBatch.ReleaseKind.CANCELLED -> diagnostics.videoCancelledOutputs.incrementAndGet()
            }
        }
        fun drain() {
            val codec = decoder ?: return
            val until=System.nanoTime()+4_000_000L
            var drained=0
            try {
                // Adapted from Moonlight's decoded-output catch-up strategy. Keep the existing
                // single owner and 8-buffer/4ms soft budget; never skip compressed reference frames.
                // https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java#L1023
                while (!closed.get() && drained<8 && System.nanoTime()<until) {
                    val dequeueStarted=System.nanoTime()
                    val index = codec.dequeueOutputBuffer(info, 0)
                    diagnostics.videoOutputDequeue(System.nanoTime()-dequeueStarted)
                    if (index >= 0) {
                        drained++
                        diagnostics.videoDecodedOutputs.incrementAndGet()
                        val picture = LatestDecodedOutputBatch.isPicture(info.size,
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0,
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        if(picture) diagnostics.videoDecodedAge(epochNs,info.presentationTimeUs,System.nanoTime())
                        when {
                            !picture -> outputBatch.discard(index, LatestDecodedOutputBatch.ReleaseKind.METADATA)
                            !latency.shouldRender(info.presentationTimeUs) -> outputBatch.discard(index, LatestDecodedOutputBatch.ReleaseKind.RECOVERY)
                            else -> {pendingOutputPtsUs=info.presentationTimeUs;outputBatch.keep(index)}
                        }
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = codec.outputFormat
                        fun number(key: String): Int = runCatching { format.getInteger(key) }.getOrDefault(-1)
                        diagnostics.decodedFormat = "${number(MediaFormat.KEY_WIDTH)}x${number(MediaFormat.KEY_HEIGHT)}；输出格式fps=${number(MediaFormat.KEY_FRAME_RATE)}（-1为未报告）"
                    } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // Surface output uses indices, not getOutputBuffers(). Retain the candidate.
                    } else {
                        // TRY_AGAIN_LATER (or an unknown notification): submit the selected picture.
                        break
                    }
                }
                if(!closed.get() && (drained>=8 || System.nanoTime()>=until)) diagnostics.videoDrainBudgetYields.incrementAndGet()
                outputBatch.finish(!closed.get())
            } finally {
                // Includes a failing dequeue/format read or a failed release of a superseded index.
                // Every acquired index gets one release attempt; codec teardown reclaims any index
                // whose native release failed. Never retry an ambiguously released index.
                outputBatch.abort()
            }
        }
        fun requestRecovery() {
            val handler=recovery ?: return
            if(latency.requestKeyFrameDue(System.nanoTime())) {
                recoveryExecutor.request {
                    if (!closed.get() && recovery === handler) {
                        diagnostics.recoveryRequests.incrementAndGet()
                        val started=System.nanoTime()
                        try { handler() } finally { diagnostics.videoRecoveryRequest(System.nanoTime()-started) }
                    }
                }
            }
        }
        fun needRecovery() {
            if(!latency.needsKeyFrame) recoveryStartedNs=System.nanoTime()
            latency.requireKeyFrame(); diagnostics.waitingForKeyFrame=true
        }
        try {
            while (!closed.get()) {
              try {
                val snapshot=surfaceState.snapshot
                val target = snapshot.target
                val data = configuration.snapshot
                if (snapshot.generation!=oldGeneration || target !== oldSurface || data !== oldConfig || videoCodec != oldCodec) {
                    release(); oldSurface = target; oldGeneration=snapshot.generation; oldConfig = data; oldCodec = videoCodec; needRecovery(); pending=null; clearVideoQueue()
                    diagnostics.waitingForKeyFrame = true
                    if (target?.isValid == true && data != null) {
                        val mime = if(oldCodec == VideoCodec.H265) "video/hevc" else "video/avc"
                        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
                        if (oldCodec == VideoCodec.H265) {
                            val csd = MediaCodecSupport.hevcCodecSpecificData(data)
                            require(csd.isNotEmpty())
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                        } else {
                            val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
                            require(sps.isNotEmpty() && pps.isNotEmpty()) { "Invalid AVC configuration" }
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
                        }
                        for (name in CodecCapabilities.names(mime)) {
                            var candidate: MediaCodec? = null
                            try {
                                candidate = MediaCodec.createByCodecName(name)
                                candidate.configure(format, target, null, 0); candidate.start()
                                decoder = candidate; inputs = candidate.inputBuffers
                                diagnostics.decoderStarts.incrementAndGet()
                                diagnostics.decoderActive = true; diagnostics.decoderName = name
                                log("视频解码器已启动：$name"); break
                            } catch (e: Exception) {
                                diagnostics.decoderStartErrors.incrementAndGet(); diagnostics.lastDecoderError = e.javaClass.simpleName.take(80)
                                try { candidate?.release() } catch (_: Exception) {}
                                log("视频解码器启动失败：$name；${e.javaClass.simpleName}；${e.message?.take(160)}")
                            }
                        }
                        check(decoder != null) { "No usable H.264 decoder" }
                    }
                }
                if (decoder != null && resync.getAndSet(false)) needRecovery()
                if(decoder!=null) requestRecovery()
                drain()
                val frame = pending?.also { pending = null } ?: video.poll(5, TimeUnit.MILLISECONDS) ?: continue
                val codec = decoder
                if (codec == null) {
                    if (surfaceState.snapshot.target?.isValid == true && configuration.snapshot != null) pending = frame
                    else diagnostics.videoNoDecoder.incrementAndGet()
                    continue
                }
                val now=System.nanoTime()
                diagnostics.videoQueueAge(latency.ageNs(frame.receivedNs,now))
                if(latency.expired(frame.receivedNs,now)) {
                    diagnostics.videoExpired.incrementAndGet(); clearVideoQueue(); needRecovery(); requestRecovery(); continue
                }
                val annexB = MediaCodecSupport.toAnnexB(frame.bytes)
                if (annexB.isEmpty()) { diagnostics.videoInvalid.incrementAndGet(); resync.set(true); continue }
                if (latency.needsKeyFrame && !MediaCodecSupport.isRandomAccess(annexB, oldCodec)) { diagnostics.videoWaitingIdr.incrementAndGet(); continue }
                var index = -1
                val waitStarted=System.nanoTime()
                val deadline = minOf(System.nanoTime() + 500_000_000L,frame.receivedNs+latency.maxFrameAgeNs)
                while (index < 0 && !closed.get() && System.nanoTime() < deadline) {
                    drain()
                    val dequeueStarted=System.nanoTime()
                    index = codec.dequeueInputBuffer(5000)
                    diagnostics.videoInputDequeue(System.nanoTime()-dequeueStarted)
                }
                diagnostics.videoInputWait(System.nanoTime()-waitStarted)
                if (index < 0) { diagnostics.videoInputTimeouts.incrementAndGet(); resync.set(true); continue }
                if(latency.expired(frame.receivedNs,System.nanoTime())) {
                    codec.queueInputBuffer(index,0,0,0,0)
                    diagnostics.videoExpired.incrementAndGet(); clearVideoQueue(); needRecovery(); requestRecovery(); continue
                }
                val input = inputs!![index]; input.clear()
                if (annexB.size > input.remaining()) {
                    diagnostics.videoInputTooLarge.incrementAndGet()
                    codec.queueInputBuffer(index, 0, 0, 0, 0); resync.set(true); continue
                }
                input.put(annexB)
                val ptsUs=maxOf(lastPtsUs+1,(frame.receivedNs-epochNs).coerceAtLeast(0L)/1000L)
                val queueStarted=System.nanoTime()
                codec.queueInputBuffer(index, 0, annexB.size, ptsUs, 0)
                diagnostics.videoInputQueue(System.nanoTime()-queueStarted); diagnostics.queuedVideo(); lastPtsUs=ptsUs
                if(latency.needsKeyFrame) diagnostics.videoRecoveryWait(System.nanoTime()-recoveryStartedNs)
                latency.queued(ptsUs)
                diagnostics.waitingForKeyFrame = false; drain(); recoveryFailures=0
              } catch(error:InterruptedException) {
                if(closed.get()) break
                diagnostics.decoderErrors.incrementAndGet(); diagnostics.lastDecoderError = error.javaClass.simpleName.take(80)
              } catch(error:Exception) {
                if(closed.get()) break
                diagnostics.decoderErrors.incrementAndGet(); diagnostics.lastDecoderError = error.javaClass.simpleName.take(80)
                recoveryFailures++
                log("视频恢复：解码异常=${error.javaClass.simpleName}；原因=${error.message?.take(160)}；次数=$recoveryFailures；将重建解码器并请求关键帧；线程继续运行")
                release(); oldSurface=null; oldGeneration=-1; oldConfig=null; pending=null
                clearVideoQueue(); resync.set(true); diagnostics.waitingForKeyFrame = true
                Thread.sleep(minOf(2000L,250L*recoveryFailures.coerceAtMost(8)))
              }
            }
        } catch (_: InterruptedException) {} catch (e: Exception) {
            if (!closed.get()) { diagnostics.decoderErrors.incrementAndGet(); diagnostics.lastDecoderError = e.javaClass.simpleName.take(80); log("视频失败：${e.javaClass.simpleName}") }
        }
        finally { release() }
    }
}

private class LegacyAudio(private val format: com.shilapi.xcertplay.airplay.AudioFormat, private val firstSample: Int,
                          private val log: (String) -> Unit, private val diagnostics: MediaDiagnostics,
                          private val settings: ReceiverSettings, private val coordinator: LegacyAudioCoordinator) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val resourceLock = Any()
    private val received = java.util.concurrent.atomic.AtomicLong()
    private val written = java.util.concurrent.atomic.AtomicLong()
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private val rejected = java.util.concurrent.atomic.AtomicLong()
    private val queuePeak = java.util.concurrent.atomic.AtomicLong()
    private val selection = AudioChannelMapper.map(format.audioType, format.payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE, android.media.AudioManager.STREAM_MUSIC)
    private val stream = AudioOverridePolicy.resolve(selection.channel.name,selection.streamType,settings.mediaChannel,settings.navigationChannel)
    private val queue = LegacyAudioPacketQueue(selection.channel == AudioChannel.MEDIA,
        format.codec == AudioCodecKind.LPCM, format.sampleRate, format.channels, settings.bufferMillis)
    private val continuity = AudioContinuityDiagnostics(format.sampleRate, format.channels * 2)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var actualStream = stream
    @Volatile private var lastWriteNs=Long.MIN_VALUE
    private data class OutputSnapshot(val sampledMs: Long = -1, val state: Int = -1, val playState: Int = -1,
        val rawHead: Long = 0, val frames: Long = 0, val queuedBytes: Long = 0,
        val sampledNs: Long = Long.MIN_VALUE)
    @Volatile private var outputSnapshot = OutputSnapshot()
    fun mediaPlaybackActive():Boolean = !stopped.get() && selection.channel==AudioChannel.MEDIA &&
        outputSnapshot.playState==AudioTrack.PLAYSTATE_PLAYING && outputSnapshot.frames>0 &&
        lastWriteNs!=Long.MIN_VALUE && System.nanoTime()-lastWriteNs in 0L..1_500_000_000L
    fun nonMediaPlaybackActive():Boolean = !stopped.get() && selection.channel!=AudioChannel.MEDIA &&
        outputSnapshot.playState==AudioTrack.PLAYSTATE_PLAYING && outputSnapshot.frames>0 &&
        lastWriteNs!=Long.MIN_VALUE && System.nanoTime()-lastWriteNs in 0L..1_500_000_000L
    private val worker = Thread({ run() }, "legacy-audio").apply { isDaemon = true; start() }
    fun summary(): String {
        val output = outputSnapshot
        val now = System.nanoTime()
        val headAgeMs = if (output.sampledNs == Long.MIN_VALUE) -1L else (now - output.sampledNs) / 1_000_000L
        val backlog = queue.backlog(now)
        val softwareMs = backlog.queuedMillis
        val trackMs = if (format.sampleRate > 0 && format.channels > 0) output.queuedBytes * 1000L / (format.sampleRate * format.channels * 2) else -1L
        val pendingMs = if (headAgeMs in 0L..1000L && trackMs >= 0) (softwareMs + trackMs).toString() else "未取得新鲜播放头"
        return "音频通道统计：用途=${AudioDiagnostics.purpose(format.audioType)}；type=${format.payloadType}；codec=${format.codec}；rate=${format.sampleRate}；channels=${format.channels}；Android=${AudioDiagnostics.androidStreamName(actualStream)}($actualStream)；请求通道=$stream；RTP=${received.get()}；写入字节=${written.get()}；入队拒绝=${dropped.get()}；短包拒绝=${rejected.get()}；队列=${queue.size}/${queue.capacityPackets}；队列峰值=${queuePeak.get()}；队列预算=${queue.budgetMillis}ms；待处理=${softwareMs}ms/${queue.queuedBytes}字节；接收owner已结束=${stopped.get()}；AudioTrack状态=${output.state}；播放状态=${output.playState}；输出采样=${if (output.sampledMs < 0) "未采样" else "+${output.sampledMs}ms"}；无符号播放头=${output.rawHead}；累计推进帧=${output.frames}；估算待播放字节=${output.queuedBytes}（worker快照；播放头推进不证明实际听到声音）\n" +
            "音频待播估算：软件队列=${softwareMs}ms；最老包等待=${backlog.oldestAgeMillis}ms；AudioTrack=${trackMs}ms；已接收合计=${pendingMs}ms；播放头快照年龄=${headAgeMs}ms（不含手机、USB传输和硬件扬声器延迟，非端到端测量）\n" + continuity.summary()
    }
    fun offer(bytes: ByteArray, sample: Int) {
        if (stopped.get()) return
        received.incrementAndGet()
        if (bytes.size <= 12) { diagnostics.audioRtpRejected.incrementAndGet(); rejected.incrementAndGet(); return }
        val frameBytes = format.channels * 2
        if (format.codec == AudioCodecKind.LPCM && (frameBytes <= 0 || (bytes.size - 12) % frameBytes != 0)) {
            diagnostics.audioRtpRejected.incrementAndGet(); rejected.incrementAndGet(); return
        }
        val arrivedNs = System.nanoTime()
        val sequence = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val ssrc = ((bytes[8].toInt() and 0xff) shl 24) or ((bytes[9].toInt() and 0xff) shl 16) or
            ((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff)
        if (!continuity.arrived(sequence, sample, if (format.codec == AudioCodecKind.LPCM) (bytes.size - 12) / frameBytes else 0, arrivedNs, ssrc)) {
            diagnostics.audioLateOrDuplicate.incrementAndGet(); return
        }
        if (!queue.offer(bytes, sample, ssrc, arrivedNs)) {
            diagnostics.audioDropped.incrementAndGet()
            continuity.dropped()
            val count = dropped.incrementAndGet()
            if ((count and (count - 1)) == 0L) log("音频缓冲溢出：累计=$count")
        }
        val size = queue.size.toLong()
        while (true) {
            val old = queuePeak.get()
            if (size <= old || queuePeak.compareAndSet(old, size)) break
        }
    }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        lastWriteNs = Long.MIN_VALUE
        queue.close(); worker.interrupt()
        // Unblock a possibly full streaming write, without UI/ingress waiting for native cleanup.
        // The worker remains the only releaser. This helper can only pause this owner's track.
        Thread({ synchronized(resourceLock) {
            track?.let { output -> runCatching { output.pause() }; runCatching { output.flush() } }
        } }, "legacy-audio-stop").apply { isDaemon = true; start() }
    }
    /** Shares the stop helper's native-operation gate; a late positive write cannot restart us. */
    private fun playIfOpen(output: AudioTrack): Boolean = synchronized(resourceLock) {
        if (stopped.get() || track !== output) false else { output.play(); true }
    }
    private fun acquireIfOpen(output: AudioTrack): Boolean = synchronized(resourceLock) {
        if (stopped.get() || track !== output) false else { coordinator.acquire(output, selection.channel); true }
    }
    private fun run() {
        var ownedTrack: AudioTrack? = null
        var codec: MediaCodec? = null
        var inputs: Array<ByteBuffer>? = null
        var outputs: Array<ByteBuffer>? = null
        val info = MediaCodec.BufferInfo()
        try {
            // AudioFlinger competes with video on this dual-core unit. Only the output worker uses
            // Android's audio priority; USB, crypto/control and the UI keep their existing policy.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            require(format.channels in 1..2 && format.sampleRate in 8000..192000) { "Unsupported audio output format" }
            val channels = if (format.channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "Audio output unavailable" }
            val plan = if (selection.channel == AudioChannel.MEDIA)
                LegacyMusicLatency.plan(format.sampleRate, format.channels, minimum, settings.bufferMillis)
            else MediaAudioBuffer.plan(false, format.sampleRate, format.channels, minimum, settings.bufferMillis)
            var startBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)
            var bufferedBytes = 0
            val progress = PlaybackHeadProgress(format.channels * 2)
            var lastOutputSampleNs = Long.MIN_VALUE
            var playing = false
            fun createTrack(channel:Int):AudioTrack {
                val result=AudioTrack(channel, format.sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT,plan.trackBufferBytes,AudioTrack.MODE_STREAM)
                if(result.state!=AudioTrack.STATE_INITIALIZED) { result.release(); error("Audio stream unavailable") }
                return result
            }
            val output=try { createTrack(stream) } catch(error:Exception) {
                if(stream==selection.streamType) throw error
                log("自选音频通道不可用：$stream；回退自动通道=${selection.streamType}")
                actualStream=selection.streamType
                createTrack(selection.streamType)
            }
            ownedTrack = output
            val published = synchronized(resourceLock) {
                if (stopped.get()) false else { track = output; true }
            }
            if (!published) return
            fun sampleOutput(force: Boolean = false) {
                if (stopped.get()) return
                val now = System.nanoTime()
                if (!force && lastOutputSampleNs != Long.MIN_VALUE && now - lastOutputSampleNs < 250_000_000L) return
                lastOutputSampleNs = now
                try {
                    val sample = progress.sample(output.playbackHeadPosition, written.get())
                    val snapshot = OutputSnapshot(diagnostics.elapsedMs(), output.state, output.playState, sample.rawHead, sample.frames, sample.queuedBytes, now)
                    outputSnapshot = snapshot
                    continuity.output(playing, sample.advanced, sample.queuedBytes)
                    if (sample.reset) diagnostics.audioHeadResets.incrementAndGet()
                    if (diagnostics.advancedAudio(sample.advanced)) log("USB audio playback progressed；AudioTrack播放头首次推进；不证明实际听到声音")
                } catch (error: Exception) {
                    diagnostics.audioHeadReadErrors.incrementAndGet(); diagnostics.lastAudioError = error.javaClass.simpleName.take(80)
                }
            }
            sampleOutput(true)
            val capacity = runCatching { AudioTrack::class.java.getDeclaredMethod("getNativeFrameCount").apply { isAccessible=true }.invoke(output) as Int }.getOrNull()?.times(format.channels*2)
            if(capacity != null && capacity > 0) startBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacity, minOf(4096, capacity/2).coerceAtLeast(2))
            log("Audio buffer requested=${plan.trackBufferBytes} actual=${capacity ?: -1} start=$startBytes mediaMs=${settings.bufferMillis}")
            if (!acquireIfOpen(output)) return
            if (stopped.get()) return
            log("音频选路：用途=${AudioDiagnostics.purpose(format.audioType)}；Android=${AudioDiagnostics.androidStreamName(actualStream)}($actualStream)；请求通道=$stream；输出声道数=${format.channels}；rate=${format.sampleRate}；state=${output.state}；playState=${output.playState}")
            if (format.codec != AudioCodecKind.LPCM) {
                val mime = if (format.codec == AudioCodecKind.OPUS) "audio/opus" else "audio/mp4a-latm"
                val mediaFormat = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channels)
                if (format.codec == AudioCodecKind.AAC_LC) mediaFormat.setInteger("is-adts", 1)
                val frequency = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(((2 shl 3) or (frequency shr 1)).toByte(),
                    (((frequency and 1) shl 7) or (format.channels shl 3)).toByte())))
                if(format.codec == AudioCodecKind.OPUS) {
                    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(format.channels, format.sampleRate)))
                    mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(6500000L).array()))
                    mediaFormat.setByteBuffer("csd-2", ByteBuffer.wrap(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(80000000L).array()))
                }
                val ready = synchronized(resourceLock) {
                    if (stopped.get() || track !== output) false else {
                        val decoder = MediaCodec.createDecoderByType(mime)
                        codec = decoder
                        if (stopped.get()) false else {
                            decoder.configure(mediaFormat, null, null, 0)
                            if (stopped.get()) false else {
                                decoder.start(); inputs = decoder.inputBuffers; outputs = decoder.outputBuffers; true
                            }
                        }
                    }
                }
                if (!ready) return
            }
            fun write(bytes: ByteArray) {
                var offset = 0
                while (!stopped.get() && offset < bytes.size) {
                    if (!playing && bufferedBytes >= startBytes) playing = playIfOpen(output)
                    if (stopped.get()) return
                    val startedNs = System.nanoTime()
                    val count = output.write(bytes, offset, minOf(4096, bytes.size - offset))
                    check(count > 0) { "Audio write failed: $count" }
                    continuity.wrote(count, System.nanoTime() - startedNs)
                    if (diagnostics.wroteAudio(count)) log("USB first audio write；首批PCM写入AudioTrack；不代表播放头已推进")
                    written.addAndGet(count.toLong()); offset += count; bufferedBytes += count
                    if (!playing && bufferedBytes >= startBytes) playing = playIfOpen(output)
                    lastWriteNs=System.nanoTime()
                    sampleOutput()
                }
            }
            fun drain() {
                val decoder = codec ?: return
                while (!stopped.get()) {
                    val index = decoder.dequeueOutputBuffer(info, 0)
                    when {
                        index >= 0 -> {
                            try {
                                if (info.size > 0) {
                                    val bytes = ByteArray(info.size)
                                    val buffer = outputs!![index]
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                                    write(bytes)
                                }
                            } finally { decoder.releaseOutputBuffer(index, false) }
                        }
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs = decoder.outputBuffers
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val next = decoder.outputFormat
                            check(next.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                                next.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels) { "Unexpected AAC output format" }
                        }
                        else -> return
                    }
                }
            }
            log("音频已启动：${format.codec}, ${format.sampleRate} Hz")
            val pcmRepair = if (format.codec == AudioCodecKind.LPCM && selection.channel == AudioChannel.MEDIA)
                PcmGapRepair(format.sampleRate, format.channels) else null
            while (!stopped.get()) {
                drain()
                sampleOutput()
                val packet = queue.poll(20)
                if (packet == null) {
                    // Keep a started AudioTrack playing across short underruns. Pausing it here makes
                    // the next write() block on the paused (non-draining) track, so the resume branch
                    // below is never reached and music never comes back; a streaming AudioTrack resumes
                    // by itself as soon as PCM is written again (mirrors upstream 0.2.15 #401).
                    continuity.waitedEmpty(); continue
                }
                continuity.dequeued(System.nanoTime() - packet.arrivedNs)
                var payload = packet.bytes.copyOfRange(12, packet.bytes.size)
                val activeDecoder = codec
                if (activeDecoder == null) {
                    for (i in 0 until payload.size - 1 step 2) { val b = payload[i]; payload[i] = payload[i + 1]; payload[i + 1] = b }
                    if (pcmRepair == null) write(payload) else {
                        val repair = pcmRepair.process(packet.sample, payload, packet.ssrc)
                        continuity.repaired(repair.concealedFrames, repair.discontinuity, repair.budgetLimited)
                        diagnostics.audioConcealedFrames.addAndGet(repair.concealedFrames.toLong())
                        if (repair.budgetLimited) diagnostics.audioRepairBudgetSkipped.incrementAndGet()
                        repair.silence?.let { write(it) }
                        write(repair.pcm)
                    }
                }
                else {
                    if (format.codec == AudioCodecKind.AAC_LC) payload = MediaCodecSupport.adtsFrame(payload, format.sampleRate, format.channels)
                    var index = -1
                    val deadline = System.nanoTime() + 250_000_000L
                    while (index < 0 && !stopped.get() && System.nanoTime() < deadline) { drain(); index = activeDecoder.dequeueInputBuffer(10000) }
                    if (index < 0) {
                        val count = diagnostics.audioInputTimeouts.incrementAndGet()
                        if ((count and (count - 1)) == 0L) log("音频输入缓冲超时：累计=$count")
                        continue
                    }
                    val input = inputs!![index]; input.clear()
                    if (payload.size > input.remaining()) { diagnostics.audioInputTooLarge.incrementAndGet(); activeDecoder.queueInputBuffer(index, 0, 0, 0, 0); continue }
                    input.put(payload)
                    val pts = ((packet.sample.toLong() - firstSample.toLong()) and 0xffffffffL) * 1_000_000L / format.sampleRate
                    activeDecoder.queueInputBuffer(index, 0, payload.size, pts, 0); drain()
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) { diagnostics.audioErrors.incrementAndGet(); diagnostics.lastAudioError = e.javaClass.simpleName.take(80); log("音频失败：${e.javaClass.simpleName}") }
        }
        finally {
            stopped.set(true); lastWriteNs = Long.MIN_VALUE; queue.close()
            try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}
            ownedTrack?.let { output ->
                try { coordinator.release(output) } catch (_: Exception) { /* Release native output even if focus cleanup fails. */ }
                synchronized(resourceLock) {
                    if (track === output) track = null
                    try { output.stop() } catch (_: Exception) {}
                    try { output.release() } catch (_: Exception) {}
                }
            }
            outputSnapshot = outputSnapshot.copy(state=AudioTrack.STATE_UNINITIALIZED,
                playState=AudioTrack.PLAYSTATE_STOPPED, queuedBytes=0)
            runCatching { log(summary()) }
        }
    }
}

/** Match upstream MicrophoneUplink source selection using SDK18 constructors. */
private fun microphoneAudioSource(audioType: String): Int = when (audioType.toLowerCase(java.util.Locale.US)) {
    "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    else -> MediaRecorder.AudioSource.MIC
}
private class LegacyMicrophone @JvmOverloads constructor(private val config: MicrophoneConfig, private val log: (String) -> Unit,
    private val diagnostics: MediaDiagnostics, private val evidence: MicrophoneDiagnostics.Stream,
    private val captureEnabled: Boolean = true,
    private val selectedSource: Int = microphoneAudioSource(config.audioType),
    private val sourceFailed:(Int)->Unit = {}) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val resourceLock = Any()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var silence: SyntheticSilenceSource? = null
    private val worker = Thread({ run() }, "legacy-microphone").apply { isDaemon = true; start() }
    override fun close() = stop("close")
    fun stop(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        evidence.closeRequested(reason)
        silence?.stop()
        worker.interrupt()
        val resources = synchronized(resourceLock) { record to socket }
        try { resources.first?.stop() } catch (error: Exception) { evidence.cleanupFailed("record-stop", error) }
        try { resources.second?.close() } catch (error: Exception) { evidence.cleanupFailed("socket-close", error) }
    }
    private fun run() {
        var encoder: LegacyOpusEncoder? = null
        var failed = false
        var microphoneLease:Closeable? = null
        var sourceOpening=false
        try {
            if (stopped.get()) return
            // Resolve and bind before acquiring the microphone or its encoder. A listener failure
            // must not leave an AudioRecord instance or an unpublished DatagramSocket behind.
            evidence.phase(MicrophoneDiagnostics.Phase.SOCKET_BIND)
            val network = LegacyMicrophoneSocket.open(config)
            synchronized(resourceLock) {
                if (stopped.get()) { network.close(); return }
                socket = network
            }
            evidence.socketBound(if (network.localAddress is java.net.Inet6Address) 6 else 4,
                if (config.host is java.net.Inet6Address) 6 else 4,
                (network.localAddress as? java.net.Inet6Address)?.scopeId?.let { it > 0 } == true,
                (config.host as? java.net.Inet6Address)?.scopeId?.let { it > 0 } == true, config.port in 1..65535)
            if (stopped.get()) return
            if (config.codec == AudioCodecKind.OPUS) {
                evidence.phase(MicrophoneDiagnostics.Phase.ENCODER_OPEN)
                encoder = LegacyOpusEncoder(config.bitrate, evidence)
            }
            // The input endpoint remains present when capture is disabled. Do not execute any
            // AudioRecord API in that mode; the same negotiated transport carries zero PCM only.
            val recorder: AudioRecord? = if(captureEnabled) {
            microphoneLease=FeatureLearning.tryAcquireLiveMicrophone()
                ?: throw IllegalStateException("Optional calibration microphone is still releasing")
            val channel = if (config.channels == 1) android.media.AudioFormat.CHANNEL_IN_MONO else android.media.AudioFormat.CHANNEL_IN_STEREO
            evidence.phase(MicrophoneDiagnostics.Phase.RECORD_BUFFER)
            val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channel, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val bufferBytes = maxOf(minBuffer * 2, config.frameBytes * 4)
            evidence.buffer(minBuffer, bufferBytes)
            require(minBuffer > 0)
            evidence.phase(MicrophoneDiagnostics.Phase.RECORD_OPEN)
            sourceOpening=true
            val recorder = AudioRecord(selectedSource, config.sampleRate, channel,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            synchronized(resourceLock) {
                record = recorder
                if (stopped.get()) return
            }
            evidence.recordOpened(recorder.state, recorder.sampleRate, recorder.channelCount, recorder.audioSessionId)
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            if (stopped.get()) return
            evidence.phase(MicrophoneDiagnostics.Phase.RECORD_START)
            recorder.startRecording()
            evidence.recordStarted(recorder.recordingState)
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            sourceOpening=false
            log("PCM 麦克风上行已启动；AudioRecord状态=RECORDING；Android录音权限可实际使用")
            recorder
            } else {
                silence = SyntheticSilenceSource(config.frameBytes, config.frameMillis)
                log("CarPlay静音输入已启动：capture=false syntheticSilence=true；未打开AudioRecord，不采集车机声音")
                null
            }
            val counters = MicrophoneCounters(); val recordedPcm = if(captureEnabled) ByteArray(config.frameBytes) else null
            while (!stopped.get()) {
                val pcm = if(recorder == null) {
                    evidence.phase(MicrophoneDiagnostics.Phase.SYNTHETIC_WAIT)
                    val frame = silence?.nextFrame() ?: break
                    evidence.syntheticFrame(frame.size)
                    frame
                } else {
                val frame = recordedPcm!!
                var offset = 0
                while (!stopped.get() && offset < frame.size) {
                    evidence.phase(MicrophoneDiagnostics.Phase.RECORD_READ)
                    val readStartedNs = System.nanoTime()
                    val n = try { recorder.read(frame, offset, frame.size - offset) }
                        catch (error: Exception) { evidence.readException(System.nanoTime() - readStartedNs); throw error }
                    evidence.read(n, System.nanoTime() - readStartedNs)
                    if (n < 0) {
                        if (!stopped.get()) log("麦克风读取失败：AudioRecord代码=$n")
                        throw java.io.IOException("AudioRecord read failed")
                    }
                    if (n == 0) {
                        diagnostics.microphoneZeroReads.incrementAndGet()
                        java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L)
                        continue
                    }
                    diagnostics.microphoneReadBytes.addAndGet(n.toLong()); offset += n
                }
                frame
                }
                if (stopped.get()) break
                if (captureEnabled && evidence.pcm(pcm)) diagnostics.microphoneNonZeroFrames.incrementAndGet()
                evidence.phase(MicrophoneDiagnostics.Phase.ENCODE)
                val encodeStartedNs = System.nanoTime()
                val units = encoder?.encode(pcm) ?: listOf(MicrophonePacketizer.toWirePcm(pcm))
                if (encoder != null) evidence.encoded(units.size, System.nanoTime() - encodeStartedNs)
                for (unit in units) {
                evidence.phase(MicrophoneDiagnostics.Phase.PACKET_SEAL)
                val packet = MicrophonePacketizer.sealPacket(config.key, config.payloadType, counters, unit, config.samplesPerPacket)
                evidence.phase(MicrophoneDiagnostics.Phase.UDP_SEND)
                val sendStartedNs = System.nanoTime()
                try { network.send(DatagramPacket(packet, packet.size, config.host, config.port)) }
                    catch (error: Exception) { evidence.sendFailed(System.nanoTime() - sendStartedNs); throw error }
                evidence.sent(packet.size, System.nanoTime() - sendStartedNs)
                val sent = diagnostics.microphoneSentPackets.incrementAndGet()
                if (sent == 1L) log("麦克风首个上行包已发送；capture=$captureEnabled syntheticSilence=${!captureEnabled}；非零帧=${diagnostics.microphoneNonZeroFrames.get()}（发送不等于语音已识别；无录音保存）")
                }
            }
        } catch (e: Exception) {
            if(sourceOpening && !stopped.get()) runCatching {sourceFailed(selectedSource)}
            if (!stopped.get()) { failed = true; evidence.failure(e); log("麦克风失败：${e.javaClass.simpleName}") }
        }
        finally {
            silence?.stop(); silence=null
            val resources = synchronized(resourceLock) { (record to socket).also { record = null; socket = null } }
            try { resources.first?.stop() } catch (error: Exception) { evidence.cleanupFailed("record-stop", error) }
            try { resources.first?.let { evidence.recordingState(it.recordingState) } } catch (_: Exception) {}
            var recordReleased=true
            try { resources.first?.release() } catch (error: Exception) { recordReleased=false;evidence.cleanupFailed("record-release", error) }
            if(recordReleased) runCatching {microphoneLease?.close()}
            else resources.first?.let { FeatureLearning.retireFailedLiveMicrophone(it,microphoneLease) }
            try { resources.second?.close() } catch (error: Exception) { evidence.cleanupFailed("socket-close", error) }
            try { encoder?.close() } catch (error: Exception) { evidence.cleanupFailed("encoder-close", error) }
            evidence.finish(if (failed) "error" else if (stopped.get()) "stopped" else "completed")
            log("麦克风上行结束：读取字节=${diagnostics.microphoneReadBytes.get()}；非零帧=${diagnostics.microphoneNonZeroFrames.get()}；发送包=${diagnostics.microphoneSentPackets.get()}")
        }
    }
}
