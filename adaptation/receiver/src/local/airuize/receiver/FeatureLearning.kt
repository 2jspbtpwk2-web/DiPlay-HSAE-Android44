package local.airuize.receiver

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.SystemClock
import com.shilapi.xcertplay.transport.Iap2LocationDiagnostics
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Explicit, device-scoped optional calibration. This object never enables a permission/provider. */
object FeatureLearning {
    private const val FILE = "receiver-feature-learning-v1"
    private val lock = Any()
    private val processToken = System.nanoTime()
    private val microphoneGate=FeatureLearningPolicy.MicrophoneLeaseGate()
    private val retiredLock=Any()
    private class RetiredMic(val record:AudioRecord,val lease:Closeable?,var attempts:Int=0,var error:String="")
    private val retired=ArrayList<RetiredMic>()
    private val retirementFault=AtomicBoolean()
    private var retirementWorker=false
    @Volatile private var lastCleanupFailure="无"
    @Volatile private var active: AudioJob? = null
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key() = LearningDevice.id().take(80)+"."
    private fun purpose(value: String) = value.toLowerCase(Locale.US)
    fun isCalibrating(): Boolean = active != null
    /** A new connection can cancel optional local capture before it acquires media resources. */
    fun cancelCalibration() { active?.close() }
    /** No wait: null excludes only this optional capture while calibration still owns its recorder. */
    fun tryAcquireLiveMicrophone():Closeable? {
        if(retirementFault.get()) return null
        val lease=microphoneGate.tryLive() ?: return null
        if(retirementFault.get()) { lease.close();return null }
        return lease
    }
    /** Only failed release enters here. Fault containment refuses new recorders without waiting.
     * Retained owners are bounded by leases already issued before the first fault. One worker,
     * no task queue, at most three background release retries per owner, no Activity or PCM. */
    fun retireFailedLiveMicrophone(record:AudioRecord,lease:Closeable?) {
        synchronized(retiredLock) {
            retirementFault.set(true)
            if(retired.any { it.record===record }) return
            retired.add(RetiredMic(record,lease))
            if(retirementWorker) return
            retirementWorker=true
        }
        try {
            Thread({
                while(true) {
                    val item=synchronized(retiredLock) {
                        val pending=retired.firstOrNull { it.attempts<3 }
                        if(pending==null) {
                            retirementWorker=false
                            if(retired.isEmpty()) retirementFault.set(false)
                            null
                        } else { pending.attempts++;pending }
                    } ?: break
                    if(item.attempts>1) try { Thread.sleep(100) } catch(_:InterruptedException) {}
                    runCatching { if(item.record.recordingState==AudioRecord.RECORDSTATE_RECORDING) item.record.stop() }
                    var released=false
                    try { item.record.release();released=true }
                    catch(error:Exception) {
                        item.error=FeatureLearningPolicy.safeType(error.javaClass.simpleName)
                        lastCleanupFailure="RetiredRecord-"+item.error
                    }
                    if(released) {
                        runCatching { item.lease?.close() }
                        synchronized(retiredLock) { retired.remove(item) }
                    }
                }
            },"feature-mic-retirement").apply {isDaemon=true;start()}
        } catch(error:Exception) {
            synchronized(retiredLock) { retirementWorker=false }
            lastCleanupFailure="RetirementWorker-"+FeatureLearningPolicy.safeType(error.javaClass.simpleName)
        }
    }
    private fun retirementSnapshot():String=synchronized(retiredLock) {
        "待清理实际MIC=${retired.size} 单worker=$retirementWorker 三次重试后仍持有=${retired.count { it.attempts>=3 }} 新录音暂拒绝=${retirementFault.get()}"
    }
    fun microphoneSourceFailed(context:Context, forPurpose:String, source:Int) { try {
        val p=prefs(context);val k=key();val name=purpose(forPurpose)
        if(name in listOf("speechrecognition","telephony") && p.getInt(k+"source-"+name,-1)==source) {
            p.edit().putBoolean(k+"confirmed-"+name,false).putBoolean(k+"candidate-"+name,false)
                .putInt(k+"source-failed-"+name,source).apply()
        }
    } catch(_:Exception) {} }

