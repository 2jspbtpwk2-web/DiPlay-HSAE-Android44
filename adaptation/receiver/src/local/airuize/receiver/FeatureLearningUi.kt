package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import java.io.Closeable
import java.lang.ref.WeakReference

/** Owned by one Activity. close on pause/destroy; workers do not retain an Activity or Window. */
class FeatureLearningUi(private val activity: Activity,
    private val showManaged: (AlertDialog)->AlertDialog,
    private val connected: ()->Boolean): Closeable {
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    private var operation:Closeable?=null
    private var progress:ProgressDialog?=null
    private var gps:LegacyGpsProbe?=null
    private var gpsTick:Runnable?=null
    private var gpsDeadline=0L
    private var operationGeneration=0L
    private val ui=ReceiverUiKit(activity,showManaged)
    private var lastAudioDetail="尚未进行本地采样或试听"
    private val microphoneStatus=HashMap<Int,WeakReference<TextView>>()
    private val microphoneCapture=HashMap<Int,WeakReference<TextView>>()
    private fun refreshMicrophone(source:Int) {
        microphoneStatus[source]?.get()?.text=FeatureLearning.microphoneTrialSummary(activity.applicationContext,source)
        microphoneCapture[source]?.get()?.text=FeatureLearning.microphoneCaptureSummary(activity.applicationContext,source)
    }
    private fun visible() = !closed && !activity.isFinishing
    private fun show(builder:AlertDialog.Builder):AlertDialog? = if(visible()) showManaged(builder.create()) else null
    private fun note(title:String, text:String) { show(AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("知道了",null)) }
    private class ProgressDialog(activity:Activity,private val cancelled:()->Unit):AlertDialog(activity) {
        var completed=false
        private var ending=false
        override fun dismiss() {
            if(ending) return
            ending=true
            if(!completed) cancelled()
            super.dismiss()
        }
    }
    private fun openProgress(title:String,message:String,button:String):ProgressDialog? {
        val dialog=ProgressDialog(activity) { cancelCurrent() }
        dialog.setTitle(title);dialog.setMessage(message)
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE,button) { _,_ -> cancelCurrent() }
        progress=dialog
        return try { showManaged(dialog);dialog } catch(_:Exception) { cancelCurrent();null }
    }
    private fun finishProgress() {
        val old=progress;progress=null
        old?.completed=true;old?.dismiss()
    }

    fun show() {
        if(!visible()) return
        FeatureLearning.observePassiveGps(activity.applicationContext)
        val box=ui.box()
        ui.intro(box,"已正常的功能可以直接用，不必重复检查。下面的工具只检查并记住本机结果，实际 Siri、通话和 CarPlay 音乐需要连接后确认。")
        ui.action(box,"检查车机麦克风","断开手机 → 安静/说话采样 → 选择候选 → 重连试 Siri",true) {showMicrophoneSetup()}
        ui.action(box,"检查车机声音","断开手机后播放一声短提示音，不改变原厂音源") {showSound()}
        ui.action(box,"观察 GPS 接收","定位有问题时，在开阔处停稳观察最多 30 秒") {showGps()}
        ui.action(box,"查看技术详情","完整数值供开发排查") {note("功能技术详情",FeatureLearning.snapshot(activity.applicationContext))}
        ui.page("麦克风、声音与定位",box)
    }

    fun showGps() {if(visible()) gpsIntroduction()}
    fun showMicrophoneSetup() {
        if(!visible()) return
        val box=ui.box()
        ui.note(box,"先在“设置 → 麦克风与 GPS”开启车机麦克风，再断开 iPhone。每套方案采样约 6 秒，不保存录音。")
        ui.intro(box,"1  选择方案，依次保持安静和说话各 3 秒。\n2  选择“下次连接试用”，再重连手机测试 Siri 或通话。\n3  确认使用车机麦克风且识别正常后，记住实测结果。不好用可恢复默认。")
        ui.action(box,"方案 A · 语音输入","先试这套；若 Siri 无法正常识别，再试方案 B",true) {showMicrophone(6)}
        ui.action(box,"方案 B · 通话输入","另一种车机录音方式") {showMicrophone(7)}
        ui.action(box,"恢复麦克风默认音源","撤销候选和确认，不关闭麦克风，不改音乐缓冲") {
            FeatureLearning.resetMicrophoneOverrides(activity.applicationContext)
            refreshMicrophone(6);refreshMicrophone(7)
            note("已恢复默认音源","下次连接使用原来的麦克风音源。已保存的电平记录保留。")
        }
        ui.page("麦克风适配",box)
    }
    fun showSound() {
        if(!visible()) return
        val box=ui.box()
        ui.intro(box,"这一步只检查车机本地能否出声。CarPlay 音乐请在连接后另外播放确认。")
        ui.action(box,"播放车机短提示音","先断开 iPhone 并暂停其他音乐，使用当前车机媒体音量",true) {toneIntroduction()}
        ui.note(box,"音乐卡音：到“设置 → 声音”选择音乐启动缓冲。保存后拔线重连；声音设置统一在该页调整。")
        ui.action(box,"停用旧的缓冲试用记录","下次连接直接使用声音设置中的缓冲值") {
            FeatureLearning.revokeAudioBuffer(activity.applicationContext);note("已恢复声音设置","下次连接直接采用“设置 → 声音”的音乐启动缓冲。")
        }
        ui.action(box,"查看最近测试数值","供排查使用") {note("本地测试详情",lastAudioDetail)}
        ui.page("声音检查",box)
    }

    private fun gpsIntroduction() {
        if(!FeatureLearning.gpsAllowed(activity.applicationContext)) {
            note("请先开启定位","先在“设置 → 麦克风与 GPS”开启“使用车机 GPS”，再确认车机系统定位和定位权限已开启。")
            return
        }
        show(AlertDialog.Builder(activity).setTitle("GPS质量观察")
            .setMessage("请在开阔处停稳。最多观察 30 秒，只保存质量统计，不保存坐标或轨迹。\n已连接时复用现有定位；有位置更新也需要在手机导航中确认效果。")
            .setPositiveButton("开始观察") { _,_ -> startGps() }.setNegativeButton("取消",null))
    }
    private fun startGps() {
        if(operation!=null || gps!=null || !visible() || !FeatureLearning.gpsAllowed(activity.applicationContext)) return
        val probe=LegacyGpsProbe(activity.applicationContext) { }
        gps=probe;gpsDeadline=SystemClock.elapsedRealtime()+FeatureLearningPolicy.GPS_WINDOW_MS
        if(openProgress("正在观察GPS质量","剩余30秒；可随时取消。不会开启GPS或向手机注入位置。","取消观察")==null) return
        try { probe.start(FeatureLearningPolicy.GPS_WINDOW_MS) }
        catch(error:Exception) { cancelCurrent();note("GPS观察失败",FeatureLearningPolicy.safeType(error.javaClass.simpleName));return }
        val tick=object:Runnable {
            override fun run() {
                if(!visible() || gps!==probe) return
                if(!FeatureLearning.gpsAllowed(activity.applicationContext)) {
                    probe.close();gps=null;gpsTick=null;finishProgress()
                    FeatureLearning.rememberGpsProbe(activity.applicationContext,probe)
                    note("GPS观察已停止","应用GPS偏好、系统定位或权限已变化。本次部分数值已保留，不算验证通过。")
                    return
                }
                val left=gpsDeadline-SystemClock.elapsedRealtime()
                if(left<=0) {
                    probe.close();gps=null;gpsTick=null;finishProgress()
                    FeatureLearning.rememberGpsProbe(activity.applicationContext,probe)
                    val q=probe.quality()
                    note("GPS观察结束",(if(q.fresh>0) "已收到 ${q.fresh} 次新位置。" else "这次没有收到新位置，请确认系统定位、权限和开阔环境。")+
                        "\n${if(q.errors>0) "观察中有读取错误，具体数值已保存。" else "观察记录已保存。"}\n之后连接 iPhone，在导航中实际确认定位效果；这一步不能证明手机采用了车机位置。")
                } else {
                    val q=probe.quality()
                    progress?.setMessage("剩余 ${(left+999)/1000} 秒\n"+if(q.fresh>0) "已收到 ${q.fresh} 次新位置。" else "正在等待新位置，请保持在开阔处。")
                    handler.postDelayed(this,1000)
                }
            }
        }
        gpsTick=tick;handler.post(tick)
    }

    private fun showMicrophone(source:Int) {
        if(!visible()) return
        val box=ui.box()
        microphoneStatus[source]=WeakReference(ui.note(box,FeatureLearning.microphoneTrialSummary(activity.applicationContext,source)))
        microphoneCapture[source]=WeakReference(ui.text(FeatureLearning.microphoneCaptureSummary(activity.applicationContext,source)).apply {box.addView(this)})
        ui.intro(box,"安静和说话两段都需要采样。若没有输入、读取失败或严重破音，程序不会保存候选。采样只测电平，不等于 Siri 已识别。")
        ui.action(box,"第 1 步 · 保持安静 3 秒","请先断开 iPhone；暂停车内其他声音",true) {micIntroduction(source,false)}
        ui.action(box,"第 2 步 · 连续说话 3 秒","用正常音量说“打开地图，导航回家”") {micIntroduction(source,true)}
        ui.action(box,"第 3 步 · 下次连接试用此方案","选择用于 Siri 或通话，然后拔线重连实测") {stageMicrophone(source)}
        ui.action(box,"记录实车验证结果","仅在重连测试后，确认车机麦克风识别正常时使用") {confirmMicrophone(source)}
        ui.action(box,"查看采样详情","高级信息 · 不必理解这些数值才能继续") {note("麦克风采样详情",FeatureLearning.microphoneSummary(activity.applicationContext,source))}
        ui.page(if(source==6) "麦克风方案 A" else "麦克风方案 B",box)
    }
    private fun micIntroduction(source:Int,speech:Boolean) {
        show(AlertDialog.Builder(activity).setTitle(if(speech) "准备说话" else "准备保持安静")
            .setMessage(if(speech) "点击开始后，用平时音量连续说话 3 秒，例如“打开地图，导航回家”。只统计本机声音电平，不送给手机或保存声音。"
                else "点击开始后保持安静 3 秒。${if(source==6) "方案 A" else "方案 B"} 会单独采样，不会同时打开其他录音。")
            .setPositiveButton("开始") { _,_ -> startMicrophone(source,speech) }.setNegativeButton("取消",null))
    }
    private fun startMicrophone(source:Int,speech:Boolean) {
        if(operation!=null || gps!=null || !visible()) return
        val weak=WeakReference(this)
        val callback=connected
        val generation=++operationGeneration
        operation=FeatureLearning.startMicrophone(activity.applicationContext,callback,source,speech) { result ->
            weak.get()?.postAudioResult("本地采样结束",result,false,generation,source,speech)
        }
        if(operation==null) { note("校准未开始","需要明确开启麦克风采集与权限、断开CarPlay/通话，并等待其它校准结束；没有更改任何开关。") ;return }
        openProgress(if(speech) "请连续说话 3 秒" else "请保持安静 3 秒",
            "${if(source==6) "方案 A" else "方案 B"} · 关闭页面或连接手机会停止。仅保存电平统计，不保存声音。","取消")
    }
    private fun stageMicrophone(source:Int) {
        show(AlertDialog.Builder(activity).setTitle("下次连接试用哪种用途？")
            .setMessage("两段采样有效后才能保存。保存只是试用候选，下一次连接才采用；请随后实际测试 Siri 或通话。")
            .setPositiveButton("用于 Siri") {_,_->applyMicrophone(source,"speechrecognition",false)}
            .setNeutralButton("用于通话") {_,_->applyMicrophone(source,"telephony",false)}
            .setNegativeButton("取消",null))
    }
    private fun confirmMicrophone(source:Int) {
        show(AlertDialog.Builder(activity).setTitle("记录实际测试结果")
            .setMessage("你需要已保存此方案用于对应功能，并重连实测。确认声音来自车机麦克风且识别或通话正常后再记住；不能只根据本地采样电平确认。")
            .setPositiveButton("Siri 已实测正常") { _,_ -> applyMicrophone(source,"speechrecognition",true) }
            .setNeutralButton("通话已实测正常") { _,_ -> applyMicrophone(source,"telephony",true) }
            .setNegativeButton("尚未确认",null))
    }
    private fun applyMicrophone(source:Int,purpose:String,confirmed:Boolean) {
        val ok=if(confirmed) FeatureLearning.confirmMicrophone(activity.applicationContext,source,purpose)
            else FeatureLearning.stageMicrophone(activity.applicationContext,source,purpose)
        refreshMicrophone(source)
        note(if(ok) {if(confirmed) "已记住实车确认" else "候选已保存，等你实测"} else "这次没有保存",if(ok)
            if(confirmed) "已记住你对该用途的实测确认。不好用时可在“麦克风适配”恢复默认音源。"
            else "下一次连接将在所选用途中试用此方案。请重连并测试 Siri 或通话，再回来记录结果；不好用可恢复默认。"
            else if(confirmed) "请先完成采样并保存此方案用于对应功能，再重连实测。"
            else "请先完成该方案的安静和说话采样。读取失败、没有输入或严重破音时不会保存候选。")
    }

    private fun toneIntroduction() {
        show(AlertDialog.Builder(activity).setTitle("车机本地试听")
            .setMessage("请停稳、断开CarPlay并暂停其它播放。将按当前音乐音量播放低幅短提示音；本工具不改系统音量、音源路由或蓝牙。若音量不合适先手动调整。\n本地试听与iPhone的CarPlay音频验证分别记录。")
            .setPositiveButton("播放短提示音") { _,_ -> startTone() }.setNegativeButton("取消",null))
    }
    private fun startTone() {
        if(operation!=null || gps!=null || !visible()) return
        val weak=WeakReference(this);val callback=connected
        val generation=++operationGeneration
        operation=FeatureLearning.startTone(activity.applicationContext,callback) { result ->
            weak.get()?.postAudioResult("本地试听结果",result,true,generation)
        }
        if(operation==null) { note("试听未开始","先断开CarPlay/通话，并等待其它校准结束。") ;return }
        openProgress("正在播放短提示音","可随时取消；不改变系统音量或路由。","停止")
    }
    private fun postAudioResult(title:String,text:String,tone:Boolean,generation:Long,source:Int=0,speech:Boolean=false) {
        val weak=WeakReference(this)
        handler.post {
            val ui=weak.get() ?: return@post
            if(!ui.visible() || ui.operationGeneration!=generation) return@post
            ui.operation=null;ui.finishProgress()
            ui.lastAudioDetail=text
            if(!tone) ui.refreshMicrophone(source)
            if(tone) ui.show(AlertDialog.Builder(ui.activity).setTitle(title).setMessage("短提示音测试已结束。你是否明确听到车机播放了刚才的提示音？\n这一步只记录本地声音，CarPlay 音乐需要另行播放确认。")
                .setPositiveButton("车机听到了") { _,_ -> FeatureLearning.confirmTone(ui.activity.applicationContext,true) }
                .setNegativeButton("没听到/不能确认") { _,_ -> FeatureLearning.confirmTone(ui.activity.applicationContext,false) })
            else ui.show(AlertDialog.Builder(ui.activity).setTitle(title)
                .setMessage(FeatureLearning.microphoneCaptureSummary(ui.activity.applicationContext,source)+"\n"+
                    if(speech) "接下来选择用于 Siri 或通话，再重连实测。" else "下一步用正常音量连续说话 3 秒。")
                .setPositiveButton(if(speech) "选择试用用途" else "继续说话采样") {_,_->
                    if(speech) ui.stageMicrophone(source) else ui.micIntroduction(source,true)
                }.setNeutralButton("查看详情") {_,_->ui.note("本地采样详情",text)}.setNegativeButton("稍后继续",null))
        }
    }
    private fun cancelCurrent() {
        operationGeneration++
        operation?.close();operation=null
        gpsTick?.let { handler.removeCallbacks(it) };gpsTick=null
        gps?.close();gps=null
        val old=progress;progress=null;old?.dismiss()
    }
    override fun close() {
        if(closed) return
        closed=true;cancelCurrent();handler.removeCallbacksAndMessages(null);microphoneStatus.clear();microphoneCapture.clear()
    }
}
