package local.airuize.receiver

/** Numeric first occurrences survive normal log rotation. No packets, identifiers or keys retained. */
internal class ConnectionMilestones {
    data class Snapshot(val attempt: Int, val startedMs: Long, val first: Map<String, Long>)
    private class Attempt(val id: Int, val start: Long) {
        val times = LongArray(keys.size) { -1L }
    }
    private val attempts = java.util.ArrayDeque<Attempt>()
    @Synchronized fun begin(id: Int, startedMs: Long) {
        attempts.addLast(Attempt(id, startedMs))
        while (attempts.size > 8) attempts.removeFirst()
    }
    @Synchronized fun record(id: Int, nowMs: Long, message: String) {
        val current = attempts.peekLast() ?: return
        if (id != current.id || nowMs < current.start) return
        for (i in keys.indices) if (current.times[i] < 0 && message.contains(keys[i].first))
            current.times[i] = nowMs - current.start
    }
    @Synchronized fun latest(): Snapshot? = attempts.peekLast()?.let(::copy)
    @Synchronized fun history(): List<Snapshot> = attempts.map(::copy)
    private fun copy(value: Attempt): Snapshot {
        val first = LinkedHashMap<String, Long>()
        for (i in keys.indices) if (value.times[i] >= 0) first[keys[i].second] = value.times[i]
        return Snapshot(value.id, value.start, first)
    }
    companion object {
        val keys = listOf(
            "USB enumeration" to "USB枚举", "USB descriptors" to "读取USB描述符",
            "vendor transition" to "CarPlay切换(0x52)", "SETCFG_CONFIRMED" to "ROOT切USB配置",
            "本地 IPv6 TUN 已就绪" to "TUN建立", "USBMUX ready" to "USBMUX就绪",
            "KERNEL verified" to "内核Alt验证", "NCM interfaces claimed" to "NCM接口就绪",
            "Lockdown pairing" to "Lockdown配对/信任", "TLS#1(StartSession) handshake done" to "TLS#1握手",
            "TLS#2(StartService) handshake done" to "TLS#2握手", "Lockdown carkit ready" to "CarKit就绪",
            "Wired iAP2 link ready" to "iAP2链路就绪", "NCM IPv6 TUN 桥接已启动" to "NCM桥接启动",
            "ncm bulk-out became ready" to "NCM首包发出", "ncm first inbound ipv6" to "NCM首个入站",
            "USB AirPlay listener ready" to "AirPlay监听", "USB AirPlay TCP accepted" to "AirPlay TCP接入",
            "airplay diag request stage=pair-verify status=200" to "AirPlay配对验证响应200",
            "AirPlay 控制通道加密已启用" to "AirPlay控制加密", "AirPlay 请求：SETUP" to "音视频SETUP请求",
            "airplay diag event encryption-ready" to "事件通道加密", "USB AirPlay active" to "AirPlay RECORD激活",
            "USB first video packet" to "首个视频数据", "USB first decoded video output released" to "首个解码输出",
            "USB first audio packet" to "首个解密音频包", "USB first audio write" to "首个音频写入",
            "USB audio playback progressed" to "音频播放头推进")
    }
}