    fun resolvedMicrophoneSource(context: Context, purpose: String, defaultSource: Int): Int = try {
        val p = prefs(context); val k = key(); val name = purpose(purpose)
        val source = p.getInt(k+"source-"+name, -1)
        FeatureLearningPolicy.source(name, defaultSource, p.getBoolean(k+"mic-enabled", false),
            p.getBoolean(k+"confirmed-"+name, false) || p.getBoolean(k+"candidate-"+name, false), source,
            loadPcm(p, k, source, false), loadPcm(p, k, source, true))
    } catch(_: Exception) { defaultSource }

    fun resolvedAudioBuffer(context: Context, settings: ReceiverSettings): Int = try {
        val p = prefs(context); val k = key()
        FeatureLearningPolicy.buffer(settings.bufferMillis, p.getInt(k+"audio-original", -1),
            p.getInt(k+"audio-buffer", -1), p.getBoolean(k+"audio-enabled", false))
    } catch(_: Exception) { settings.bufferMillis }

    fun rememberBuffer(context: Context, selected: Int) {
        require(selected in FeatureLearningPolicy.audioBuffers)
        val k = key(); val original = ReceiverSettings.read(context).bufferMillis
        prefs(context).edit().putInt(k+"audio-original", original).putInt(k+"audio-buffer", selected)
            .putBoolean(k+"audio-enabled", true).apply()
    }
    fun revokeAudioBuffer(context:Context) {
        prefs(context).edit().putBoolean(key()+"audio-enabled",false).apply()
    }
    fun disableOverrides(context: Context) {
        val k = key(); prefs(context).edit().putBoolean(k+"audio-enabled", false)
            .putBoolean(k+"mic-enabled", false).apply()
    }
    /** Explicit trial, not a claim that Siri or a real call has passed. Capture preference is untouched. */
    fun stageMicrophone(context: Context, source: Int, forPurpose: String): Boolean = try {
        val p=prefs(context);val k=key();val name=purpose(forPurpose)
        if(source !in FeatureLearningPolicy.microphoneSources || name !in listOf("speechrecognition","telephony") ||
            FeatureLearningPolicy.source(name,-1,true,true,source,
                loadPcm(p,k,source,false),loadPcm(p,k,source,true))!=source) false
        else {
            p.edit().putInt(k+"source-"+name,source).putBoolean(k+"candidate-"+name,true)
                .putBoolean(k+"confirmed-"+name,false).putBoolean(k+"mic-enabled",true).apply()
            true
        }
    } catch(_:Exception) { false }
    fun resetMicrophoneOverrides(context: Context) {
        val k=key();val e=prefs(context).edit().putBoolean(k+"mic-enabled",false)
        for(name in listOf("speechrecognition","telephony"))
            e.putBoolean(k+"candidate-"+name,false).putBoolean(k+"confirmed-"+name,false)
        e.apply()
    }
    fun microphoneTrialSummary(context: Context, source: Int): String = try {
        val p=prefs(context);val k=key()
        listOf("speechrecognition" to "Siri", "telephony" to "通话").joinToString("；") { (name,label) ->
            val selected=p.getInt(k+"source-"+name,-1)==source && p.getBoolean(k+"mic-enabled",false)
            "$label："+when {
                !selected -> "未采用此音源"
                p.getBoolean(k+"confirmed-"+name,false) -> "已确认实车有效"
                p.getBoolean(k+"candidate-"+name,false) -> "下次连接试用，等待实车确认"
                else -> "使用默认音源"
            }
        }
    } catch(_:Exception) { "音源档案暂不可读，采用默认音源" }
    fun confirmMicrophone(context: Context, source: Int, forPurpose: String): Boolean = try {
        val p = prefs(context); val k = key(); val name = purpose(forPurpose)
        val staged=p.getInt(k+"source-"+name,-1)==source &&
            (p.getBoolean(k+"candidate-"+name,false) || p.getBoolean(k+"confirmed-"+name,false))
        if(source !in FeatureLearningPolicy.microphoneSources || name !in listOf("speechrecognition","telephony") ||
            !staged || FeatureLearningPolicy.source(name, -1, true, true, source,
                loadPcm(p, k, source, false), loadPcm(p, k, source, true)) != source) false
        else {
            p.edit().putInt(k+"source-"+name, source).putBoolean(k+"confirmed-"+name, true)
                .putBoolean(k+"candidate-"+name,false)
                .putBoolean(k+"mic-enabled", true).apply()
            true
        }
    } catch(_:Exception) { false }
    private fun pcmKey(k: String, source: Int, speech: Boolean) = k+"mic$source-"+if(speech) "speech-" else "quiet-"
    private fun savePcm(context: Context, source: Int, speech: Boolean, data: FeatureLearningPolicy.PcmEvidence) {
        val k = key(); val b = pcmKey(k, source, speech); val e = prefs(context).edit()
        e.putLong(b+"reads", data.reads).putLong(b+"bytes", data.bytes).putLong(b+"samples", data.samples)
            .putInt(b+"peak", data.peak).putLong(b+"squares", java.lang.Double.doubleToLongBits(data.squares))
            .putLong(b+"zeros", data.zeros).putLong(b+"clipped", data.clipped)
            .putLong(b+"errors", data.readErrors).putString(b+"error", data.error)
            .putLong(b+"when", System.currentTimeMillis())
        // New measurements invalidate all confirmation relying on this source.
        for(name in listOf("speechrecognition", "telephony"))
            if(prefs(context).getInt(k+"source-"+name, -1) == source)
                e.putBoolean(k+"confirmed-"+name, false).putBoolean(k+"candidate-"+name,false)
        e.apply()
    }
    private fun loadPcm(p: SharedPreferences, k: String, source: Int, speech: Boolean): FeatureLearningPolicy.PcmEvidence {
        val b = pcmKey(k, source, speech)
        return FeatureLearningPolicy.PcmEvidence(p.getLong(b+"reads", 0), p.getLong(b+"bytes", 0),
            p.getLong(b+"samples", 0), p.getInt(b+"peak", 0),
            java.lang.Double.longBitsToDouble(p.getLong(b+"squares", 0)), p.getLong(b+"zeros", 0),
            p.getLong(b+"clipped", 0), p.getLong(b+"errors", 0), p.getString(b+"error", "") ?: "")
    }
    fun microphoneSummary(context: Context, source: Int): String = try {
        val p = prefs(context); val k = key()
        "音源$source 已存采样：\n安静：${pcmSummary(loadPcm(p,k,source,false))}\n说话：${pcmSummary(loadPcm(p,k,source,true))}"
    } catch(_:Exception) { "音源$source：档案读取失败，采用原音源" }
    fun microphoneCaptureSummary(context: Context, source: Int): String = try {
        val p=prefs(context);val k=key()
        fun describe(speech:Boolean):String {
            val value=loadPcm(p,k,source,speech)
            return when {
                value.reads==0L && value.readErrors==0L -> "未采样"
                !value.usable -> "采样无效或读取失败，请重试"
                speech && value.allZero -> "未收到说话声音，请重试或换音源"
                value.clipping -> "声音严重破音，请放低音量或换音源"
                else -> "采样完成"
            }
        }
        "安静3秒：${describe(false)}\n说话3秒：${describe(true)}\n采样完成仅说明能采到声音；Siri和通话效果还需连接手机验证。"
    } catch(_:Exception) { "采样档案暂不可读，请重新采样。" }
    fun pcmSummary(p: FeatureLearningPolicy.PcmEvidence): String =
        "read=${p.reads}/${p.bytes}字节 样本=${p.samples} peak=${p.peak} rms=${String.format(Locale.US,"%.1f",p.rms)} 全零=${p.allZero} 削波超1%=${p.clipping} 错误=${p.readErrors}/${p.error.ifEmpty { "无" }}"

