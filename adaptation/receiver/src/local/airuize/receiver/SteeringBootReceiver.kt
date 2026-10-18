package local.airuize.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore enabled steering listener; upstream-style host launch when boot-connect is enabled. */
class SteeringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 开机广播在主线程、且可能是进程被拉起后执行的第一段代码，整体兜底，绝不因恢复逻辑闪退。
        try {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            if (ReceiverSettings.read(context).bootConnect) {
                context.startService(
                    Intent(context, LegacyWiredService::class.java)
                )
                context.startActivity(
                    Intent(context, ReceiverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            if (!context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
                    .getBoolean("steeringVoice", false)
            ) return
            try {
                context.startService(Intent(context, SteeringVoiceService::class.java))
            } catch (error: Throwable) {
                VoiceKeyJournal.add("开机恢复方向盘监听失败：${error.javaClass.simpleName}:${error.message}")
            }
        } catch (error: Throwable) {
            VoiceKeyJournal.add("Boot CarPlay launch failed: ${error.javaClass.simpleName}:${error.message}")
        }
    }
}
