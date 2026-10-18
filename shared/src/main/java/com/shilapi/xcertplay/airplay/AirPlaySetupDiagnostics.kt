package com.shilapi.xcertplay.airplay

/** Explicit metadata allowlist: never stringify a peer value, nested map, byte array, or identifier. */
internal object AirPlaySetupDiagnostics {
    fun request(type: Int, stream: Map<String, Any?>): String {
        val kind = when (type) {
            110, 111 -> "screen"
            100, 101, 102 -> "audio"
            130 -> "data"
            else -> "other"
        }
        val purpose = (stream["audioType"] as? String)?.lowercase()
            ?.takeIf { it in listOf("media", "telephony", "speechrecognition", "alert", "default", "compatibility") } ?: "unknown"
        return "airplay diag setup requested kind=$kind type=${number(type, 65535)} " +
            "width=${number(stream["width"] ?: stream["widthPixels"], 16384)} " +
            "height=${number(stream["height"] ?: stream["heightPixels"], 16384)} " +
            "fps=${number(stream["fps"], 240)} " +
            "audioFormat=${number(stream["audioFormat"], 0xffffffffL)} " +
            "sampleRate=${number(stream["sampleRate"], 384000)} " +
            "channels=${number(stream["channels"], 32)} " +
            "latencyMs=${number(stream["audioLatencyMs"], 60000)} " +
            "framesPerPacket=${number(stream["framesPerPacket"], 65535)} " +
            "peerDataPort=${number(stream["dataPort"], 65535)} " +
            "peerControlPort=${number(stream["controlPort"], 65535)} audioType=$purpose"
    }

    fun result(type: Int, kind: String, accepted: Boolean, response: Map<String, Any?>?): String {
        val safeKind = kind.takeIf { it in listOf("screen", "audio", "data") } ?: "other"
        return "airplay diag setup result kind=$safeKind type=${number(type, 65535)} accepted=$accepted " +
            "dataPort=${number(response?.get("dataPort"), 65535)} " +
            "controlPort=${number(response?.get("controlPort"), 65535)} " +
            "timingPort=${number(response?.get("timingPort"), 65535)}"
    }

    fun advertised(config: AirPlayConfig): String =
        "airplay diag advertised width=${number(config.main.widthPixels, 16384)} " +
            "height=${number(config.main.heightPixels, 16384)} fps=${number(config.main.fps, 240)} " +
            "hevc=${config.hevc} microphone=${config.microphone} audioRate=${number(config.entertainmentSampleRate, 384000)}"

    private fun number(value: Any?, maximum: Long): String {
        val numeric = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> return "unknown"
        }
        return numeric.takeIf { it in 0L..maximum }?.toString() ?: "unknown"
    }
}

