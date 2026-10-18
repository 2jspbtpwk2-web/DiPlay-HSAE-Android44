package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlaySession
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/**
 * 途胜L 纯有线主页（深色卡片风格）：
 * 未连接/连接中显示卡片仪表盘（车图 + 状态 + 第x/18步进度 + 工具）；
 * AirPlay 激活后自动进入 CarPlay 全屏，右下角“菜单”可回主页。
 * 连接 / USB 权限 / ROOT 全部在 LegacyWiredService 内处理；本界面只负责启动、显示、触摸转发与诊断。
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private var steeringLearningUi:SteeringLearningUi?=null
    private var featureLearningUi:FeatureLearningUi?=null
    private val learningExportPending=AtomicBoolean()
    private lateinit var video: SurfaceView
    private lateinit var videoBox: FrameLayout
    private lateinit var dashScroll: ScrollView
    private lateinit var dash: LinearLayout
    private lateinit var hero: ImageView
    private lateinit var subStatus: TextView
    private lateinit var connectBtn: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var stageLine: TextView
    private lateinit var timeLine: TextView
    private lateinit var stageBar: ProgressBar
    private lateinit var dot: View
    private lateinit var fab: TextView

    private var displayWidth = 1024
    private var displayHeight = 600
    @Volatile private var destroyed = false
    private val logBuf = StringBuilder("Hyundai Tucson L 有线版 $VERSION; SDK18\n")
    private val logStart = SystemClock.elapsedRealtime()
    private val busy = AtomicBoolean()
    private var diagnosticDialog: AlertDialog? = null
    private var diagnosticHidden = false
    private var pendingDiagnosticResult = false
    private var pendingDiagnosticFile: File? = null
    private val touchQueue = TouchDispatchQueue<AirPlaySession>()
    private var touchThread: Thread? = null

    private var uiState = STATE_IDLE
    private var connectStart = 0L
    private var progressStage = -2
    private var progressSecond = -1L
    private var resumed = false
    private val uiTiming = UiTimingStats()
    private val artworkWorker = Executors.newSingleThreadExecutor { Thread(it, "dashboard-artwork").apply { isDaemon = true } }
    private val artworkRequest = AtomicInteger()
    private var displayedColor: String? = null
    private val managedDialogs = HashSet<AlertDialog>()

    private val activeSession: AirPlaySession? get() = LegacyWiredService.instance?.activeSession

    companion object {
        const val VERSION = "0.60"
        @Volatile var viewportDiagnostic: String = "界面尚未测量"
            private set
        @Volatile var interactionDiagnostic: String = "界面交互尚未采样"
            private set
        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
        private val C_BG = 0xFF0D0D10.toInt()
        private val C_CARD = 0xFF18181D.toInt()
        private val C_CARD2 = 0xFF23232A.toInt()
        private val C_STROKE = 0xFF2E2E37.toInt()
        private val C_RED = 0xFFE23B3B.toInt()
        private val C_WHITE = 0xFFFFFFFF.toInt()
        private val C_GRAY = 0xFF9A9AA4.toInt()
        private val C_GREEN = 0xFF34C759.toInt()
        private val C_FOOT = 0xFF6A6A73.toInt()
        private val STAGES = ConnectionStages.items
        // 23 款途胜 L 国产车漆（7 色）：assets 图片 key -> 中文名
        private val COLORS = listOf(
            "gray" to "墨云灰", "black" to "幻影黑", "white" to "极地白",
            "red" to "烈焰红", "green" to "苍松绿", "brown" to "乌木棕", "blue" to "行星蓝")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun cardBg(fill: Int, stroke: Int, radius: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.RECTANGLE); g.setCornerRadius(radius); g.setColor(fill)
        if (stroke != 0) g.setStroke(dp(1f), stroke)
        return g
    }
    private fun pillBg(fill: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.RECTANGLE); g.setCornerRadius(dp(90).toFloat()); g.setColor(fill)
        if (stroke != 0) g.setStroke(dp(1f), stroke)
        return g
    }
    private fun makePill(label: String, fill: Int, txt: Int, stroke: Int, action: () -> Unit): TextView {
        val v = TextView(this)
        v.background = pillBg(fill, stroke)
        v.text = label; v.setTextColor(txt); v.textSize = 14f
        v.gravity = Gravity.CENTER
        v.setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setOnClickListener { safe { action() } }
        return v
    }
    private fun makeToolBtn(label: String, action: () -> Unit): TextView {
        val v = TextView(this)
        v.background = cardBg(C_CARD2, C_STROKE, dp(12).toFloat())
        v.text = label; v.setTextColor(C_WHITE); v.textSize = 13.5f
        v.gravity = Gravity.CENTER; v.setPadding(dp(8f), 0, dp(8f), 0)
        v.setOnClickListener { safe { action() } }
        return v
    }
    private fun roundCorners(src: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius, radius, paint)
        if (out != src) src.recycle()
        return out
    }

    private fun applyHeroColor() {
        val key = getSharedPreferences("receiver-ui", MODE_PRIVATE).getString("heroColor", "gray") ?: "gray"
        if (destroyed) return
        val request = artworkRequest.incrementAndGet()
        if (key == displayedColor) return
        val radius = dp(12).toFloat()
        artworkWorker.execute {
            if (destroyed || request != artworkRequest.get()) return@execute
            val bitmap = runCatching {
                assets.open("images/tucson_$key.jpg").use { BitmapFactory.decodeStream(it) }?.let { roundCorners(it, radius) }
            }.getOrNull() ?: return@execute
            handler.post {
                if (destroyed || request != artworkRequest.get()) bitmap.recycle()
                else { hero.setImageBitmap(bitmap); displayedColor = key }
            }
        }
    }

    private fun log(text: String) {
        synchronized(logBuf) {
            logBuf.append('+').append(SystemClock.elapsedRealtime() - logStart).append("ms ").append(text).append('\n')
            if (logBuf.length > 9000) { val keep = logBuf.substring(logBuf.length - 6500); logBuf.setLength(0); logBuf.append(keep) }
        }
        if (!destroyed) runOnUiThread { if (!destroyed) try {
            subStatus.text = text
        } catch (_: Throwable) {} }
    }
    private fun refreshDisplayLayout() {
        // An active phone session keeps the dimensions it read in /info until reconnection.
        val d = LegacyWiredService.instance?.advertisedDisplay ?: ReceiverSettings.read(this).display()
        if (displayWidth != d.widthPixels || displayHeight != d.heightPixels) {
            displayWidth = d.widthPixels; displayHeight = d.heightPixels
            fitVideoSurface(videoBox.width,videoBox.height)
            videoBox.requestLayout()
        }
    }
    private fun fitVideoSurface(aw:Int,ah:Int) {
        if(aw<=0 || ah<=0) return
        val fitted=LegacyDisplayPolicy.fit(aw,ah,displayWidth,displayHeight)
        viewportDiagnostic="应用实测容器=${aw}x${ah}；视频Surface=${fitted.width}x${fitted.height}；会话画布=${displayWidth}x${displayHeight}；等比居中，触摸按Surface局部坐标"
        if(video.layoutParams.width!=fitted.width || video.layoutParams.height!=fitted.height)
            video.layoutParams=FrameLayout.LayoutParams(fitted.width,fitted.height,Gravity.CENTER)
    }
    private fun safe(block: () -> Unit) { try { block() } catch (t: Throwable) { runCatching { log("UI操作异常(已拦截):${t.javaClass.simpleName}:${t.message}") } } }
    private fun showManaged(dialog: AlertDialog): AlertDialog {
        if (destroyed || isFinishing) return dialog
        managedDialogs.add(dialog)
        dialog.setOnDismissListener { managedDialogs.remove(dialog) }
        try { dialog.show() } catch (error: Exception) { managedDialogs.remove(dialog); throw error }
        return dialog
    }
    private fun AlertDialog.Builder.showManaged(): AlertDialog = showManaged(create())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runCatching { CrashGuard.install(this) }
        runCatching { PerformanceLearning.initialize(applicationContext) }
        com.shilapi.xcertplay.transport.TlsDuplexChannel.preloadInBackground()
        lockOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val d0 = LegacyWiredService.instance?.advertisedDisplay ?: ReceiverSettings.read(this).display()
        displayWidth = d0.widthPixels; displayHeight = d0.heightPixels

        val root = FrameLayout(this)

        // video layer (always present)
        videoBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        video.holder.addCallback(this)
        videoBox.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoBox.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val aw = r - l; val ah = b - t
            fitVideoSurface(aw,ah)
        }
        video.setOnTouchListener { view, e ->
            val session = activeSession
            if (session != null && view.width > 0 && view.height > 0) {
                // Optional metadata must not acquire the media/learning observer locks per touch.
                runCatching { PerformanceLearning.touch() }
                val action = e.actionMasked
                val contacts = (0 until minOf(e.pointerCount, 2)).map { i ->
                    AirPlayContact(e.getPointerId(i),
                        (e.getX(i) / view.width).toDouble().coerceIn(0.0, 1.0),
                        (e.getY(i) / view.height).toDouble().coerceIn(0.0, 1.0),
                        action != MotionEvent.ACTION_CANCEL &&
                            !(i == e.actionIndex && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)))
                }
                touchQueue.submit(session, contacts, action == MotionEvent.ACTION_MOVE, action == MotionEvent.ACTION_DOWN)
            }
            true
        }
        root.addView(videoBox, FrameLayout.LayoutParams(-1, -1))

        buildDashboard()
        root.addView(dashScroll, FrameLayout.LayoutParams(-1, -1))

        fab = makePill("菜单", 0xEE222229.toInt(), C_WHITE, C_STROKE) { showDashboard() }
        fab.visibility = View.GONE
        root.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(0, 0, dp(16f), dp(16f)) })

        setContentView(root)

        touchThread = Thread({
            while (true) {
                try {
                    val job = touchQueue.poll(100)
                    if (job == null) { if (destroyed) return@Thread else continue }
                    if (activeSession !== job.session) { touchQueue.recordStale(); continue }
                    val started = System.nanoTime()
                    var sent = false
                    try { sent = job.session.sendTouch(job.contacts) }
                    finally { touchQueue.recordDelivery(job, started, System.nanoTime() - started, sent) }
                } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) {}
            }
        }, "wired-touch").apply { isDaemon = true; start() }

        if (getSharedPreferences("receiver-ui", MODE_PRIVATE).getBoolean("steeringVoice", true))
            runCatching { startService(android.content.Intent(this, SteeringVoiceService::class.java)) }
        log("准备就绪，请用数据线连接 iPhone；首次使用请允许 USB 和超级用户授权。")
    }

    private fun buildDashboard() {
        dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG)
            setPadding(dp(14f), dp(14f), dp(14f), dp(22f))
        }
        dashScroll = ScrollView(this).apply { addView(dash, FrameLayout.LayoutParams(-1, -2)) }

        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(Gravity.CENTER_VERTICAL) }
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(38f), dp(22f)))
        runCatching {
            assets.open("images/logo_emblem.png").use { BitmapFactory.decodeStream(it) }?.let { logo.setImageBitmap(it) }
        }
        val title = TextView(this).apply { text = "Hyundai Tucson L"; setTextColor(C_WHITE); textSize = 18f; setTypeface(typeface, Typeface.BOLD) }
        top.addView(title, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val usbTag = makePill("USB 有线", C_RED, C_WHITE, 0) {}
        usbTag.isClickable = false
        top.addView(usbTag)
        dash.addView(top, LinearLayout.LayoutParams(-1, -2))

        // hero car card
        val heroCard = FrameLayout(this).apply { background = cardBg(C_CARD, C_STROKE, dp(16).toFloat()) }
        hero = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroCard.addView(hero, FrameLayout.LayoutParams(-1, dp(208f)).apply { setMargins(dp(6f), dp(6f), dp(6f), dp(6f)) })
        hero.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width; val h = w * 9 / 16
            if (w > 0 && v.height != h)
                v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply { height = h }
        }
        dash.addView(heroCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        applyHeroColor()

        // main status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setGravity(Gravity.CENTER_HORIZONTAL)
            background = cardBg(C_CARD, C_STROKE, dp(16).toFloat())
            setPadding(dp(18f), dp(20f), dp(18f), dp(20f))
        }
        val big = TextView(this).apply { text = "有线 CarPlay"; setTextColor(C_WHITE); textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }
        statusCard.addView(big)
        subStatus = TextView(this).apply { text = "准备就绪：插好数据线，点下方连接"; setTextColor(C_GRAY); textSize = 14f; gravity = Gravity.CENTER }
        statusCard.addView(subStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        connectBtn = makePill("连接 CarPlay", C_RED, C_WHITE, 0) { onConnectButton() }.apply { textSize = 16f }
        statusCard.addView(connectBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        dash.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })

        // progress card
        progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg(C_CARD, C_STROKE, dp(16).toFloat())
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f)); visibility = View.GONE
        }
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(Gravity.CENTER_VERTICAL) }
        val spin = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        prow.addView(spin, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        val pcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stageLine = TextView(this).apply { text = "连接中 · 第 1/18 步"; setTextColor(C_RED); textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        pcol.addView(stageLine)
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(Gravity.CENTER_VERTICAL) }
        val dotG = GradientDrawable()
        dotG.setShape(GradientDrawable.OVAL); dotG.setColor(C_RED)
        dot = View(this).apply { background = dotG }
        timeRow.addView(dot, LinearLayout.LayoutParams(dp(8f), dp(8f)))
        timeLine = TextView(this).apply { text = "链路建立中 · 已用 0 秒"; setTextColor(C_GRAY); textSize = 12.5f }
        timeRow.addView(timeLine, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(7f) })
        pcol.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        prow.addView(pcol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
        val stepsBtn = makePill("全部步骤", C_CARD2, C_WHITE, C_STROKE) { showStagesDialog() }.apply { textSize = 12.5f }
        prow.addView(stepsBtn)
        val cancelBtn = makePill("取消", C_CARD2, C_WHITE, C_STROKE) { stopUsb() }.apply { textSize = 12.5f }
        prow.addView(cancelBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) })
        progressCard.addView(prow)
        stageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 18; progress = 1 }
        progressCard.addView(stageBar, LinearLayout.LayoutParams(-1, dp(10f)).apply { topMargin = dp(12f) })
        dash.addView(progressCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })

        val toolsCard = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;background=cardBg(C_CARD,C_STROKE,dp(16).toFloat())
            setPadding(dp(14f),dp(16f),dp(14f),dp(14f))
        }
        toolsCard.addView(TextView(this).apply {
            text="设置与帮助";setTextColor(C_WHITE);textSize=18f;setTypeface(typeface,Typeface.BOLD)
        })
        toolsCard.addView(TextView(this).apply {
            text="按用途进入；遇到问题先导出简报。";setTextColor(C_GRAY);textSize=13f
            setPadding(0,dp(5),0,dp(7))
        })
        fun entry(title:String,detail:String,action:()->Unit):View = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setGravity(Gravity.CENTER_VERTICAL)
            background=cardBg(C_CARD2,C_STROKE,dp(12).toFloat());minimumHeight=dp(92)
            setPadding(dp(16),dp(13),dp(12),dp(13));isClickable=true;isFocusable=true
            contentDescription="$title。$detail"
            addView(TextView(this@ReceiverActivity).apply {
                text=title;setTextColor(C_WHITE);textSize=18f;setTypeface(typeface,Typeface.BOLD)
            })
            addView(TextView(this@ReceiverActivity).apply {
                text=detail;setTextColor(C_GRAY);textSize=13f;setPadding(0,dp(5),0,0)
            })
            setOnClickListener {safe(action)}
        }
        fun entries(left:View,right:View) {
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            row.addView(left,LinearLayout.LayoutParams(0,-1,1f))
            row.addView(right,LinearLayout.LayoutParams(0,-1,1f).apply {leftMargin=dp(10)})
            toolsCard.addView(row,LinearLayout.LayoutParams(-1,dp(100)).apply {topMargin=dp(9)})
        }
        entries(entry("设置","画面、声音、麦克风与GPS"){showSettingsDialog()},
            entry("功能适配","自动优化画面、方向盘按键"){showLearningDashboard()})
        entries(entry("问题排查","问题简报、声音检查、完整诊断"){problemTools().showDiagnostics()},
            entry("使用说明","连接步骤与常见问题"){problemTools().showHelp()})
        val shortcuts=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        listOf("返回 CarPlay" to {returnToCarPlay()},"Siri" to {requestSiri()},"返回车机" to {goHome()})
            .forEachIndexed {index,item -> shortcuts.addView(makeToolBtn(item.first,item.second),
                LinearLayout.LayoutParams(0,dp(48),1f).apply {if(index>0) leftMargin=dp(8)}) }
        toolsCard.addView(shortcuts,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        dash.addView(toolsCard,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})

        val foot = TextView(this).apply {
            text = "DiPlay v$VERSION · 有线 CarPlay"
            setTextColor(C_FOOT); textSize = 11f; gravity = Gravity.CENTER
        }
        dash.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
    }

    private val handler = Handler()
    private val autoConnect = Runnable { if (!destroyed && !isFinishing) safe { startUsb() } }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed || !resumed) return
            uiTiming.arrived(SystemClock.elapsedRealtime())
            try { refreshState() } catch (_: Throwable) {}
            runCatching { updateLearningVisibility() }
            interactionDiagnostic = uiTiming.summary() + "\n" + touchQueue.diagnosticSummary()
            if (!destroyed && resumed) { uiTiming.schedule(SystemClock.elapsedRealtime(), 800); handler.postDelayed(this, 800) }
        }
    }
    private fun refreshState() {
        val svc = LegacyWiredService.instance
        val session = svc?.activeSession
        when {
            session != null -> { if (uiState != STATE_CONNECTED) renderConnected() }
            uiState == STATE_CONNECTING -> {
                if (svc == null) renderIdle("连接已停止：可查看运行日志或重新连接")
                else renderProgress(svc.connectionStage)
            }
            uiState == STATE_CONNECTED -> renderIdle("CarPlay 已断开：可重新连接")
        }
    }
    private fun updateLearningVisibility() {
        val svc=LegacyWiredService.instance
        PerformanceLearning.activity(resumed && activeSession!=null && dashScroll.visibility==View.GONE && video.holder.surface.isValid,
            DiagnosticRunState.isRunning || managedDialogs.isNotEmpty() || SteeringLearning.isLearning || FeatureLearning.isCalibrating(),
            FunctionRunDiagnostics.isSiriPending() || svc?.learningVoiceActive()==true)
    }
    private fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun showSteeringLearning() {
        if(DiagnosticRunState.isRunning || FeatureLearning.isCalibrating()) {toast("请先结束诊断或功能校准");return}
        if(steeringLearningUi==null) steeringLearningUi=SteeringLearningUi(this,{showManaged(it)},
            {LegacyWiredService.instance?.activeSession!=null})
        steeringLearningUi?.show()
    }
    private fun showFeatureLearning() {
        if(DiagnosticRunState.isRunning || SteeringLearning.isLearning) {toast("请先结束诊断或按键学习");return}
        if(featureLearningUi==null) featureLearningUi=FeatureLearningUi(this,{showManaged(it)},
            {LegacyWiredService.instance?.isConnectionInProgress()==true || FunctionRunDiagnostics.isSiriPending()})
        featureLearningUi?.show()
    }
    private fun showLearningDashboard() = LearningDashboardUi(this,{showManaged(it)},
        {showSteeringLearning()},{showFeatureLearning()},{exportLearningReport()},
        settings={showSettingsDialog()},gps={showGpsStatusDialog()},backToCarPlay={returnToCarPlay()}).show()
    private fun problemTools() = ReceiverToolsUi(this,{showManaged(it)},ReceiverToolsUi.Actions(
        quickReport={exportLearningReport()},fullDiagnostic={runSuperDiag(true)},collectOnly={runSuperDiag(false)},
        audioCheck={showFeatureLearning()},gpsStatus={showGpsStatusDialog()},runtimeLog={showLogDialog()},
        siri={requestSiri()},backToCarPlay={returnToCarPlay()},backToVehicle={goHome()}))
    private fun returnToCarPlay() {
        if(activeSession==null) {toast("请先插好iPhone并连接CarPlay");return}
        runCatching {steeringLearningUi?.close()};steeringLearningUi=null
        runCatching {featureLearningUi?.close()};featureLearningUi=null
        managedDialogs.toList().forEach {dialog ->
            if(dialog===diagnosticDialog && busy.get()) {diagnosticHidden=true;dialog.hide()}
            else runCatching {dialog.dismiss()}
        }
        enterFullscreen();updateLearningVisibility()
    }
    private fun exportLearningReport() {
        if(!learningExportPending.compareAndSet(false,true)) return
        val owner=java.lang.ref.WeakReference(this)
        val app=applicationContext
        Thread({
            val outcome=runCatching {LearningReports.export(app)}
            Handler(android.os.Looper.getMainLooper()).post {
                owner.get()?.let { activity ->
                    activity.learningExportPending.set(false)
                    if(activity.resumed && !activity.destroyed && !activity.isFinishing) {
                        outcome.fold({ file-> activity.showManaged(AlertDialog.Builder(activity).setTitle("问题简报已导出")
                            .setMessage(file.absolutePath+"\n把这个文件和问题现象发给开发者；需要时再做完整诊断。")
                            .setPositiveButton("知道了",null).create()) },
                            { error->activity.toast("导出失败：${error.javaClass.simpleName}") })
                    }
                }
            }
        },"learning-report-export").apply {isDaemon=true;start()}
    }
    private fun setDot(c: Int) { (dot.background as GradientDrawable).setColor(c) }
    private fun renderProgress(idx: Int) {
        val x = (idx + 1).coerceAtLeast(1)
        if (progressStage != idx) {
            stageLine.text = "连接中 · 第 $x/18 步 · ${STAGES[(x - 1).coerceAtMost(STAGES.size - 1)].second}"
            stageBar.progress = x; progressStage = idx
        }
        val secs = (SystemClock.elapsedRealtime() - connectStart) / 1000
        if (progressSecond != secs) { timeLine.text = "链路建立中 · 已用 $secs 秒"; progressSecond = secs }
    }
    private fun renderConnected() {
        uiState = STATE_CONNECTED
        progressCard.visibility = View.GONE
        connectBtn.text = "进入 CarPlay"; connectBtn.background = pillBg(C_RED, 0); connectBtn.setTextColor(C_WHITE)
        subStatus.text = "已连接 · CarPlay 已激活"
        setDot(C_GREEN)
        refreshDisplayLayout()
        log("已连接：进入 CarPlay 全屏。")
        enterFullscreen()
    }
    private fun renderIdle(msg: String) {
        uiState = STATE_IDLE
        progressCard.visibility = View.GONE
        connectBtn.text = "连接 CarPlay"; connectBtn.background = pillBg(C_RED, 0); connectBtn.setTextColor(C_WHITE)
        subStatus.text = msg
        showDashboard()
    }

    override fun onBackPressed() {
        // 全屏 CarPlay 时返回键 = 回仪表盘（不退出 App）；仪表盘时返回 = 回车机桌面
        if (::fab.isInitialized && fab.visibility == View.VISIBLE) showDashboard() else goHome()
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        VoiceKeyJournal.recordFramework("ACTIVITY", event)
        return super.dispatchKeyEvent(event)
    }
    private fun enterFullscreen() {
        dashScroll.visibility=View.GONE;fab.visibility=View.VISIBLE
        runCatching {updateLearningVisibility()}
    }
    private fun releaseTouch() { activeSession?.let { touchQueue.cancel(it) } }
    private fun showDashboard() {
        releaseTouch();dashScroll.visibility=View.VISIBLE;fab.visibility=View.GONE
        runCatching {updateLearningVisibility()}
    }
    private fun goHome() { startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)) }
    private fun onConnectButton() { when (uiState) { STATE_CONNECTING -> stopUsb(); STATE_CONNECTED -> enterFullscreen(); else -> startUsb() } }

    private fun lockOrientation() {
        try {
            val dm = android.util.DisplayMetrics()
            runCatching { windowManager.defaultDisplay.getRealMetrics(dm) }
            if (dm.widthPixels == 0) dm.setTo(resources.displayMetrics)
            requestedOrientation = if (dm.heightPixels >= dm.widthPixels)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } catch (_: Throwable) {}
    }

    private fun startUsb() {
        if (destroyed || isFinishing) return
        safe {
            connectStart = SystemClock.elapsedRealtime()
            uiState = STATE_CONNECTING
            progressStage = -2; progressSecond = -1L
            runCatching { LegacyWiredService.setVideoSurface(video.holder.surface.takeIf { it.isValid }) }
            startService(android.content.Intent(this, LegacyWiredService::class.java))
            progressCard.visibility = View.VISIBLE
            renderProgress(-1); setDot(C_RED)
            connectBtn.text = "取消连接"; connectBtn.background = pillBg(C_CARD2, C_STROKE); connectBtn.setTextColor(C_WHITE)
            showDashboard()
            log("USB有线服务启动：USB权限弹窗勾“始终”再允许，超级用户点允许并勾记住。")
        }
    }
    private fun stopUsb() {
        safe {
            releaseTouch()
            stopService(android.content.Intent(this, LegacyWiredService::class.java))
            renderIdle("已请求停止：可查看运行日志，或重新连接")
            log("已请求停止有线服务。")
        }
    }
    private fun requestSiri() {
        val session = activeSession
        if (session == null) { log("Siri：没有活动会话，请先连接。"); return }
        if(!FunctionRunDiagnostics.beginSiri()) { log("Siri请求仍在发送，已合并重复点击。"); return }
        try {
        Thread({
            try {
                if(destroyed || activeSession !== session) { FunctionRunDiagnostics.finishSiri("discarded-session-changed"); return@Thread }
                val started=SystemClock.elapsedRealtime()
                val sent=session.invokeSiri()
                FunctionRunDiagnostics.finishSiri("sent=$sent elapsedMs=${SystemClock.elapsedRealtime()-started}")
                log(if(sent) "Siri按下/抬起请求写入成功；手机是否启动与识别需结合麦克风统计。" else "Siri请求未完整写入；事件通道未就绪或会话已关闭。")
            } catch(error:Exception) {
                FunctionRunDiagnostics.finishSiri("error=${error.javaClass.simpleName}")
                log("Siri调用异常:${error.javaClass.simpleName}")
            }
        }, "siri").apply { isDaemon = true; start() }
        } catch(error:Throwable) { FunctionRunDiagnostics.finishSiri("start-error=${error.javaClass.simpleName}"); throw error }
    }
    private fun runSuperDiag(autoConnect: Boolean) {
        if(destroyed || isFinishing) return
        diagnosticDialog?.let { existing -> if(busy.get()) {
            // A hidden Dialog keeps its original window order. Remove the newly opened tools
            // parent before showing that existing window, or it remains covered underneath.
            managedDialogs.toList().filter {it!==existing}.forEach {runCatching {it.dismiss()}}
            diagnosticHidden=false;showManaged(existing);return
        } }
        if(DiagnosticRunState.isRunning) { Toast.makeText(this,"已有采集在后台运行，完成后仍保存到DiPlay文件夹",Toast.LENGTH_LONG).show(); return }
        if (!busy.compareAndSet(false, true)) { Toast.makeText(this, "诊断/采集正在进行中", Toast.LENGTH_SHORT).show(); return }
        if(!DiagnosticRunState.begin()) { busy.set(false); return }
        var workerStarted=false
        try {
        val progView = TextView(this).apply { textSize = 12f; setPadding(24, 16, 24, 12) }
        val scroll = ScrollView(this).apply { addView(progView) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (autoConnect) "★终极诊断（自动连接，约3–5分钟）" else "仅采集（不自动连接，约2–3分钟）")
            .setView(scroll).setCancelable(false)
            .setNegativeButton("收起后操作",null).setNeutralButton("Siri采集",null).create()
        diagnosticDialog=dialog; diagnosticHidden=false
        showManaged(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            diagnosticHidden=true; dialog.hide()
            managedDialogs.toList().filter {it!==dialog}.forEach {runCatching {it.dismiss()}}
            FunctionRunDiagnostics.event("diagnostic","hidden")
            if(activeSession!=null) enterFullscreen()
            Toast.makeText(this,"后台继续采集；到“问题排查 → 完整诊断与按键采集”返回进度",Toast.LENGTH_LONG).show()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { requestSiri() }
        dialog.setOnKeyListener { _, _, event ->
            VoiceKeyJournal.recordFramework("DIAG_DIALOG", event)
            false
        }
        val pendingProgress = StringBuilder()
        var progressPosted = false
        val flushProgress = Runnable {
            val text = synchronized(pendingProgress) {
                progressPosted = false
                pendingProgress.toString().also { pendingProgress.setLength(0) }
            }
            if (!destroyed) runCatching {
                val bounded = (progView.text.toString() + text).takeLast(24000)
                progView.text = bounded
                scroll.post { if (!destroyed && dialog.isShowing) scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        fun prog(m: String) {
            if (destroyed) return
            // Preserve every report line on the diagnostic worker; refresh its preview at most 10Hz.
            synchronized(logBuf) {
                logBuf.append('+').append(SystemClock.elapsedRealtime() - logStart).append("ms 诊断：").append(m).append('\n')
                if (logBuf.length > 9000) logBuf.delete(0, logBuf.length - 6500)
            }
            synchronized(pendingProgress) {
                pendingProgress.append(m).append('\n')
                if (pendingProgress.length > 24000) pendingProgress.delete(0, pendingProgress.length - 24000)
                if (!progressPosted) { progressPosted = true; handler.postDelayed(flushProgress, 100) }
            }
            if(m.startsWith("功能观察")) runOnUiThread {
                if(!destroyed && diagnosticHidden && resumed) Toast.makeText(this,m,Toast.LENGTH_LONG).show()
            }
        }
        Thread({
            var out: File? = null
            try {
                prog("开始采集…")
                out = SuperDiagnostics(this@ReceiverActivity) { prog(it) }.run(autoConnect)
                prog("报告已保存：${out.absolutePath}")
            } catch (t: Throwable) { prog("诊断异常:${t.javaClass.simpleName}:${t.message}") }
            finally {
                val f = out
                DiagnosticRunState.finish()
                runOnUiThread {
                    handler.removeCallbacks(flushProgress)
                    runCatching { dialog.dismiss() }
                    if(diagnosticDialog === dialog) { diagnosticDialog=null; diagnosticHidden=false }
                    busy.set(false)
                    if (destroyed || isFinishing) return@runOnUiThread
                    if(!resumed) { pendingDiagnosticResult=true; pendingDiagnosticFile=f; return@runOnUiThread }
                    showDiagnosticResult(f)
                }
            }
        }, "super-diag").apply { isDaemon = true; start() }
        workerStarted=true
        } finally {
            if(!workerStarted) {
                DiagnosticRunState.finish(); busy.set(false)
                diagnosticDialog?.let { runCatching { it.dismiss() }; managedDialogs.remove(it) }
                diagnosticDialog=null; diagnosticHidden=false
            }
        }
    }

    private fun showDiagnosticResult(file:File?) {
        if(destroyed || isFinishing || !resumed) return
        if(file!=null) AlertDialog.Builder(this).setTitle("诊断完成")
            .setMessage("报告（就这一个文件）：\n${file.absolutePath}\n\n请用甲壳虫ADB助手 → 文件管理 → DiPlay 文件夹，把该 super-diag 文件发到手机再转发给我。")
            .setPositiveButton("知道了",null).showManaged()
        else AlertDialog.Builder(this).setTitle("诊断未完成").setMessage("请把本窗口日志截图发我。")
            .setPositiveButton("知道了",null).showManaged()
    }

    private fun showGpsStatusDialog() {
        if(destroyed || isFinishing) return
        val prefs=getSharedPreferences("receiver-ui",MODE_PRIVATE)
        val enabled=ReceiverSettings.read(this).gps
        val svc=LegacyWiredService.instance
        val frozen=svc?.gpsConnectionConfiguration()
        val active=svc?.activeSession!=null
        val systemGps=runCatching { (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }.getOrNull()
        val usage=com.shilapi.xcertplay.transport.Iap2LocationDiagnostics.latestUsage()
        val text=buildString {
            append("应用GPS开关：${if(enabled) "已开启" else "已关闭"}\n")
            append("车机系统GPS：${when(systemGps) { true->"已开启"; false->"未开启，请到车机系统设置开启"; else->"无法读取" }}\n")
            append("本轮GPS设置：${when(frozen?.first) { true->"已开启"; false->"已关闭"; else->"尚无连接配置" }}\n")
            if(frozen?.first!=null && enabled!=frozen.first) append("设置已变化，请拔线后重新连接生效。\n")
            if(active && frozen?.second==true && usage!=null && usage.terminalCleanupCount==0L) {
                append("手机曾订阅：${usage.requestCount}次；累计发送：${usage.successfulSends}条；停止订阅：${usage.stopRequestCount}次\n")
                if(usage.requestCount==0L) append("等待手机请求；手机可以选择自己的定位。\n")
                if(usage.successfulSends>0L) append("最近发送：${usage.lastSentAgeMillis.coerceAtLeast(0)/1000}秒前；发送成功不证明手机采用。\n")
                if(usage.startFailureCount+usage.providerErrorCount+usage.sendErrorCount>0L) append("记录到定位错误，请到问题排查运行完整诊断查看原因。\n")
            } else if(active && frozen?.first==false) append("当前会话没有提供车机GPS，请开启后重新连接。\n")
            else if(active && frozen?.second==false) append("本轮没有提供GPS能力，请到问题排查运行完整诊断查看定位初始化或权限问题。\n")
            else if(active && frozen?.second==null) append("本轮GPS能力尚未完成通告，请稍后刷新。\n")
            else append("连接后可在这里查看手机订阅和发送情况。\n")
            append("\n仅使用新鲜GPS定位；未开启的系统定位不会被自动打开。")
        }
        AlertDialog.Builder(this).setTitle("车机GPS状态").setMessage(text)
            .setPositiveButton(if(enabled) "设置" else "开启车机GPS") { _, _ ->
                if(enabled) showSettingsDialog() else {
                    prefs.edit().putBoolean("vehicleGps",true).apply()
                    log("已开启车机GPS；请拔线后重新连接生效。")
                    Toast.makeText(this,"已开启车机GPS，请拔线后重新连接",Toast.LENGTH_LONG).show()
                    showGpsStatusDialog()
                }
            }.setNeutralButton("刷新") { _, _ -> showGpsStatusDialog() }
            .setNegativeButton("关闭",null).showManaged()
    }

    private fun showStagesDialog() {
        val svc = LegacyWiredService.instance
        val far = svc?.connectionStage ?: -1
        val cur = (far + 1).coerceAtLeast(0)
        val sb = StringBuilder()
        for (i in STAGES.indices)
            sb.append(if (i <= far) "✓ " else "· ").append(i + 1).append(". ").append(STAGES[i].second).append('\n')
        AlertDialog.Builder(this).setTitle("连接步骤（$cur/18）")
            .setMessage(sb.trim()).setPositiveButton("关闭", null).showManaged()
    }
    private fun showLogDialog() {
        val tv = TextView(this).apply {
            textSize = 10.5f; setTextColor(C_GRAY); setTypeface(Typeface.MONOSPACE); setTextIsSelectable(true)
            text = synchronized(logBuf) { logBuf.toString() }
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("运行日志").setView(sv).setPositiveButton("关闭", null).showManaged()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
    private fun showSettingsDialog() = ReceiverSettingsUi(this,{showManaged(it)},{
        val enabled=getSharedPreferences("receiver-ui",MODE_PRIVATE).getBoolean("steeringVoice",true)
        val intent=android.content.Intent(this,SteeringVoiceService::class.java)
        runCatching {if(enabled) startService(intent) else stopService(intent)}
        applyHeroColor();refreshDisplayLayout()
        log("设置已保存；当前连接保持原配置，拔线后重新连接生效。")
    },{LegacyWiredService.instance?.isConnectionInProgress()==true}).show()

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        when (intent?.action) {
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))
                handler.removeCallbacks(autoConnect); handler.postDelayed(autoConnect, 600)
            }
            SteeringVoiceService.SIRI_ACTION -> voiceKey(true)
            SteeringVoiceService.VOICE_ACTION -> voiceKey(false)
        }
    }
    private fun voiceKey(wantSiri: Boolean) {
        val session = activeSession
        if (session != null) { if (wantSiri) requestSiri() } else startUsb()
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        FunctionRunDiagnostics.event("ui","resumed")
        if(pendingDiagnosticResult) {
            val file=pendingDiagnosticFile; pendingDiagnosticResult=false; pendingDiagnosticFile=null
            handler.post { if(resumed && !destroyed && !isFinishing) showDiagnosticResult(file) else { pendingDiagnosticResult=true; pendingDiagnosticFile=file } }
        }
        uiTiming.resume(SystemClock.elapsedRealtime())
        handler.removeCallbacks(tick); handler.post(tick)
        refreshDisplayLayout()
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))
            handler.removeCallbacks(autoConnect); handler.postDelayed(autoConnect, 700)
        }
    }

    override fun onPause() {
        FunctionRunDiagnostics.event("ui","paused")
        resumed = false
        runCatching {PerformanceLearning.activity(false,true,false)}
        runCatching {steeringLearningUi?.onPause()}
        runCatching {featureLearningUi?.close()};featureLearningUi=null
        managedDialogs.toList().filter {it!==diagnosticDialog}.forEach {runCatching {it.dismiss()}}
        uiTiming.pause()
        handler.removeCallbacks(tick)
        releaseTouch()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseTouch()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { FunctionRunDiagnostics.event("surface","created valid=${holder.surface.isValid}"); runCatching { LegacyWiredService.setVideoSurface(holder.surface.takeIf { it.isValid }) } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { FunctionRunDiagnostics.event("surface","changed width=$width height=$height format=$format"); runCatching { LegacyWiredService.setVideoSurface(holder.surface.takeIf { it.isValid }) } }
    override fun surfaceDestroyed(holder: SurfaceHolder) { FunctionRunDiagnostics.event("surface","destroyed"); runCatching { LegacyWiredService.setVideoSurface(null) } }

    override fun onDestroy() {
        FunctionRunDiagnostics.event("ui","destroyed")
        releaseTouch()
        destroyed = true
        runCatching {steeringLearningUi?.close()};steeringLearningUi=null
        runCatching {featureLearningUi?.close()};featureLearningUi=null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoConnect)
        artworkRequest.incrementAndGet(); artworkWorker.shutdownNow()
        managedDialogs.toList().forEach { runCatching { it.dismiss() } }; managedDialogs.clear()
        diagnosticDialog=null; diagnosticHidden=false
        runCatching { LegacyWiredService.setVideoSurface(null) }
        // The worker drains the final all-up before exiting; never wait for a socket on the UI thread.
        super.onDestroy()
    }
}
