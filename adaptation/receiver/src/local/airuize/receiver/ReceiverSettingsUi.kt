package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Settings are grouped by a driver's task; every page saves only the controls it owns. */
internal class ReceiverSettingsUi(private val activity:Activity,private val managed:(AlertDialog)->AlertDialog,
    private val onSaved:()->Unit,private val connected:()->Boolean={false}) {
    private val ui=ReceiverUiKit(activity,managed)
    private val prefs=activity.getSharedPreferences("receiver-ui",Context.MODE_PRIVATE)
    private var summary:TextView?=null
    fun show() {
        val box=ui.box()
        summary=ui.note(box,summaryText())
        ui.action(box,"画面","横竖屏、清晰度、帧率与自定义分辨率",true) { display() }
        ui.action(box,"声音","播放音量、音乐缓冲、导航压低音乐") { sound() }
        ui.action(box,"麦克风与 GPS","允许车机提供语音输入和定位") { inputs() }
        ui.action(box,"方向盘与连接","语音键、切歌转发、开机准备连接") { connection() }
        ui.action(box,"外观","主页车身颜色") { appearance() }
        ui.page("设置",box)
    }
    private fun summaryText():String {
        val settings=ReceiverSettings.read(activity)
        val actual=LegacyWiredService.instance?.currentSettingsSnapshot()?.display()
        return "已保存：${displayName(settings.displayMode)} · ${settings.fps} 帧/秒\n"+
            "麦克风${if(settings.microphone) "开启" else "关闭"} · 车机 GPS${if(settings.gps) "开启" else "关闭"}\n"+
            if(connected()) (if(actual!=null) "当前连接：${actual.widthPixels} × ${actual.heightPixels} · ${actual.fps} 帧/秒\n" else "正在连接或投屏。\n")+
                "修改后请拔线再连接，当前会话保持原设置。"
            else "画面、声音与功能开关在下次连接生效。保存不会自动连接手机。"
    }
    private fun saved() {summary?.text=summaryText();onSaved();toast("已保存；拔线再连接后生效")}
    private fun display() {
        val settings=ReceiverSettings.read(activity)
        val box=ui.box()
        var mode=settings.displayMode
        var fps=settings.fps
        ui.intro(box,"按应用可用区域适配横竖屏，原厂空调和系统保留区域无法由 CarPlay 覆盖。")
        val modeText=ui.note(box,displaySummary(mode,fps))
        var modeRow:android.view.View?=null
        fun drawMode() {modeText.text=displaySummary(mode,fps)}
        modeRow=ui.action(box,"选择画面模式","当前：${displayName(mode)}",true) {
            val choices=arrayOf("自动适配 · 均衡（支持自动优化画面）","流畅优先 · 分辨率较低","清晰优先 · 对车机性能要求较高","自定义 · 使用手动分辨率")
            managed(AlertDialog.Builder(activity).setTitle("画面模式")
                .setSingleChoiceItems(choices,LegacyDisplayPolicy.modes.indexOf(mode)) {dialog,which->
                    mode=LegacyDisplayPolicy.modes[which];drawMode();dialog.dismiss()
                    (modeRow as? LinearLayout)?.let { row->(row.getChildAt(1) as? TextView)?.text="当前：${displayName(mode)}" }
                }.setNegativeButton("取消",null).create())
        }
        var fpsRow:android.view.View?=null
        fpsRow=ui.action(box,"选择帧率","当前：$fps 帧/秒") {
            val values=intArrayOf(15,24,30)
            managed(AlertDialog.Builder(activity).setTitle("帧率")
                .setSingleChoiceItems(arrayOf("15 帧/秒 · 低负载","24 帧/秒 · 折中","30 帧/秒 · 较顺滑"),values.indexOf(fps)) {dialog,which->
                    fps=values[which];drawMode();dialog.dismiss()
                    (fpsRow as? LinearLayout)?.let { row->(row.getChildAt(1) as? TextView)?.text="当前：$fps 帧/秒" }
                }.setNegativeButton("取消",null).create())
        }
        ui.action(box,"编辑自定义分辨率","仅“自定义”模式使用；普通使用建议自动或流畅优先") {customDisplay()}
        ui.intro(box,"自动优化画面在“功能适配”中开启。自动与流畅模式都保持屏幕比例；保存后拔线重连。")
        ui.page("画面设置",box,"保存画面设置") {
            prefs.edit().putString("displayMode",mode).putInt("fps",fps).apply();saved()
        }
    }
    private fun displaySummary(mode:String,fps:Int):String {
        val dm=activity.resources.displayMetrics
        val size=LegacyDisplayPolicy.resolve(mode,dm.widthPixels,dm.heightPixels,
            prefs.getInt("width",1024),prefs.getInt("height",600))
        val scale=if(mode==LegacyDisplayPolicy.MANUAL) prefs.getInt("scale",10) else 10
        val shown=ReceiverSettings.read(activity).copy(width=size.width,height=size.height,scale=scale).display()
        return "保存后的画面：${displayName(mode)}\n${shown.widthPixels} × ${shown.heightPixels} · $fps 帧/秒\n应用可用区域：${dm.widthPixels} × ${dm.heightPixels}"
    }
    private fun customDisplay() {
        val box=ui.box()
        ui.intro(box,"这里仅编辑手动画布。保存后仍需在画面模式中选择“自定义”；自动优化不会覆盖手动值。")
        fun number(label:String,value:Int):EditText {
            box.addView(ui.text(label,15f,ui.white))
            return EditText(activity).apply {
                inputType=InputType.TYPE_CLASS_NUMBER;setSingleLine(true);setText(value.toString());textSize=18f
                setTextColor(ui.white);contentDescription=label;minimumHeight=ui.dp(54);box.addView(this)
            }
        }
        val width=number("宽度（640～1920）",prefs.getInt("width",1024))
        val height=number("高度（360～1920）",prefs.getInt("height",600))
        val scale=number("画面比例（10 表示 100%，范围 3～10）",prefs.getInt("scale",10))
        val dialog=ui.page("自定义分辨率",box,"保存手动值") {}
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val w=width.text.toString().toIntOrNull();val h=height.text.toString().toIntOrNull();val s=scale.text.toString().toIntOrNull()
            if(w==null || w !in 640..1920) {width.error="请输入 640～1920";return@setOnClickListener}
            if(h==null || h !in 360..1920) {height.error="请输入 360～1920";return@setOnClickListener}
            if(s==null || s !in 3..10) {scale.error="请输入 3～10";return@setOnClickListener}
            try {prefs.edit().putInt("width",w).putInt("height",h).putInt("scale",s).apply();saved();dialog.dismiss()}
            catch(_:Exception) {toast("这次没有保存，请检查设置后重试")}
        }
    }
    private fun sound() {
        val settings=ReceiverSettings.read(activity);val box=ui.box()
        var volume=settings.volume;var buffer=settings.bufferMillis
        ui.intro(box,"先在车机调整媒体音量。这里的音量是 DiPlay 播放比例；导航、Siri 和通话保留各自播放规则。")
        var volumeDetail:TextView?=null
        val volumeRow=ui.action(box,"DiPlay 播放音量","当前：$volume%") {
            val values=intArrayOf(25,50,75,100)
            managed(AlertDialog.Builder(activity).setTitle("DiPlay 播放音量")
                .setSingleChoiceItems(values.map {"$it%"}.toTypedArray(),values.indexOf(volume)) {dialog,which->
                    volume=values[which];volumeDetail?.text="当前：$volume%";dialog.dismiss()
                }.setNegativeButton("取消",null).create())
        } as LinearLayout
        volumeDetail=volumeRow.getChildAt(1) as TextView
        var bufferDetail:TextView?=null
        val bufferRow=ui.action(box,"音乐启动缓冲","当前：$buffer 毫秒 · 卡音时可尝试 500 或 1000") {
            val values=intArrayOf(300,500,1000)
            managed(AlertDialog.Builder(activity).setTitle("音乐启动缓冲")
                .setSingleChoiceItems(arrayOf("300 毫秒 · 较低延迟","500 毫秒 · 折中","1000 毫秒 · 更大缓冲"),values.indexOf(buffer)) {dialog,which->
                    buffer=values[which];bufferDetail?.text="当前：$buffer 毫秒";dialog.dismiss()
                }.setNegativeButton("取消",null).create())
        } as LinearLayout
        bufferDetail=bufferRow.getChildAt(1) as TextView
        val focus=ui.check(box,"管理播放音频焦点","协调 DiPlay 与其他播放器；通常保持开启。",settings.audioFocus)
        val duck=ui.check(box,"导航时压低音乐","有导航提示时暂时降低音乐音量。",settings.navigationDuck)
        ui.note(box,"缓冲较大可能减少供给不及时造成的断音，也会增加开始播放等待；持续卡音请导出问题简报。")
        ui.page("声音设置",box,"保存声音设置") {
            prefs.edit().putInt("volume",volume).putInt("bufferMillis",buffer)
                .putBoolean("audioFocus",focus.isChecked).putBoolean("navigationDuck",duck.isChecked).apply()
            FeatureLearning.revokeAudioBuffer(activity.applicationContext);saved()
        }
    }
    private fun inputs() {
        val settings=ReceiverSettings.read(activity);val box=ui.box()
        val mic=ui.check(box,"使用车机麦克风","用于 Siri 和通话；关闭时不录音。请在系统设置允许麦克风权限。",settings.microphone)
        val gps=ui.check(box,"使用车机 GPS","允许向手机提供车机位置；还需要系统定位开启，手机是否采用由 CarPlay 决定。",settings.gps)
        ui.note(box,"只需开启并保存，再拔线重连。遇到问题才进入“功能适配”检查麦克风或定位，不必每次采样。")
        ui.page("麦克风与 GPS",box,"保存功能开关") {
            prefs.edit().putBoolean("microphonePcm",mic.isChecked).putBoolean("vehicleGps",gps.isChecked).apply();saved()
        }
    }
    private fun connection() {
        val box=ui.box()
        val voice=ui.check(box,"方向盘语音键","启用已有语音键服务，已连接时可呼叫 Siri。",prefs.getBoolean("steeringVoice",true))
        val media=ui.check(box,"转发方向盘切歌","先播放 CarPlay 音乐测试原厂按键。原厂无效时再开启；一次切两首立即关闭。",prefs.getBoolean("steeringMedia",false))
        ui.section(box,"连接习惯")
        val boot=ui.check(box,"开机后准备连接","车机启动后启用连接服务。",prefs.getBoolean("bootConnect",false))
        ui.intro(box,"返回车机桌面不会主动断开当前连接；重新插线时保留现有连接恢复流程。")
        ui.note(box,"方向盘识别与按键测试在“功能适配 → 方向盘切歌”。未识别到按键时，开启转发不能让它自动识别。")
        ui.page("方向盘与连接",box,"保存连接设置") {
            prefs.edit().putBoolean("steeringVoice",voice.isChecked).putBoolean("steeringMedia",media.isChecked)
                .putBoolean("bootConnect",boot.isChecked).apply();saved()
        }
    }
    private fun appearance() {
        val colors=listOf("gray" to "墨云灰","black" to "幻影黑","white" to "极地白","red" to "烈焰红",
            "green" to "苍松绿","brown" to "乌木棕","blue" to "行星蓝")
        managed(AlertDialog.Builder(activity).setTitle("主页车身颜色")
            .setSingleChoiceItems(colors.map {it.second}.toTypedArray(),colors.indexOfFirst {it.first==prefs.getString("heroColor","gray")}) {dialog,which->
                try {prefs.edit().putString("heroColor",colors[which].first).apply();onSaved();toast("外观已更新");dialog.dismiss()}
                catch(_:Exception) {toast("这次没有更新外观，请重试")}
            }.setNegativeButton("取消",null).create())
    }
    private fun displayName(mode:String)=when(mode) {
        LegacyDisplayPolicy.SMOOTH->"流畅优先";LegacyDisplayPolicy.CLEAR->"清晰优先"
        LegacyDisplayPolicy.MANUAL->"自定义";else->"自动适配"
    }
    private fun toast(text:String) {Toast.makeText(activity,text,Toast.LENGTH_LONG).show()}
}