    fun gpsAllowed(context: Context): Boolean = try {
        context.getSharedPreferences("receiver-ui",Context.MODE_PRIVATE).getBoolean("vehicleGps",true) &&
            context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    } catch(_: Exception) { false }

    private fun readGps(p: SharedPreferences, b: String) = FeatureLearningPolicy.GpsQuality(
        p.getLong(b+"id", 0), p.getLong(b+"callbacks", 0), p.getLong(b+"fresh", 0),
        p.getLong(b+"rejected", 0), p.getLong(b+"errors", 0), p.getLong(b+"accuracySamples", 0),
        java.lang.Double.longBitsToDouble(p.getLong(b+"accuracyTotal", 0)),
        p.getFloat(b+"best", -1f), p.getFloat(b+"worst", -1f))
    private fun saveGps(context: Context, category: String, q: FeatureLearningPolicy.GpsQuality) {
        val p = prefs(context); val b = key()+category+"-"
        if(p.getLong(b+"process",0) == processToken && !FeatureLearningPolicy.newGpsEvidence(readGps(p,b), q)) return
        p.edit().putLong(b+"id", q.runId).putLong(b+"callbacks", q.callbacks).putLong(b+"fresh", q.fresh)
            .putLong(b+"rejected", q.rejected).putLong(b+"errors", q.errors)
            .putLong(b+"accuracySamples", q.accuracySamples)
            .putLong(b+"accuracyTotal", java.lang.Double.doubleToLongBits(q.accuracyTotal))
            .putFloat(b+"best", q.bestAccuracy).putFloat(b+"worst", q.worstAccuracy)
            .putLong(b+"when", System.currentTimeMillis()).putLong(b+"process",processToken).apply()
    }
    fun rememberGpsProbe(context: Context, probe: LegacyGpsProbe) { saveGps(context, "gps-guided", probe.quality()); observePassiveGps(context) }
    /** Passive aggregation only; no requestLocationUpdates or additional collection is made here. */
    fun observePassiveGps(context: Context) { try {
        LocationDiagnostics.latestGpsQuality()?.let {
            if(it.callbacks>0 || it.errors>0 || it.rejected>0) saveGps(context, "gps-passive", it)
        }
        val u = Iap2LocationDiagnostics.latestUsage() ?: return
        val p = prefs(context); val b = key()+"gps-phone-"
        if(p.getLong(b+"process",0) == processToken && p.getLong(b+"id", 0) == u.reporterId && p.getLong(b+"requests", 0) == u.requestCount &&
            p.getLong(b+"sends", 0) == u.successfulSends && p.getLong(b+"errors", 0) == u.providerErrorCount+u.sendErrorCount &&
            p.getLong(b+"stops", 0) == u.stopRequestCount) return
        p.edit().putLong(b+"id", u.reporterId).putLong(b+"requests", u.requestCount)
            .putLong(b+"sends", u.successfulSends).putLong(b+"errors", u.providerErrorCount+u.sendErrorCount)
            .putLong(b+"stops", u.stopRequestCount)
            .putBoolean(b+"ever-requested",p.getBoolean(b+"ever-requested",false) || u.requestCount>0)
            .putBoolean(b+"ever-sent",p.getBoolean(b+"ever-sent",false) || u.successfulSends>0)
            .putLong(b+"when", System.currentTimeMillis()).putLong(b+"process",processToken).apply()
    } catch(_: Exception) {} }

