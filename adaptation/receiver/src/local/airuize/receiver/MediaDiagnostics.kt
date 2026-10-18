package local.airuize.receiver

import java.util.concurrent.atomic.AtomicLong

/** Counts data flow; never stores coordinates, media payloads or user identifiers. */
class MediaDiagnostics(private val nanoTime: () -> Long = System::nanoTime) {
    private val originNs = nanoTime()
    val microphone = MicrophoneDiagnostics { nanoTime() / 1_000_000L }
    val videoReceived = AtomicLong()
    val videoRendered = AtomicLong()
    val decoderStarts = AtomicLong()
    val videoDropped = AtomicLong()
    val videoBytes = AtomicLong()
    val videoQueueCleared = AtomicLong()
    val videoQueuePeak = AtomicLong()
    val videoNoDecoder = AtomicLong()
    val videoInvalid = AtomicLong()
    val videoWaitingIdr = AtomicLong()
    val videoInputTimeouts = AtomicLong()
    val videoInputTooLarge = AtomicLong()
    val videoExpired = AtomicLong()
    val videoDecodedOutputs = AtomicLong()
    val videoSupersededOutputs = AtomicLong()
    val videoMetadataOutputs = AtomicLong()
    val videoCancelledOutputs = AtomicLong()
    val videoOutputReleaseErrors = AtomicLong()
    val videoRecoveryDiscardedOutputs = AtomicLong()
    val videoDrainBudgetYields = AtomicLong()
    val videoInputQueued = AtomicLong()
    private val videoQueueAgeMaxNs = AtomicLong()
    private val learningQueueAgeMaxNs = AtomicLong()
    private val videoInputWaitMaxNs = AtomicLong()
    private val videoInputDequeueMaxNs = AtomicLong()
    private val videoInputQueueMaxNs = AtomicLong()
    private val videoOutputDequeueMaxNs = AtomicLong()
    private val videoOutputReleaseMaxNs = AtomicLong()
    private val videoReceiveGapMaxNs = AtomicLong()
    private val videoOutputGapMaxNs = AtomicLong()
    private val videoRecoveryWaitMaxNs = AtomicLong()
    private val videoRecoveryRequestMaxNs = AtomicLong()
    private val lastVideoReceivedNs = AtomicLong(Long.MIN_VALUE)
    private val lastVideoReleasedNs = AtomicLong(Long.MIN_VALUE)
    private val videoRates = VideoFrameRates(originNs)
    private val videoOutputAges=VideoOutputAgeStats()
    @Volatile var videoDisplayPriorityRequested=false
    @Volatile var videoWorkerPriority:Int?=null
    val decoderStartErrors = AtomicLong()
    val decoderErrors = AtomicLong()
    val recoveryRequests = AtomicLong()
    val recoveryRequestErrors = AtomicLong()
    val audioPackets = AtomicLong()
    val audioWrittenBytes = AtomicLong()
    val audioDropped = AtomicLong()
    val audioNoStream = AtomicLong()
    val audioRtpRejected = AtomicLong()
    val audioInputTimeouts = AtomicLong()
    val audioInputTooLarge = AtomicLong()
    val audioErrors = AtomicLong()
    val audioHeadReadErrors = AtomicLong()
    val audioHeadResets = AtomicLong()
    val audioPlayedFrames = AtomicLong()
    val audioLateOrDuplicate = AtomicLong()
    val audioConcealedFrames = AtomicLong()
    val audioRepairBudgetSkipped = AtomicLong()
    val microphoneReadBytes = AtomicLong()
    val microphoneZeroReads = AtomicLong()
    val microphoneSentPackets = AtomicLong()
    val microphoneNonZeroFrames = AtomicLong()
    private val firstVideoMs = AtomicLong(-1)
    private val firstVideoOutputMs = AtomicLong(-1)
    private val firstAudioMs = AtomicLong(-1)
    private val firstAudioWriteMs = AtomicLong(-1)
    private val firstAudioAdvanceMs = AtomicLong(-1)
    private val lastVideoMs = AtomicLong(-1)
    private val lastVideoOutputMs = AtomicLong(-1)
    private val lastAudioMs = AtomicLong(-1)
    private val lastAudioWriteMs = AtomicLong(-1)
    private val lastAudioAdvanceMs = AtomicLong(-1)
    @Volatile var lastDecoderError = "无"
    @Volatile var lastAudioError = "无"
    @Volatile var decoderName = "无"
    @Volatile var decodedFormat = "未报告"
    @Volatile var decoderActive = false
    @Volatile var waitingForKeyFrame = true

