package local.airuize.receiver

import android.app.Application

/**
 * 进程入口：在任何 Activity / Service / BroadcastReceiver（含开机广播、USB 插入拉起）创建之前
 * 最早安装全局崩溃兜底，保证不存在“CrashGuard 还没装好就崩溃”的时间窗。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        runCatching { CrashGuard.install(this) }
    }

    companion object {
        @JvmStatic
        @Volatile
        var appContext: Application? = null
            private set
    }
}
