package local.airuize.receiver

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File

/** Small API18 snapshots; never changes an audio route, volume, GPS setting or CPU governor. */
object FeatureEnvironmentDiagnostics {
    private fun value(read: () -> Any?): String = try { read()?.toString() ?: "未提供" }
        catch (error: Exception) { "不可读取:${error.javaClass.simpleName}" }
    private fun read(path: String, limit: Int = 80): String = try {
        val file = File(path)
        if (!file.exists()) "不存在/不可见" else file.bufferedReader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(limit + 1)
            var size = 0
            while (size < chars.size) { val n = reader.read(chars, size, chars.size - size); if (n < 0) break; size += n }
            String(chars, 0, minOf(size, limit)).trim().replace('\n', ' ') + if (size > limit) "[达到上限]" else ""
        }
    } catch (error: Exception) { "不可读取:${error.javaClass.simpleName}" }
    private fun storage(file: File): String = value {
        val stat = StatFs(file.path)
        val block = stat.blockSize.toLong()
        "availableMiB=${stat.availableBlocks.toLong() * block / 1048576L} totalMiB=${stat.blockCount.toLong() * block / 1048576L}"
    }
    fun snapshot(context: Context): String {
        val out = StringBuilder("功能环境只读快照 mono=${SystemClock.elapsedRealtime()}\n")
        val audio = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
        if (audio == null) out.append("AudioManager=未提供\n") else {
            out.append("AudioManager mode=${value { audio.mode }} microphoneMute=${value { audio.isMicrophoneMute }} ")
            out.append("speakerFlag=${value { audio.isSpeakerphoneOn }} wiredHeadsetFlag=${value { audio.isWiredHeadsetOn }} ")
            out.append("bluetoothA2dpFlag=${value { audio.isBluetoothA2dpOn }} bluetoothScoFlag=${value { audio.isBluetoothScoOn }} ")
            out.append("scoAvailableOffCall=${value { audio.isBluetoothScoAvailableOffCall }} musicActive=${value { audio.isMusicActive }}\n")
            for ((name, stream) in listOf("VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
                "SYSTEM" to AudioManager.STREAM_SYSTEM, "RING" to AudioManager.STREAM_RING,
                "MUSIC" to AudioManager.STREAM_MUSIC, "ALARM" to AudioManager.STREAM_ALARM,
                "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION, "DTMF" to AudioManager.STREAM_DTMF)) {
                out.append("stream=$name volume=${value { audio.getStreamVolume(stream) }}/${value { audio.getStreamMaxVolume(stream) }}\n")
            }
            out.append("上述系统flag/音量是采样值；OEM路由可能不通过标准AudioManager，不能据此证明声音来源或麦克风实际可用。\n")
        }
        val power = runCatching { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        out.append("power screenOn=${value { power?.isScreenOn }}；持有唤醒锁及Surface恢复另见本应用生命周期记录。\n")
        out.append("storage internal ${value { storage(context.filesDir) }} externalState=${value { Environment.getExternalStorageState() }} ")
        out.append("external ${value { storage(Environment.getExternalStorageDirectory()) }}\n")
        out.append("permissions:")
        for (permission in listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
            android.Manifest.permission.WAKE_LOCK, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            out.append(" ${permission.substringAfterLast('.')}=${value {
                context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED }}")
        }
        out.append('\n')
        out.append("memory ${value {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (manager == null) "ActivityManager未提供" else {
                val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
                "availableMiB=${info.availMem / 1048576L} thresholdMiB=${info.threshold / 1048576L} lowMemory=${info.lowMemory}"
            }
        }}\n")
        // Keep only load numbers and runnable/task counts; omit /proc/loadavg's last process id.
        val load = read("/proc/loadavg", 128)
        out.append("loadavg=${load.split(Regex("\\s+")).take(4).joinToString(" ")}\n")
        out.append("CPU frequency values are firmware kHz; thermal values use firmware units. No policy is changed.\n")
        for (number in 0..7) {
            val cpu = File("/sys/devices/system/cpu/cpu$number")
            val exists = try { cpu.exists() } catch (error: Exception) {
                out.append("cpu$number 不可查询:${error.javaClass.simpleName}\n"); false
            }
            if (!exists) continue
            out.append("cpu$number online=${read("${cpu.path}/online", 32)}")
            for (field in listOf("scaling_cur_freq", "scaling_min_freq", "scaling_max_freq", "cpuinfo_max_freq", "scaling_governor"))
                out.append(" $field=${read("${cpu.path}/cpufreq/$field", 48)}")
            out.append('\n')
        }
        var zones = 0
        for (number in 0..7) {
            val zone = File("/sys/class/thermal/thermal_zone$number")
            val exists = try { zone.exists() } catch (error: Exception) {
                out.append("thermal_zone$number 不可查询:${error.javaClass.simpleName}\n"); false
            }
            if (!exists) continue
            zones++
            out.append("thermal_zone$number type=${read("${zone.path}/type", 64)} temp=${read("${zone.path}/temp", 32)} mode=${read("${zone.path}/mode", 32)}\n")
        }
        if (zones == 0) out.append("thermal=无可见thermal_zone0..7；不能据此认定没有温度保护\n")
        val text = out.toString()
        return if (text.length <= 10000) text else text.take(9900) + "\n[功能环境快照达到10000字符上限]\n"
    }
}
