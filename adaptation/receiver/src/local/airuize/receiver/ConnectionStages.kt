package local.airuize.receiver

/** UI progress is updated at the log producer; the main thread never scans the log ring. */
internal object ConnectionStages {
    val items = listOf(
        "USB enumeration" to "USB枚举", "USB descriptors" to "读取USB描述符",
        "vendor transition" to "CarPlay切换", "SETCFG_CONFIRMED" to "ROOT切USB配置",
        "本地 IPv6 TUN 已就绪" to "TUN建立", "USBMUX ready" to "USBMUX就绪",
        "KERNEL verified" to "内核Alt验证", "NCM interfaces claimed" to "NCM接口就绪",
        "Lockdown pairing" to "Lockdown配对", "TLS#1(StartSession) handshake done" to "TLS#1握手",
        "TLS#2(StartService) handshake done" to "TLS#2握手", "Lockdown carkit ready" to "CarKit就绪",
        "Wired iAP2 link ready" to "iAP2链路就绪", "NCM IPv6 TUN 桥接已启动" to "NCM桥接启动",
        "ncm bulk-out became ready" to "NCM首包发出", "ncm first inbound ipv6" to "NCM首个入站",
        "USB AirPlay active" to "AirPlay激活", "USB first video frame rendered" to "首个视频帧")

    fun advance(current: Int, message: String): Int {
        var result = current
        for (i in current + 1 until items.size) if (message.contains(items[i].first)) result = i
        return result
    }
}
