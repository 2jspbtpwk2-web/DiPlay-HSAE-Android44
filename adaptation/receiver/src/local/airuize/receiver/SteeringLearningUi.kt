package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import java.io.Closeable
import java.lang.ref.WeakReference

/** Explicit wizard only. Activity calls onPause/close, so capture never follows it into background. */
class SteeringLearningUi(private val activity: Activity,
    private val showManaged: (AlertDialog) -> AlertDialog,
    private val connected: () -> Boolean) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var run: SteeringLearning.Run? = null
    private var closed = false
    private var foreground = true
    private var state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.IDLE, 0, 0, null, "")
    private var status: TextView? = null
    private var original: CheckBox? = null
    private var single: CheckBox? = null
    private var generation = 0
    /** Keep the Activity's managed OnDismiss listener intact. Every dismissal path,
     * including an external programmatic dismiss while awaiting confirmation, releases
     * our short-lived observer and global forwarding gate before the window closes. */
    private inner class LearningDialog : AlertDialog(activity) {
        override fun dismiss() {
            if (dialog === this) {
                if (run != null) cancelUi()
                dialog = null
                status = null; original = null; single = null
            }
            super.dismiss()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val current = run ?: return
            if (closed || !foreground || dialog?.isShowing != true) { stopRun(); return }
            state = current.snapshot(); render()
            if (state.stage == SteeringLearningPolicy.Stage.NEXT || state.stage == SteeringLearningPolicy.Stage.PREVIOUS)
                handler.postDelayed(this, 250)
        }
    }
    fun show() {
        if (closed || activity.isFinishing) return
        foreground = true
        dialog?.takeIf { it.isShowing }?.let { render(); return }
        if (run == null && state.stage == SteeringLearningPolicy.Stage.READY)
            state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.IDLE, 0, 0, null, "")
        val kit=ReceiverUiKit(activity,showManaged)
        val box=kit.box()
        kit.intro(box,"停车后操作。手机未连接也能学习按键。\n按提示短按方向盘即可，学习时不会向手机发送切歌；原厂音乐仍可能切歌。")
        status=kit.note(box,"").apply { textSize=17f }
        kit.section(box,"识别完成后再启用")
        original=kit.check(box,"原厂按键不能控制CarPlay切歌","请先在CarPlay播放音乐试一次。原厂已能切歌时，无需开启DiPlay适配。",false)
        single=kit.check(box,"启用DiPlay切歌适配","如果一次切两首，请在设置里关闭方向盘切歌。",false)
        original?.setOnCheckedChangeListener { _, _ -> render() }
        single?.setOnCheckedChangeListener { _, _ -> render() }
        val built = LearningDialog().apply {
            setTitle("方向盘切歌 · 按键识别")
            setView(ScrollView(activity).apply { addView(box) })
            setButton(AlertDialog.BUTTON_POSITIVE, "开始学习", null as DialogInterface.OnClickListener?)
            setButton(AlertDialog.BUTTON_NEUTRAL, "关闭并清除", null as DialogInterface.OnClickListener?)
            setButton(AlertDialog.BUTTON_NEGATIVE, "取消", null as DialogInterface.OnClickListener?)
        }
        dialog = showManaged(built)
        built.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { userAction {
            if (state.stage == SteeringLearningPolicy.Stage.READY) {
                val current = run
                if (current != null && original?.isChecked == true && single?.isChecked == true &&
                    SteeringLearning.save(activity, current, true, true)) {
                    run = null; generation++; handler.removeCallbacksAndMessages(null)
                    state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.IDLE, 0, 0, null, "已保存本机映射；需要时可重新学习")
                    Toast.makeText(activity, "已保存本机按键映射并开启适配，请拔线重连后测试；双切请关闭", Toast.LENGTH_LONG).show()
                    built.dismiss(); dialog = null
                } else {
                    stopRun(); state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.FAILED, 0, 0, null, "尚未取得完整映射或保存失败，请重学")
                    render()
                }
            } else startRun()
        } }
        built.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { userAction {
            stopRun()
            val restored = SteeringLearning.restoreDefault(activity)
            state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.IDLE, 0, 0, null,
                if (restored) "已清除本机学习映射，切歌适配已关闭" else "恢复默认未能完整保存，请检查存储后重试")
            original?.isChecked = false; single?.isChecked = false; render()
        } }
        render()
    }
    private fun startRun() {
        if (closed || !foreground || dialog?.isShowing != true) return
        if (VoiceKeyJournal.isCapturing() || DiagnosticRunState.isRunning) { status?.text = "问题报告正在采集，请等采集完成后再识别按键"; return }
        stopRun(); val id = ++generation
        original?.isChecked = false; single?.isChecked = false
        val weak = WeakReference(this)
        val next = SteeringLearning.begin(activity) { value -> weak.get()?.receive(id, value) }
        if (next == null) { status?.text = "已有按键学习任务，请先结束它"; return }
        run = next; state = next.snapshot(); render(); handler.post(tick)
    }
    private fun receive(id: Int, value: SteeringLearningPolicy.Snapshot) {
        if (closed || !foreground) return
        val weak = WeakReference(this)
        handler.post {
            val ui = weak.get()
            if (ui != null && !ui.closed && ui.foreground && id == ui.generation && ui.dialog?.isShowing == true) {
                ui.state = value; ui.render()
            }
        }
    }
    private fun render() {
        val text = when (state.stage) {
            SteeringLearningPolicy.Stage.IDLE -> if (state.reason.isNotEmpty()) state.reason else
                "点“开始学习”，先按下一曲，再按上一曲，各两次。\n${if (SteeringLearning.saved(activity) != null) "已保存本机按键，可重新识别。" else "尚未识别本机按键。"}"
            SteeringLearningPolicy.Stage.NEXT -> "第1/2步：短按“下一曲”两次\n每次松开，间隔1秒。\n已识别 ${state.count}/2 次 · 剩余 ${(state.remainingMillis + 999) / 1000} 秒"
            SteeringLearningPolicy.Stage.PREVIOUS -> "第2/2步：短按“上一曲”两次\n每次松开，间隔1秒。\n已识别 ${state.count}/2 次 · 剩余 ${(state.remainingMillis + 999) / 1000} 秒"
            SteeringLearningPolicy.Stage.READY -> "按键识别完成。\n若原厂只能切它自己的音乐、不能切CarPlay，请勾选下方两项并启用。保存后拔线重连测试。"
            SteeringLearningPolicy.Stage.FAILED, SteeringLearningPolicy.Stage.CANCELLED -> state.reason + "\n未保存新映射，可以重学或取消。"
        }
        status?.text = text + (run?.let { "\n\n"+it.statusText() } ?: "")
        val collecting = state.stage == SteeringLearningPolicy.Stage.NEXT || state.stage == SteeringLearningPolicy.Stage.PREVIOUS
        val ready = state.stage == SteeringLearningPolicy.Stage.READY
        original?.isEnabled = ready; single?.isEnabled = ready
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            this.text = if (collecting) "正在采集" else if (ready) "确认并启用" else if (state.stage == SteeringLearningPolicy.Stage.IDLE) "开始学习" else "重新学习"
            isEnabled = !collecting && (!ready || original?.isChecked == true && single?.isChecked == true)
        }
    }
    private fun userAction(block:()->Unit) {
        try { block() } catch(_:Exception) {
            stopRun()
            state=SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.FAILED,0,0,null,"操作未完整完成，请检查切歌开关后重试；仍失败时导出问题报告")
            render()
            Toast.makeText(activity,"操作未完成，请重试",Toast.LENGTH_LONG).show()
        }
    }
    private fun stopRun() {
        generation++;val current=run;run=null
        runCatching { current?.close() }
        handler.removeCallbacksAndMessages(null)
    }
    private fun cancelUi() {
        stopRun(); state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.CANCELLED, 0, 0, null, "已取消，未保存新映射")
    }
    fun onPause() {
        foreground = false
        if (run != null) {
            stopRun(); state = SteeringLearningPolicy.Snapshot(SteeringLearningPolicy.Stage.CANCELLED, 0, 0, null, "应用已离开前台，学习已停止")
            render()
        }
        dialog?.dismiss(); dialog = null
        status = null; original = null; single = null
    }
    override fun close() {
        if (closed) return
        closed = true; foreground = false; stopRun(); dialog?.dismiss(); dialog = null
        status = null; original = null; single = null
    }
}
