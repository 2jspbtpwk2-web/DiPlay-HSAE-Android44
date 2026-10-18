package local.airuize.receiver

import java.util.concurrent.atomic.AtomicBoolean

/** Bounded metadata only. This survives Activity replacement without retaining a Context. */
object FunctionRunDiagnostics {
    private val siriPending = AtomicBoolean()
    private val events = java.util.ArrayDeque<String>()
    private val epoch = System.nanoTime()
    @Synchronized fun event(kind: String, value: String) {
        val allowed = setOf("ui", "surface", "siri", "diagnostic", "wake")
        if (kind !in allowed) return
        val safe = value.take(180).filter { it.isLetterOrDigit() || it in " =,._:-" }
        events.addLast("monoDeltaMs=${(System.nanoTime()-epoch)/1_000_000} $kind $safe")
        while (events.size > 64) events.removeFirst()
    }
    fun beginSiri(): Boolean = siriPending.compareAndSet(false, true).also { if(it) event("siri", "requested") }
    fun isSiriPending(): Boolean = siriPending.get()
    fun finishSiri(outcome: String) { event("siri",outcome); siriPending.set(false) }
    @Synchronized fun snapshot(): String = "功能事件（最多64项；发送不等于手机采用/语音识别）：siriPending=${siriPending.get()} originNanoTime=$epoch snapshotNanoTime=${System.nanoTime()}\n" + events.joinToString("\n")
}

object DiagnosticRunState {
    private val running = AtomicBoolean()
    val isRunning: Boolean get() = running.get()
    fun begin(): Boolean = running.compareAndSet(false, true)
    fun finish() { running.set(false) }
}

/** Producer supplies only whitelisted protocol metadata, never a config/key/address or payload. */
class MicrophoneNegotiationDiagnostics {
    private val records = java.util.ArrayDeque<String>()
    private val fields = Regex("^(?:reason=(?:disabled|stream-type|audio-purpose|missing-port|invalid-port|missing-peer|missing-input-key|missing-output-key)|waiting-first-audio|first-audio|stopped|negotiated codec=(?:LPCM|AAC_LC|OPUS) rate=[0-9]{1,6} channels=[0-9]{1,2} frameMs=[0-9]{1,3} family=(?:ipv4|ipv6|other) scope=[0-9]{1,10})$")
    private val epoch = System.nanoTime()
    @Synchronized fun record(type: Int, purpose: String, status: String) {
        if(type !in 0..65535 || !fields.matches(status)) return
        val safePurpose = AudioDiagnostics.purpose(purpose)
        records.addLast("+${(System.nanoTime()-epoch)/1_000_000}ms type=$type purpose=$safePurpose $status")
        while(records.size>48) records.removeFirst()
    }
    @Synchronized fun snapshot(): String = "麦克风协商时间线（最多48项；等待首下行音频再启动输入上行，实际录音/合成静音见capture与逐流证据）：originNanoTime=$epoch\n" +
        if(records.isEmpty()) "未观察到音频SETUP/麦克风请求；不能据此判断硬件故障" else records.joinToString("\n")
}
