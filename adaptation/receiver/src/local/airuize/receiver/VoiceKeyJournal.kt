package local.airuize.receiver

import android.os.SystemClock
import android.view.KeyEvent

object VoiceKeyJournal {
    val gate = VoiceKeyGate()
    private val entries = java.util.ArrayDeque<String>()
    @Volatile var captureUntil = 0L
    @Volatile private var captureStart = 0L
    private var dropped = 0

    @Synchronized fun beginCapture(durationMs: Long): Long {
        entries.clear()
        dropped = 0
        captureStart = SystemClock.elapsedRealtime()
        captureUntil = captureStart + durationMs
        add("采集开始：elapsedRealtime=${captureStart}ms；窗口=${durationMs}ms")
        return captureStart
    }

    fun isCapturing(): Boolean = SystemClock.elapsedRealtime() < captureUntil

    @Synchronized fun add(line: String) {
        if (entries.size >= 256) { entries.removeFirst(); dropped++ }
        entries.addLast(line)
    }

    @Synchronized fun recordMcu(raw: Int) {
        if (!isCapturing()) return
        val ms = SystemClock.elapsedRealtime() - captureStart
        add("[+${ms}ms] MCU raw=$raw unsigned=${raw and 255} hex=0x${Integer.toHexString(raw)} voice=${VoiceKeyPolicy.isMcuVoice(raw)}")
    }

    @Synchronized fun recordFramework(where: String, event: KeyEvent) {
        if (!isCapturing()) return
        val ms = SystemClock.elapsedRealtime() - captureStart
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> event.action.toString()
        }
        add("[+${ms}ms] KEY @$where $action code=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) scan=${event.scanCode} device=${event.deviceId} source=0x${Integer.toHexString(event.source)} repeat=${event.repeatCount} flags=0x${Integer.toHexString(event.flags)} eventTime=${event.eventTime}")
    }

    @Synchronized fun finishCapture(): List<String> {
        captureUntil = 0L
        val lines = entries.toList()
        entries.clear()
        return if (dropped > 0) lines + "[早期事件因 256 条上限省略 $dropped 条]" else lines
    }

    @Synchronized fun drain(): List<String> {
        val lines = entries.toList(); entries.clear(); return lines
    }
}
