package local.airuize.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常兜底，目标是“任何故障都不闪退”。
 *
 * - 后台线程（USB / USBMUX / TLS / NCM / AirPlay / 音视频解码等所有 IO 与 worker 线程）抛出未被
 *   捕获的 Throwable（含 NoClassDefFoundError / ExceptionInInitializerError / VerifyError /
 *   OutOfMemoryError 等 Error）时：先把完整堆栈写入外部存储 DiPlay/crash.log，然后**直接返回、
 *   不调用系统默认处理器**。这样只结束那一条出问题的线程，进程与界面继续存活，用户可以重新点
 *   「USB 连接」，而不是整个 App 闪退到桌面。
 * - 主线程（UI Looper）抛出未捕获 Throwable 时：落盘后通过 AlarmManager 在几百毫秒后把主页
 *   重新拉起，再结束本进程，用户感知为“自动回到 App 主页”，而不是闪退到桌面后无响应。带 30 秒
 *   崩溃闸门：若短时间内反复在主线程崩溃（说明是启动即崩的硬错误），放弃自重启、交回系统默认
 *   处理器，避免无限重启循环。
 *
 * 只写自身外部存储 DiPlay/crash.log，不修改系统、不写 /system。安装本身幂等且全程容错。
 */
object CrashGuard {
    private const val NAME = "crash.log"
    private const val KEEP_BYTES = 131072
    private const val REPORT_BYTES = 60000
    private const val PREFS = "diplay-crash"
    private const val KEY_LAST_MAIN = "lastMainCrashUptime"
    private const val RESTART_WINDOW_MS = 30_000L
    private const val RESTART_DELAY_MS = 600L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            val isMain = runCatching { Looper.getMainLooper().thread === thread }.getOrDefault(false)
            if (isMain) {
                // 主线程崩溃：尝试快速自重启回主页；失败或处于崩溃循环时交回系统默认处理器。
                val relaunched = runCatching { scheduleRelaunch(app) }.getOrDefault(false)
                if (relaunched) {
                    runCatching { Process.killProcess(Process.myPid()) }
                    runCatching { SystemClock.sleep(500) }
                }
                previous?.uncaughtException(thread, error)
            }
            // 非主线程：到此直接结束处理（不调用 previous、不杀进程）。抛出异常的后台线程自行
            // 终止，其余线程与 UI 不受影响——这是“不闪退”的关键。
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val dir = File(Environment.getExternalStorageDirectory(), "DiPlay").apply { mkdirs() }
        val file = File(dir, NAME)
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val isMain = Looper.getMainLooper().thread === thread
        val block = StringBuilder()
            .append("\n\n===== CRASH ").append(ts)
            .append(" thread=").append(thread.name)
            .append(" main=").append(isMain)
            .append(" pid=").append(Process.myPid()).append(" =====\n")
            .append(sw.toString())
            .toString()
        val old = if (file.exists()) file.readText() else ""
        val merged = old + block
        file.writeText(if (merged.length > KEEP_BYTES) merged.substring(merged.length - KEEP_BYTES) else merged)
    }

    /** 距上次主线程崩溃超过窗口才安排一次快速重启，否则返回 false 交给系统，防止无限重启循环。 */
    private fun scheduleRelaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong(KEY_LAST_MAIN, -1L)
        if (last >= 0 && now - last < RESTART_WINDOW_MS) return false
        prefs.edit().putLong(KEY_LAST_MAIN, now).commit()
        val intent = Intent(context, ReceiverActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        am.set(AlarmManager.ELAPSED_REALTIME, now + RESTART_DELAY_MS, pi)
        return true
    }

    fun recent(): String = try {
        val file = File(Environment.getExternalStorageDirectory(), "DiPlay/$NAME")
        if (file.exists()) file.readText().takeLast(REPORT_BYTES) else "(无崩溃记录，未发生过闪退)"
    } catch (error: Throwable) {
        "(崩溃记录读取失败:${error.javaClass.simpleName}:${error.message})"
    }
}
