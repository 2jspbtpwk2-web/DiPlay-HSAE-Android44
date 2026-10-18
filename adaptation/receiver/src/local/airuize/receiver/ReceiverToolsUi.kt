package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog

/** Plain-language problem flows. Callbacks retain the Activity's existing lifecycle guards. */
internal class ReceiverToolsUi(activity:Activity,managed:(AlertDialog)->AlertDialog,private val actions:Actions) {
    data class Actions(val quickReport:()->Unit,val fullDiagnostic:()->Unit,val collectOnly:()->Unit,
        val audioCheck:()->Unit,val gpsStatus:()->Unit,val runtimeLog:()->Unit,
        val siri:()->Unit,val backToCarPlay:()->Unit,val backToVehicle:()->Unit)
    private val ui=ReceiverUiKit(activity,managed)
    fun showDiagnostics() {
        val box=ui.box()
        ui.intro(box,"遇到卡顿、没声音或按键无效，先保留问题现场，再导出报告。报告保存在车机 DiPlay 文件夹。")
        ui.action(box,"导出问题简报","优先使用 · 快速保存本次画面、声音和功能适配记录",true,actions.quickReport)
        ui.section(box,"按问题检查")
        ui.action(box,"检查声音与麦克风","查看声音输出、采集状态；不会替你切换原厂音源",click=actions.audioCheck)
        ui.action(box,"查看车机 GPS 状态","检查定位是否更新、手机是否请求车机位置",click=actions.gpsStatus)
        ui.section(box,"进一步排查")
        ui.action(box,"完整诊断与按键采集","约 3 分钟 · 需要按提示操作，适合持续卡顿或按键无效",click=actions.fullDiagnostic)
        ui.action(box,"只采集完整报告","约 2 分钟 · 不做引导测试，保留当前运行状态",click=actions.collectOnly)
        ui.action(box,"查看运行日志","高级信息 · 供开发排查",click=actions.runtimeLog)
        ui.page("问题排查",box)
    }
    fun showHelp() {
        val box=ui.box()
        ui.note(box,"DiPlay 将 iPhone 的 CarPlay 显示在车机上，支持触摸、音乐、导航和 Siri。")
        ui.section(box,"日常使用")
        ui.intro(box,"1  解锁 iPhone，用数据线连接车机，点击“连接 CarPlay”。\n2  连接后直接操作投屏；“菜单”可返回主页。\n3  改画面、声音、麦克风或 GPS 设置后，拔线再连接。")
        ui.section(box,"功能适配怎么用")
        ui.intro(box,"画面卡顿：开启“自动优化画面”，回到 CarPlay 滑动地图或列表约 40 秒；调整在下次连接生效。\n方向盘：先播放 CarPlay 音乐试按键，无效再按向导检查；一次切两首就关闭转发。\n麦克风：断开手机后采样，再连接实际测试 Siri；声音电平正常不代表识别成功。")
        ui.section(box,"反馈问题")
        ui.intro(box,"在“问题排查”导出问题简报，说明卡顿、出声位置和按键反应。需要时再做完整诊断。")
        ui.action(box,"返回 CarPlay","继续当前投屏",true,actions.backToCarPlay)
        ui.action(box,"呼叫 Siri","需要已连接 iPhone",click=actions.siri)
        ui.action(box,"返回车机桌面","保留当前连接",click=actions.backToVehicle)
        ui.page("使用说明",box)
    }
}
