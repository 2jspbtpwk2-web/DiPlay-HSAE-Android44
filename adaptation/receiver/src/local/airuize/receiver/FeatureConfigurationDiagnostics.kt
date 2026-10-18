package local.airuize.receiver

object FeatureConfigurationDiagnostics {
    fun describe(current: ReceiverSettings, frozen: ReceiverSettings?, advertisedGps: Boolean?): String {
        fun fields(s:ReceiverSettings) = "microphone=${s.microphone} gps=${s.gps} opus=${s.opus} hevc=${s.hevc} " +
            "audioFocus=${s.audioFocus} navigationDuck=${s.navigationDuck} bufferMs=${s.bufferMillis} volume=${s.volume} " +
            "mediaChannel=${s.mediaChannel} navigationChannel=${s.navigationChannel}"
        val preferences="偏好补充：bootConnect=${current.bootConnect} reconnect=${current.reconnect} background=${current.background} fullScreen=${current.fullScreen}；后台/自动重试实际行为请结合唤醒、前后台及连接事件，偏好值本身不证明已执行。\n"
        return preferences + "功能设置当前值：${fields(current)}\n" + if(frozen==null)
            "当前没有本轮冻结配置；未连接时不推断手机协商结果"
        else "本轮连接冻结值：${fields(frozen)}\n本轮GPS能力通告=${advertisedGps ?: "尚未确定"}；" +
            "mic或GPS开关与冻结值不同=${current.microphone!=frozen.microphone || current.gps!=frozen.gps}；更改需重新连接。" +
            "开关打开不等于手机已请求/实际使用；关闭时零数据不代表硬件坏。\n" +
            "本轮CarPlay输入端点配置=true；本轮采集模式capture=${frozen.microphone} syntheticSilence=${!frozen.microphone}；实际通告/请求见info/SETUP证据；" +
            "麦克风关闭时仅发送合成静音以保留音频设备兼容性，不打开AudioRecord；打开后重连才采集车机麦克风。"
    }
}
