package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.CheckBox
import android.widget.Toast

/** User-facing setup, deliberately separate from numeric developer evidence. */
internal class LearningDashboardUi(private val activity:Activity,
    private val managed:(AlertDialog)->AlertDialog,private val wheel:()->Unit,
    private val features:()->Unit,private val export:()->Unit,
    private val settings:()->Unit={},private val gps:()->Unit={},private val backToCarPlay:()->Unit={}) {
    private val ui=ReceiverUiKit(activity,managed)
    fun show() {
        val box=ui.box()
        ui.intro(box,"不必每次都做。画面可自动观察；按键、麦克风和声音需要按提示检查一次，再记住你确认有效的设置。")
        ui.action(box,"自动优化画面","怎么开启、什么时候生效、卡顿后如何回退",true) {performance()}
        ui.action(box,"方向盘切歌","播放 CarPlay 音乐后检查按键；识别不到也能保存排查线索",click=wheel)
        ui.action(box,"麦克风、声音与定位","分步骤检查；麦克风采样和本地试听请先断开手机",click=features)
        ui.section(box,"日常使用")
        ui.action(box,"查看 GPS 状态","已有定位正常时无需重复观察",click=gps)
        ui.action(box,"导出问题简报","把本次适配状态和运行记录发给开发排查",click=export)
        ui.page("功能适配",box)
    }
    fun performance() {
        val box=ui.box()
        val summary=ui.note(box,PerformanceLearning.userSummary())
        var automatic:CheckBox?=null
        var locked:CheckBox?=null
        fun refresh() {
            summary.text=PerformanceLearning.userSummary()
            automatic?.isChecked=PerformanceLearning.isAutomatic();locked?.isChecked=PerformanceLearning.isLocked()
        }
        ui.action(box,"启用自动优化画面","选用自动显示模式、允许下次连接调整画质；不会现在断线",true) {
            val current=ReceiverSettings.read(activity)
            if(current.hevc || current.fps !in 15..30) {
                toast("当前编码或帧率不支持自动优化；请使用兼容画面和 15～30 帧/秒")
            } else {
                activity.getSharedPreferences("receiver-ui",Context.MODE_PRIVATE).edit()
                    .putString("displayMode",LegacyDisplayPolicy.AUTO).apply()
                PerformanceLearning.setAutomatic(true);PerformanceLearning.setLocked(false)
                refresh();toast("已开启；请拔线重连，再回 CarPlay 操作")
            }
        }
        ui.section(box,"照这 3 步做")
        ui.intro(box,"1  开启后拔线再连接 iPhone。\n2  返回 CarPlay，滑动地图或列表约 40 秒，让画面持续变化。\n3  再次查看这里；如建议降低画质，下次连接才会采用。")
        ui.note(box,"停在工具页、诊断中、使用 Siri 或静态画面时，不用这些数据判断卡顿。调整只会逐级降低分辨率；不会自动重连。")
        ui.action(box,"返回 CarPlay 开始观察","关闭工具页面，继续当前投屏",click=backToCarPlay)
        ui.section(box,"我的选择")
        automatic=ui.check(box,"允许自动调整画面","关闭后，下次连接使用自动显示的原始画质。",PerformanceLearning.isAutomatic())
        locked=ui.check(box,"固定现在的画质","暂停自动调整，保留当前或上次实际使用的档位。",PerformanceLearning.isLocked())
        ui.action(box,"效果反馈与恢复","满意可记住；不满意可回退；重置只清除画面适配记录") {actions {refresh()}}
        ui.action(box,"画面设置","手动选择流畅优先、清晰优先或自定义",click=settings)
        ui.action(box,"查看技术详情","供排查使用，普通使用无需查看") {details()}
        ui.page("自动优化画面",box,"保存我的选择") {
            PerformanceLearning.setAutomatic(automatic?.isChecked==true);PerformanceLearning.setLocked(locked?.isChecked==true)
            toast("已保存；下次连接生效")
        }
    }
    private fun actions(changed:()->Unit) {
        val box=ui.box()
        ui.action(box,"当前效果满意，记住这个画质","需要本次或上次已有实际视频样本",true) {
            toast(if(PerformanceLearning.confirmCurrent(true)) "已记住你的确认" else "先使用自动显示连接并操作一会，再确认效果")
            changed()
        }
        ui.action(box,"当前效果不好，回退并固定","下次连接恢复已确认、较稳定或原始画质") {
            if(!PerformanceLearning.confirmCurrent(false)) PerformanceLearning.rollback()
            changed()
            toast("已回退并固定；拔线重连后生效")
        }
        ui.action(box,"清除画面适配记录","保留手机配对和其他设置") {
            managed(AlertDialog.Builder(activity).setTitle("清除画面适配记录？")
                .setMessage("下次从原始自动画质重新观察。当前连接和手机配对不受影响。")
                .setPositiveButton("清除记录") {_,_->PerformanceLearning.reset();changed();toast("画面适配记录已清除")}
                .setNegativeButton("取消",null).create())
        }
        ui.page("画面效果反馈",box)
    }
    private fun details() {
        val box=ui.box();ui.intro(box,"这些是本地处理指标，不能直接当作屏幕实际帧率或手指到画面的延迟。")
        box.addView(ui.text(PerformanceLearning.snapshot(),13f))
        ui.action(box,"导出问题简报","包含这些记录，方便开发排查",click=export)
        ui.page("画面技术详情",box)
    }
    private fun toast(message:String) {Toast.makeText(activity,message,Toast.LENGTH_LONG).show()}
}