    fun elapsedMs(): Long = ((nanoTime() - originNs) / 1_000_000L).coerceAtLeast(0)
    private fun mark(first: AtomicLong, last: AtomicLong): Boolean {
        val now = elapsedMs(); val initial = first.compareAndSet(-1, now); last.set(now); return initial
    }
    fun receivedVideo(bytes: Int, queued: Int): Boolean {
        videoReceived.incrementAndGet(); videoBytes.addAndGet(bytes.toLong())
        val now = nanoTime()
        videoRates.received(now)
        markGap(lastVideoReceivedNs, videoReceiveGapMaxNs, now)
        val initial = mark(firstVideoMs, lastVideoMs)
        while (true) {
            val old = videoQueuePeak.get()
            if (queued <= old || videoQueuePeak.compareAndSet(old, queued.toLong())) break
        }
        return initial
    }
    fun outputVideo(): Boolean {
        videoRendered.incrementAndGet()
        val now = nanoTime()
        videoRates.output(now)
        markGap(lastVideoReleasedNs, videoOutputGapMaxNs, now)
        return mark(firstVideoOutputMs, lastVideoOutputMs)
    }
    fun queuedVideo() { videoInputQueued.incrementAndGet(); videoRates.queued(nanoTime()) }
    fun videoQueueAge(ageNs: Long) {
        maximum(videoQueueAgeMaxNs, ageNs)
        maximum(learningQueueAgeMaxNs, ageNs)
    }
    /** One optional interval consumer; original cumulative diagnostics remain unchanged. */
    internal fun learningCounters(): LearningTelemetry.Counts = LearningTelemetry.Counts(
        videoReceived.get(), videoRendered.get(), videoBytes.get(), videoInputTimeouts.get(),
        decoderErrors.get() + videoOutputReleaseErrors.get(), recoveryRequests.get(),
        learningQueueAgeMaxNs.getAndSet(0L) / 1_000_000L)
    fun videoInputWait(elapsedNs: Long) = maximum(videoInputWaitMaxNs, elapsedNs)
    fun videoInputDequeue(elapsedNs: Long) = maximum(videoInputDequeueMaxNs, elapsedNs)
    fun videoInputQueue(elapsedNs: Long) = maximum(videoInputQueueMaxNs, elapsedNs)
    fun videoOutputDequeue(elapsedNs: Long) = maximum(videoOutputDequeueMaxNs, elapsedNs)
    fun videoOutputRelease(elapsedNs: Long) = maximum(videoOutputReleaseMaxNs, elapsedNs)
    fun videoDecodedAge(epochNs:Long,presentationTimeUs:Long,nowNs:Long) = videoOutputAges.decoded(epochNs,presentationTimeUs,nowNs)
    fun videoSubmittedAge(epochNs:Long,presentationTimeUs:Long,nowNs:Long) = videoOutputAges.submitted(epochNs,presentationTimeUs,nowNs)
    fun videoAgeSummary():String=videoOutputAges.summary()+
        "；视频线程DISPLAY优先级请求=$videoDisplayPriorityRequested 实际nice=${videoWorkerPriority?.toString() ?: "未读取"}"
    fun videoRecoveryWait(elapsedNs: Long) = maximum(videoRecoveryWaitMaxNs, elapsedNs)
    fun videoRecoveryRequest(elapsedNs: Long) = maximum(videoRecoveryRequestMaxNs, elapsedNs)
    private fun maximum(counter: AtomicLong, value: Long) {
        var old = counter.get()
        while (value > old && !counter.compareAndSet(old, value)) old = counter.get()
    }
    private fun markGap(last: AtomicLong, peak: AtomicLong, now: Long) {
        val before = last.getAndSet(now)
        if (before != Long.MIN_VALUE) maximum(peak, (now - before).coerceAtLeast(0L))
    }
    private fun micros(counter: AtomicLong): Long = counter.get() / 1000L
    private fun videoPerformance(): String = "视频性能（本次接收器累计最大值；释放不证明屏幕实际显示）：入解码=${videoInputQueued.get()}；解码输出缓冲=${videoDecodedOutputs.get()}；解码后合并旧画面=${videoSupersededOutputs.get()}；配置或空EOS输出=${videoMetadataOutputs.get()}；取消释放输出=${videoCancelledOutputs.get()}；输出release异常=${videoOutputReleaseErrors.get()}；超龄恢复=${videoExpired.get()}；恢复丢弃旧输出=${videoRecoveryDiscardedOutputs.get()}；输出排空让步=${videoDrainBudgetYields.get()}；队列年龄MaxUs=${micros(videoQueueAgeMaxNs)}；输入等待MaxUs=${micros(videoInputWaitMaxNs)}；输入dequeueMaxUs=${micros(videoInputDequeueMaxNs)}；输入queueMaxUs=${micros(videoInputQueueMaxNs)}；输出dequeueMaxUs=${micros(videoOutputDequeueMaxNs)}；输出releaseMaxUs=${micros(videoOutputReleaseMaxNs)}；视频到达间隔MaxUs=${micros(videoReceiveGapMaxNs)}；输出释放间隔MaxUs=${micros(videoOutputGapMaxNs)}；等关键帧MaxUs=${micros(videoRecoveryWaitMaxNs)}；异步关键帧请求MaxUs=${micros(videoRecoveryRequestMaxNs)}；${videoRates.snapshot(nanoTime()).summary()}"
    fun receivedAudio(): Boolean { audioPackets.incrementAndGet(); return mark(firstAudioMs, lastAudioMs) }
    fun wroteAudio(bytes: Int): Boolean { audioWrittenBytes.addAndGet(bytes.toLong()); return mark(firstAudioWriteMs, lastAudioWriteMs) }
    fun advancedAudio(frames: Long): Boolean {
        if (frames <= 0) return false
        audioPlayedFrames.addAndGet(frames); return mark(firstAudioAdvanceMs, lastAudioAdvanceMs)
    }
    private fun time(value: AtomicLong): String = value.get().let { if (it < 0) "未发生" else "+${it}ms" }
    fun summary(): String = "媒体统计：观测=${elapsedMs()}ms；视频接收=${videoReceived.get()}；视频字节=${videoBytes.get()}；显示缓冲释放=${videoRendered.get()}；解码器启动=${decoderStarts.get()}；视频入队拒绝=${videoDropped.get()}；视频清队列帧=${videoQueueCleared.get()}；视频队列峰值=${videoQueuePeak.get()}/16；无解码器丢帧=${videoNoDecoder.get()}；无效视频帧=${videoInvalid.get()}；等关键帧跳过=${videoWaitingIdr.get()}；视频输入超时=${videoInputTimeouts.get()}；视频输入过大=${videoInputTooLarge.get()}；解码启动失败=${decoderStartErrors.get()}；解码异常=${decoderErrors.get()}；末次解码异常=$lastDecoderError；关键帧请求=${recoveryRequests.get()}；关键帧请求异常=${recoveryRequestErrors.get()}；音频RTP=${audioPackets.get()}；音频无流拒绝=${audioNoStream.get()}；RTP短包拒绝=${audioRtpRejected.get()}；AudioTrack写入字节=${audioWrittenBytes.get()}；音频入队拒绝=${audioDropped.get()}；音频输入超时=${audioInputTimeouts.get()}；音频输入过大=${audioInputTooLarge.get()}；音频异常=${audioErrors.get()}；播放头读取异常=${audioHeadReadErrors.get()}；播放头复位=${audioHeadResets.get()}；末次音频异常=$lastAudioError；播放头累计推进帧=${audioPlayedFrames.get()}；麦克风读取字节=${microphoneReadBytes.get()}；麦克风零字节读取次数=${microphoneZeroReads.get()}；麦克风非零PCM帧=${microphoneNonZeroFrames.get()}；输入上行UDP发送包（含合成静音）=${microphoneSentPackets.get()}\n" +
        "媒体时间线（相对本次媒体接收器创建 originNanoTime=$originNs）：首视频=${time(firstVideoMs)}；末视频=${time(lastVideoMs)}；首解码输出释放=${time(firstVideoOutputMs)}；末解码输出释放=${time(lastVideoOutputMs)}；首音频RTP=${time(firstAudioMs)}；末音频RTP=${time(lastAudioMs)}；首AudioTrack写入=${time(firstAudioWriteMs)}；末写入=${time(lastAudioWriteMs)}；首播放头推进=${time(firstAudioAdvanceMs)}；末播放头推进=${time(lastAudioAdvanceMs)}（释放不证明屏幕实际显示；播放头推进不证明实际听到声音；无媒体内容保存）\n" +
        "音频补充：迟到或重复RTP拒绝=${audioLateOrDuplicate.get()}；短缺口有界静音修补帧=${audioConcealedFrames.get()}；修补预算不足次数=${audioRepairBudgetSkipped.get()}（不能恢复丢失音乐）\n" + videoPerformance()+"\n"+videoAgeSummary()
}
