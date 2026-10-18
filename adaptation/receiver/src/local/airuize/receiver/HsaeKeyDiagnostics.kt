package local.airuize.receiver

import android.content.Context
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/** Read-only discovery of the HSAE API present on the head unit. */
object HsaeKeyDiagnostics {
    private const val HMI = "com.hsae.autosdk.hmi.IHmi"
    private const val KEY_BIND = "com.hsae.autosdk.userprofile.IKeyBind"

    fun schema(context: Context): String {
        val out = LimitedReport(64 * 1024)
        out.line("HSAE API schema：只读取类和签名，不初始化 OEM 类，不调用 OEM 方法，不发送未知 Binder transaction。")
        out.line("实车服务 com.hsae.auto.IBINDER_HMI -> $HMI")
        out.line("实车服务 com.hsae.auto.IBINDER_KEY_BINDER -> $KEY_BIND")
        out.line("上述描述符来自实车 service list；本节未通过 OEM Binder 重新查询。")
        out.line("BOOTCLASSPATH=${runCatching { System.getenv("BOOTCLASSPATH") }.getOrNull() ?: "(不可用)"}")
        for (path in listOf("/system/framework", "/vendor/framework", "/system/vendor/framework")) {
            val dir = File(path)
            val files = runCatching { dir.listFiles() }.getOrNull()
            if (files == null) {
                out.line("FRAMEWORK $path：目录不存在或本应用不可读")
                continue
            }
            val relevant = files.filter {
                val n = it.name.toLowerCase(java.util.Locale.US)
                (n.endsWith(".jar") || n.endsWith(".odex")) &&
                    (n.contains("hsae") || n.contains("autosdk") || n.contains("auto-sdk") || n.contains("hmi"))
            }.sortedBy { it.name }
            out.line("FRAMEWORK $path：相关 jar/odex=${relevant.size}")
            relevant.forEach { out.line("  ${it.absolutePath} bytes=${it.length()} readable=${it.canRead()}") }
        }

        val pending = ArrayDeque<String>()
        val seen = LinkedHashSet<String>()
        for (name in listOf(HMI, HMI + "\$Stub", KEY_BIND, KEY_BIND + "\$Stub")) pending.add(name)
        var loaded = 0
        while (!pending.isEmpty() && seen.size < 32 && !out.full) {
            val name = pending.removeFirst()
            if (!seen.add(name)) continue
            val type = try {
                // Reading static field values would initialize the class, so no Field.get calls below.
                Class.forName(name, false, context.classLoader)
            } catch (error: Throwable) {
                out.line("CLASS $name：加载失败 ${error.javaClass.name}: ${error.message}")
                continue
            }
            loaded++
            out.line("CLASS ${type.name} modifiers=${Modifier.toString(type.modifiers)}")
            runCatching { type.interfaces }.onSuccess { interfaces ->
                out.line("  interfaces=${interfaces.joinToString { it.name }}")
            }.onFailure { out.line("  interfaces unavailable: ${it.javaClass.simpleName}: ${it.message}") }
            val methods = LinkedHashMap<String, Method>()
            for ((label, read) in listOf<Pair<String, () -> Array<Method>>>(
                "declared" to { type.declaredMethods }, "public" to { type.methods })) {
                runCatching { read().map { signature(it) to it } }.onSuccess { found ->
                    for ((signature, method) in found) {
                        val key = method.declaringClass.name + ":" + signature
                        if (!methods.containsKey(key)) {
                            methods[key] = method
                            out.line("  $label ${method.declaringClass.name}.$signature")
                        }
                    }
                }.onFailure { out.line("  $label methods unavailable: ${it.javaClass.simpleName}: ${it.message}") }
            }
            for (method in methods.values) {
                runCatching { method.parameterTypes.toList() + method.returnType }.onSuccess { types ->
                    for (rawType in types) {
                        var candidate = rawType
                        while (candidate.isArray) candidate = candidate.componentType
                        val n = candidate.name
                        if (n.startsWith("com.hsae.") &&
                            (candidate.isInterface || n.contains("Listener") || n.contains("Callback") || n.contains("CallBack"))) {
                            out.line("  callback/API type available=$n (signature only; no subscription attempted)")
                            if (!seen.contains(n)) pending.add(n)
                        }
                    }
                }.onFailure { out.line("  callback types unavailable: ${it.javaClass.simpleName}: ${it.message}") }
            }
            runCatching { type.declaredFields }.onSuccess { fields ->
                fields.filter { it.name == "DESCRIPTOR" || it.name.startsWith("TRANSACTION_") }
                    .sortedBy { it.name }.forEach {
                        out.line("  FIELD ${Modifier.toString(it.modifiers)} ${typeLabel(it.type)} ${it.name}; value未读取（避免初始化OEM类）")
                    }
            }.onFailure { out.line("  fields unavailable: ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { type.declaredClasses }.onSuccess { classes ->
                classes.filter { it.name.startsWith("com.hsae.") }.forEach { if (!seen.contains(it.name)) pending.add(it.name) }
            }.onFailure { out.line("  nested classes unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        }
        if (!pending.isEmpty()) out.line("[达到32个类型或64KB上限，尚有类型未展开]")
        out.line("HSAE_SCHEMA_LOADED=$loaded")
        out.line("若 IHmi/IKeyBind 加载失败，本次仍缺车机 OEM SDK 类定义；服务名和日志数值不足以确定安全的回调注册协议。")
        return out.toString()
    }

    /** Insert before the prompted key window in the existing, already-authorized ROOT script. */
    fun rootCaptureStartShell(basePath: String): String = """
        diplay_hmi_status=${shellQuote(basePath + ".hmi.status")}
        rm -f "${'$'}diplay_hmi_status"
        diplay_hmi_start=${'$'}(date '+%m-%d %H:%M:%S' 2>/dev/null)
        echo "HSAE_HMI_WINDOW_BEGIN=${'$'}diplay_hmi_start"
        echo "HSAE_HMI_BEGIN_UPTIME=${'$'}(cat /proc/uptime 2>/dev/null)"
        echo 'HSAE_HMI_MODE=main-buffer snapshot at window end; second precision; no background collector'
    """.trimIndent()

    /**
     * Insert after the key window, in the same ROOT script as rootCaptureStartShell.
     * The finite logcat dump is drained completely; output is bounded and no PID is signalled.
     * A log ring overwrite can lose events, so an empty snapshot never proves no key was pressed.
     */
    fun rootCaptureFinishShell(): String = """
        diplay_hmi_end=${'$'}(date '+%m-%d %H:%M:%S' 2>/dev/null)
        echo "HSAE_HMI_WINDOW_END=${'$'}diplay_hmi_end"
        echo "HSAE_HMI_END_UPTIME=${'$'}(cat /proc/uptime 2>/dev/null)"
        echo HSAE_HMI_CAPTURE_BEGIN
        {
          logcat -d -b main -v threadtime -s HmiManager:V '*:S' 2>&1
          echo "${'$'}?" > "${'$'}diplay_hmi_status"
        } | (
          LC_ALL=C
          export LC_ALL
          diplay_hmi_bytes=0
          diplay_hmi_lines=0
          diplay_hmi_dropped=0
          diplay_hmi_errors=0
          diplay_hmi_valid=1
          diplay_hmi_start_day=${'$'}{diplay_hmi_start%% *}
          diplay_hmi_end_day=${'$'}{diplay_hmi_end%% *}
          if [ -z "${'$'}diplay_hmi_start" ] || [ -z "${'$'}diplay_hmi_end" ]; then
            diplay_hmi_valid=0
            echo HSAE_HMI_TIMESTAMP_UNAVAILABLE
          elif [ "${'$'}diplay_hmi_start_day" = "${'$'}diplay_hmi_end_day" ] && [ "${'$'}diplay_hmi_end" \< "${'$'}diplay_hmi_start" ]; then
            diplay_hmi_valid=0
            echo HSAE_HMI_WALLCLOCK_REVERSED
          fi
          while IFS= read -r diplay_hmi_line; do
            case "${'$'}diplay_hmi_line" in
              ??-??\ ??:??:??.*)
                [ "${'$'}diplay_hmi_valid" -eq 1 ] || continue
                diplay_hmi_day=${'$'}{diplay_hmi_line%% *}
                diplay_hmi_rest=${'$'}{diplay_hmi_line#* }
                diplay_hmi_clock=${'$'}{diplay_hmi_rest%% *}
                diplay_hmi_stamp="${'$'}diplay_hmi_day ${'$'}{diplay_hmi_clock%%.*}"
                if [ "${'$'}diplay_hmi_end" \< "${'$'}diplay_hmi_start" ]; then
                  if [ "${'$'}diplay_hmi_stamp" \< "${'$'}diplay_hmi_start" ] && [ "${'$'}diplay_hmi_stamp" \> "${'$'}diplay_hmi_end" ]; then continue; fi
                else
                  if [ "${'$'}diplay_hmi_stamp" \< "${'$'}diplay_hmi_start" ] || [ "${'$'}diplay_hmi_stamp" \> "${'$'}diplay_hmi_end" ]; then continue; fi
                fi
                diplay_hmi_size=${'$'}(( ${'$'}{#diplay_hmi_line} + 1 ))
                if [ "${'$'}((diplay_hmi_bytes + diplay_hmi_size))" -le 60000 ]; then
                  echo "${'$'}diplay_hmi_line"
                  diplay_hmi_bytes=${'$'}((diplay_hmi_bytes + diplay_hmi_size))
                  diplay_hmi_lines=${'$'}((diplay_hmi_lines + 1))
                else
                  diplay_hmi_dropped=${'$'}((diplay_hmi_dropped + 1))
                fi
                ;;
              '--------- beginning of '*) ;;
              *)
                if [ "${'$'}diplay_hmi_errors" -lt 4 ]; then echo "HSAE_HMI_LOGCAT_DIAGNOSTIC=${'$'}{diplay_hmi_line:0:512}"; fi
                diplay_hmi_errors=${'$'}((diplay_hmi_errors + 1))
                ;;
            esac
          done
          echo "HSAE_HMI_EVENT_LINES=${'$'}diplay_hmi_lines DATA_BYTES=${'$'}diplay_hmi_bytes DROPPED_LINES=${'$'}diplay_hmi_dropped"
          if [ "${'$'}diplay_hmi_dropped" -gt 0 ]; then echo 'HSAE_HMI_TRUNCATED=60000 byte data budget; total section under64KB'; fi
          echo 'HSAE_HMI_LIMIT=log ring may overwrite events; boundaries have second precision; empty output is inconclusive'
        )
        if [ -r "${'$'}diplay_hmi_status" ]; then
          read -r diplay_hmi_rc < "${'$'}diplay_hmi_status"
          echo "HSAE_HMI_LOGCAT_EXIT=${'$'}diplay_hmi_rc"
        else
          echo HSAE_HMI_LOGCAT_EXIT=unavailable
        fi
        rm -f "${'$'}diplay_hmi_status"
        echo HSAE_HMI_CAPTURE_END
    """.trimIndent()

    private fun signature(method: Method): String =
        "${Modifier.toString(method.modifiers)} ${typeLabel(method.returnType)} ${method.name}" +
            "(${method.parameterTypes.joinToString { typeLabel(it) }})" +
            if (method.exceptionTypes.isEmpty()) "" else " throws ${method.exceptionTypes.joinToString { it.name }}"

    private fun typeLabel(type: Class<*>): String = if (type.isArray) typeLabel(type.componentType) + "[]" else type.name

    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"

    private class LimitedReport(private val byteLimit: Int) {
        private val text = StringBuilder()
        private var bytes = 0
        var full = false
            private set

        fun line(value: String) {
            if (full) return
            val count = value.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + count > byteLimit - 128) { full = true; return }
            text.append(value).append('\n')
            bytes += count
        }

        override fun toString(): String = text.toString() + if (full) "[HSAE schema 达到64KB上限，后续省略]\n" else ""
    }
}