    fun snapshot(context: Context): String = try {
        val p = prefs(context); val k = key(); val original = ReceiverSettings.read(context).bufferMillis
        fun gps(category: String): String {
            val q = readGps(p, k+category+"-")
            return "实例=${q.runId} 回调=${q.callbacks} 新鲜=${q.fresh} 拒绝=${q.rejected} 错误=${q.errors} 精度均/优/差米=${String.format(Locale.US,"%.1f",q.meanAccuracy)}/${q.bestAccuracy}/${q.worstAccuracy}"
        }
        "功能学习：设备=${LearningDevice.label()}；录音/试听或待清理=${isCalibrating()}；MIC资源门=${microphoneGate.state()}（-1=校准，正数=实际上行数）；末清理异常=$lastCleanupFailure；${retirementSnapshot()}\n"+
            "GPS主动向导（最多30秒）[${gps("gps-guided")}]\nGPS已有会话聚合[${gps("gps-passive")}]\n"+
            "手机定位上次存档层次：请求=${p.getLong(k+"gps-phone-requests",0)} 成功发送=${p.getLong(k+"gps-phone-sends",0)} 停止=${p.getLong(k+"gps-phone-stops",0)} 错误=${p.getLong(k+"gps-phone-errors",0)}；曾请求=${p.getBoolean(k+"gps-phone-ever-requested",false)} 曾成功发送=${p.getBoolean(k+"gps-phone-ever-sent",false)}；不证明iPhone采用\n"+
            microphoneSummary(context,6)+"\n"+microphoneSummary(context,7)+"\n"+
            "MIC覆盖启用=${p.getBoolean(k+"mic-enabled",false)} Siri确认=${p.getBoolean(k+"confirmed-speechrecognition",false)} 电话确认=${p.getBoolean(k+"confirmed-telephony",false)}；Siri候选待确认=${p.getBoolean(k+"candidate-speechrecognition",false)} 电话候选待确认=${p.getBoolean(k+"candidate-telephony",false)}；上次打开失败源Siri/电话=${p.getInt(k+"source-failed-speechrecognition",-1)}/${p.getInt(k+"source-failed-telephony",-1)}；关闭采集时不录音\n"+
            "音乐缓冲：主设置=${original}ms 学习选择=${p.getInt(k+"audio-buffer",-1)}ms 下次采用=${resolvedAudioBuffer(context,ReceiverSettings.read(context))}ms；主设置改动优先；不改变语音/导航缓冲\n"+
            "试听：写入=${p.getLong(k+"tone-bytes",0)}字节 播放头推进=${p.getBoolean(k+"tone-head",false)} 用户确认车机出声=${p.getBoolean(k+"tone-heard",false)}；仅本地AudioTrack试听，不证明CarPlay音频路由\n"+
            "仅保存数值，不保存PCM、坐标或轨迹；系统更新后设备键变更回默认；候选设置不改变USB/TLS/配对。"
    } catch(error: Exception) { "功能学习档案读取失败：${FeatureLearningPolicy.safeType(error.javaClass.simpleName)}；采用原设置" }

