package local.airuize.receiver

/** Also exclude raw protocol lines left in logcat by an older installed build. */
object ProtocolLogFilter {
    private val rawMarkers=listOf("payload=", "bodyhex=", "wirehex=", "bplisthex=", " headers=",
        "airplay /info request=", "airplay /info displays=", "airplay setup response streams=",
        "airplay setup keys=", "airplay command type=", "airplay touch report sent", " report=")
    fun filter(text:String):String {
        var removed=0
        val lines=text.lineSequence().filter { line ->
            val lower=line.lowercase()
            val raw=(lower.contains("airplay") || lower.contains("xcertplay")) && rawMarkers.any { lower.contains(it) }
            if(raw) removed++
            !raw
        }.toList()
        return lines.joinToString("\n")+"\n[原始协议内容行已过滤=$removed]"
    }
}
