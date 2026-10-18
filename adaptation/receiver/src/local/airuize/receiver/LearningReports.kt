package local.airuize.receiver

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small metadata export; never starts ROOT, GPS, audio capture or a diagnostic run. */
internal object LearningReports {
    fun snapshot(context: Context): String = "DiPlay 自学习简报 ${ReceiverActivity.VERSION}\n" +
        "只含本地统计与明确用户确认；不能替代实车连接/听音/切歌验证。\n" +
        PerformanceLearning.exportText() + "\n\n" + SteeringLearning.snapshot(context) +
        "\n\n" + FeatureLearning.snapshot(context) +
        "\n\n音乐链路与连续性：\n" +
        (LegacyWiredService.instance?.audioDiagnosticSnapshot() ?: "服务未运行；本次简报无音频运行数据。")
    @Synchronized fun export(context: Context): File {
        val dir=File(Environment.getExternalStorageDirectory(),"DiPlay")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create report folder" }
        val stamp=SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(Date())
        val file=File(dir,"learning-diag-$stamp.txt")
        file.writeText(snapshot(context).take(65_536),Charsets.UTF_8)
        return file
    }
}