    fun confirmTone(context: Context, heard: Boolean) {
        val p=prefs(context);val k=key()
        p.edit().putBoolean(k+"tone-heard",heard && p.getBoolean(k+"tone-head",false) && p.getLong(k+"tone-bytes",0)>0).apply()
    }
    private fun allowedAudio(context: Context, connected: () -> Boolean, capture: Boolean): Boolean = try {
        !connected() && (!capture || (context.getSharedPreferences("receiver-ui",Context.MODE_PRIVATE).getBoolean("microphonePcm",false) &&
            context.checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) &&
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode == AudioManager.MODE_NORMAL
    } catch(_:Exception) { false }

    /** Caller must use weak UI callbacks; the job retains application Context only. */
    fun startMicrophone(context: Context, connected: () -> Boolean, source: Int, speech: Boolean,
        done: (String) -> Unit): Closeable? {
        if(source !in FeatureLearningPolicy.microphoneSources) return null
        val app = context.applicationContext
        return startJob(app, connected, true, done) { job ->
            val stats = FeatureLearningPolicy.PcmAccumulator()
            try {
                job.checkAllowed()
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum in 1..131072) { "UnsupportedBuffer" }
                val recorder = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum*2,3200))
                job.attach(recorder)
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "UninitializedRecorder" }
                job.checkAllowed(); recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "NotRecording" }
                val buffer = ByteArray(640)
                val until = SystemClock.elapsedRealtime()+FeatureLearningPolicy.MICROPHONE_WINDOW_MS
                while(SystemClock.elapsedRealtime() < until) {
                    job.checkAllowed()
                    val count = recorder.read(buffer,0,buffer.size)
                    job.checkAllowed()
                    stats.read(buffer,count)
                    if(count <= 0) throw IllegalStateException("RecordRead")
                }
            } catch(error: Exception) { stats.failed(error.javaClass.simpleName) }
            finally { job.releaseAudio();if(job.cleanupFailure.isNotEmpty()) stats.failed("Cleanup-"+job.cleanupFailure) }
            val evidence = stats.evidence()
            if(!job.cancelled.get()) savePcm(app, source, speech, evidence)
            "音源$source ${if(speech) "说话" else "安静"}：${pcmSummary(evidence)}\n未保存录音；电平不等于Siri识别，需连接后确认。"
        }
    }

    fun startTone(context: Context, connected: () -> Boolean, done: (String) -> Unit): Closeable? {
        val app = context.applicationContext
        return startJob(app, connected, false, done) { job ->
            var written = 0L; var advanced = false
            var error = "无"
            try {
                job.checkAllowed()
                val minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum in 1..1048576) { "UnsupportedBuffer" }
                val track = AudioTrack(AudioManager.STREAM_MUSIC,44100,AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,17640),AudioTrack.MODE_STREAM)
                job.attach(track); check(track.state == AudioTrack.STATE_INITIALIZED) { "UninitializedTrack" }
                // Low amplitude plus per-track attenuation; never change a system volume or route.
                track.setStereoVolume(0.25f,0.25f); job.checkAllowed(); track.play()
                val before = track.playbackHeadPosition
                val data = ByteArray(3528) // 20 ms, never a long blocking write.
                for(packet in 0 until 40) {
                    job.checkAllowed()
                    for(frame in 0 until 882) {
                        val v = (sin(2.0*PI*660.0*(packet*882+frame)/44100.0)*0.12*32767).toInt()
                        val i = frame*4
                        data[i]=v.toByte();data[i+1]=(v shr 8).toByte();data[i+2]=data[i];data[i+3]=data[i+1]
                    }
                    var offset=0
                    while(offset<data.size) {
                        job.checkAllowed(); val n=track.write(data,offset,data.size-offset)
                        check(n>0) { "TrackWrite" }; offset+=n;written+=n
                    }
                    Thread.sleep(20)
                }
                Thread.sleep(100);job.checkAllowed();advanced=track.playbackHeadPosition>before
            } catch(t: Exception) { error=FeatureLearningPolicy.safeType(t.javaClass.simpleName) }
            finally { job.releaseAudio() }
            if(!job.cancelled.get()) prefs(app).edit().putLong(key()+"tone-bytes",written)
                .putBoolean(key()+"tone-head",advanced).putBoolean(key()+"tone-heard",false).apply()
            "本地试听：写入=$written 字节，播放头推进=$advanced，错误=$error。\n需要你确认车机是否出声；本结果不能证明iPhone音频路由。"
        }
    }

    private fun startJob(context: Context, connected: () -> Boolean, capture: Boolean, done: (String)->Unit,
        body: (AudioJob)->String): Closeable? {
        val app=context.applicationContext
        val job = synchronized(lock) {
            if(active != null || retirementFault.get() || !allowedAudio(app,connected,capture)) return null
            if(!capture && microphoneGate.state()!=0) return null
            if(!DiagnosticRunState.begin()) return null
            val lease=if(capture) microphoneGate.tryCalibration() else null
            if(capture && lease==null) { DiagnosticRunState.finish();return null }
            AudioJob(app,connected,capture,lease).also { active=it }
        }
        var workerStarted=false
        try {
            Thread({
                var result="校准已取消"
                try { result=body(job) }
                catch(error: Exception) { result="校准失败："+FeatureLearningPolicy.safeType(error.javaClass.simpleName) }
                finally {
                    job.releaseAudio();job.finished.set(true);job.deadline.countDown()
                    finishCleanup(job)
                }
                done(if(job.cancelled.get()) "校准已取消或达到时限；未完成的采样不会作为有效覆盖。" else result)
            },"feature-audio-calibration").apply { isDaemon=true;start() }
            workerStarted=true
            Thread({ try { if(!job.deadline.await(6000,TimeUnit.MILLISECONDS)) job.close() } catch(_:InterruptedException) {} },
                "feature-audio-deadline").apply { isDaemon=true;start() }
        } catch(error: Exception) {
            job.close();if(!workerStarted) {
                job.finished.set(true);finishCleanup(job)
            };return null
        }
        return job
    }
    private fun finishCleanup(job:AudioJob) {
        if(job.cleanupFailure.isNotEmpty()) lastCleanupFailure=job.cleanupFailure
        if(job.finished.get() && !job.hasAudioResources()) {
            job.releaseLease()
            synchronized(lock) {
                if(active===job) {
                    active=null
                    DiagnosticRunState.finish()
                }
            }
        }
    }
    private class AudioJob(private val app:Context, private val connected:()->Boolean,
        private val capture:Boolean,private val lease:Closeable?):Closeable {
        val cancelled=AtomicBoolean(false);val finished=AtomicBoolean(false)
        val deadline=CountDownLatch(1)
        @Volatile var cleanupFailure="";private set
        private val cleanupRequests=AtomicInteger()
        private val resourceLock=Any();private var record:AudioRecord?=null;private var track:AudioTrack?=null
        fun checkAllowed() { check(!cancelled.get() && allowedAudio(app,connected,capture)) { "CancelledOrBusy" } }
        fun attach(value:AudioRecord) { synchronized(resourceLock) { record=value };if(cancelled.get()) releaseAudio() }
        fun attach(value:AudioTrack) { synchronized(resourceLock) { track=value };if(cancelled.get()) releaseAudio() }
        fun releaseAudio() {
            synchronized(resourceLock) {
                val r=record;val t=track
                if(r!=null) {
                    runCatching { if(r.recordingState==AudioRecord.RECORDSTATE_RECORDING) r.stop() }
                        .onFailure { cleanupFailure=FeatureLearningPolicy.safeType(it.javaClass.simpleName) }
                    try { r.release();record=null }
                    catch(error:Exception) { cleanupFailure=FeatureLearningPolicy.safeType(error.javaClass.simpleName) }
                }
                if(t!=null) {
                    runCatching { if(t.playState==AudioTrack.PLAYSTATE_PLAYING) t.stop() }
                        .onFailure { cleanupFailure=FeatureLearningPolicy.safeType(it.javaClass.simpleName) }
                    try { t.release();track=null }
                    catch(error:Exception) { cleanupFailure=FeatureLearningPolicy.safeType(error.javaClass.simpleName) }
                }
            }
        }
        fun hasAudioResources():Boolean=synchronized(resourceLock) { record!=null || track!=null }
        fun releaseLease() { runCatching { lease?.close() } }
        override fun close() {
            cancelled.set(true);deadline.countDown()
            if(cleanupRequests.incrementAndGet()>3) return
            // Vendor stop/read calls can block: cleanup never runs on an Activity/UI thread.
            try { Thread({releaseAudio();finishCleanup(this)},"feature-audio-cancel").apply { isDaemon=true;start() } }
            catch(_:Exception) { /* Worker/deadline retain the cleanup obligation. */ }
        }
    }
}
