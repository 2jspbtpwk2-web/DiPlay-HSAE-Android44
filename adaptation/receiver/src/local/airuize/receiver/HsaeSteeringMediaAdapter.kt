package local.airuize.receiver

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlaySession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Optional HSAE-only fallback. Every logcat is a finite snapshot, never a persistent ROOT reader. */
class HsaeSteeringMediaAdapter(context:Context, private val currentSession:()->AirPlaySession?,
    private val mediaActive:()->Boolean) : Closeable {
    private val app=context.applicationContext
    private val stopped=AtomicBoolean()
    private val source=HsaeFiniteKeySnapshot()
    private val policy=HsaeMediaKeyPolicy()
    private var learningBoundary=SteeringLearning.boundary
    private val worker=Thread({ run() },"legacy-hsae-media-keys").apply { isDaemon=true;start() }
    override fun close() {
        if(!stopped.compareAndSet(false,true)) return
        worker.interrupt();source.close()
    }
    private fun enabled()=app.getSharedPreferences("receiver-ui",Context.MODE_PRIVATE).getBoolean("steeringMedia",false)
    private fun run() {
        if(!HsaeFiniteKeySnapshot.supported()) { SteeringMediaDiagnostics.event("unsupported-firmware；未启动ROOT读取");return }
        SteeringMediaDiagnostics.event("enabled=true；scope=HSAE；有限ROOT快照；关闭设置立即停止，启用需重连")
        var selected:AirPlaySession?=null
        var wasEligible=false
        try {
            while(!stopped.get() && enabled()) {
                val session=currentSession()
                val eligible=session!=null && mediaActive() && !VoiceKeyJournal.isCapturing() && !SteeringLearning.isLearning
                if(session!==selected || eligible!=wasEligible || learningBoundary!=SteeringLearning.boundary) {
                    learningBoundary=SteeringLearning.boundary
                    selected=session;wasEligible=eligible;policy.reset(session,System.currentTimeMillis())
                }
                if(eligible) snapshot(session!!)
                Thread.sleep(600)
            }
        } catch(_:InterruptedException) { Thread.currentThread().interrupt() }
        catch(error:Exception) { if(!stopped.get()) SteeringMediaDiagnostics.failure(error.javaClass.simpleName) }
        finally { stopped.set(true);source.close();SteeringMediaDiagnostics.event("reader stopped；本轮有限snapshot已关闭；无常驻ROOT logcat") }
    }
    private fun snapshot(session:AirPlaySession) {
        SteeringMediaDiagnostics.poll()
        try { source.read({ !stopped.get() && !SteeringLearning.isLearning }) { event ->
            if(learningBoundary!=SteeringLearning.boundary) {
                learningBoundary=SteeringLearning.boundary;policy.reset(currentSession(),System.currentTimeMillis());return@read
            }
            SteeringMediaDiagnostics.parsed()
            val index=policy.accept(event,currentSession(),System.currentTimeMillis(),SystemClock.elapsedRealtime(),
                enabled(),mediaActive(),VoiceKeyJournal.isCapturing() || SteeringLearning.isLearning) ?: return@read
            val mapped=SteeringLearning.translate(app,index) ?: return@read
            if(!stopped.get() && source.deliveryAllowed && currentSession()===session && enabled() && mediaActive() &&
                !VoiceKeyJournal.isCapturing() && !SteeringLearning.isLearning) {
                SteeringMediaDiagnostics.attempted(mapped,session.sendMedia(mapped))
            }
        } } finally { SteeringMediaDiagnostics.source(source.summary()) }
    }
}

/** Shared finite source for normal forwarding and the explicitly started learning window. */
internal class HsaeFiniteKeySnapshot : Closeable {
    private val stopped=AtomicBoolean()
    private val channel=HsaeKeyRootChannel()
    @Volatile private var permitted=false
    @Volatile private var state="IDLE"
    @Volatile private var polls=0L
    @Volatile private var completed=0L
    @Volatile private var lines=0L
    @Volatile private var parsed=0L
    @Volatile private var fresh=0L
    @Volatile private var truncated=0L
    private val beganWall=System.currentTimeMillis()
    private val seen=LinkedHashSet<String>()
    val deliveryAllowed: Boolean get() = !stopped.get() && permitted
    fun summary():String="source=$state polls=$polls completed=$completed lines=$lines parsed=$parsed freshUnique=$fresh truncated=$truncated"
    fun statusText():String=when(state) {
        "IDLE" -> "准备读取方向盘信号"
        "READING" -> if(completed==0L) "正在申请ROOT并检查按键信号，请允许授权" else "正在读取按键信号"
        "READY" -> if(fresh==0L) "读取正常，尚未看到本次切歌信号；请短按并松开" else "读取正常，本次已收到 $fresh 条按下／松开记录"
        "ROOT_UNAVAILABLE","ROOT_NOT_GRANTED" -> "未取得ROOT授权，无法读取原厂方向盘信号；请允许ROOT后重试"
        "TIMEOUT" -> "读取超时；未保存，请重试或导出问题报告"
        "LOGCAT_FAILED" -> "系统日志读取失败；请导出问题报告"
        "CLOSED","INTERRUPTED" -> "按键读取已停止"
        else -> "按键读取失败；请导出问题报告"
    }
    override fun close() {
        if(!stopped.compareAndSet(false,true)) return
        permitted=false;channel.close()
    }
    fun read(eligible:()->Boolean, onEvent:(HsaeMediaKeyParser.Event)->Unit) {
        if(stopped.get() || !eligible()) return
        polls++;state="READING"
        try {
            // KitKat's -t limit applies before the tag filter. During active playback a
            // 64-line global tail can omit a key immediately. Drain the finite tag dump;
            // the private channel bounds memory and retains the newest output instead.
            val text=channel.snapshot();completed++;state="READY"
            if(text.contains("[ROOT output truncated;")) truncated++
            if(stopped.get() || !eligible()) return
            permitted=true
            val now=System.currentTimeMillis()
            val freshWindow=HsaeKeyFreshWindow(now)
            text.lineSequence().forEach { line ->
                lines++
                if(stopped.get() || !eligible()) return
                if(!freshWindow.includes(line)) return@forEach
                val event=HsaeMediaKeyParser.parse(line,now) ?: return@forEach
                parsed++
                if(event.wallMillis>=beganWall && now-event.wallMillis in 0L..1800L && seen.add(event.token)) {
                    fresh++;while(seen.size>128) seen.remove(seen.first())
                }
                onEvent(event)
            }
        } catch(error:HsaeKeyRootChannel.Failure) {
            state=error.problem.name;throw error
        } catch(error:Exception) {
            state=if(stopped.get()) "CLOSED" else "READ_FAILED";throw error
        } finally {
            permitted=false
        }
    }
    companion object {
        fun supported():Boolean=(Build.MANUFACTURER+" "+Build.BRAND+" "+Build.PRODUCT+" "+Build.DEVICE+" "+Build.MODEL)
            .toLowerCase(java.util.Locale.US).let { it.contains("hsae") || it.contains("nx4c_avnt_imx6q") }
    }
}
