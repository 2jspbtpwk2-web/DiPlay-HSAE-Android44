package com.shilapi.xcertplay.airplay

/** Bounded protocol metadata. Never retain commands, nested maps, payloads, addresses or identifiers. */
internal class AirPlayAudioEndpointDiagnostics {
    private val originNs = System.nanoTime()
    private val events = java.util.ArrayDeque<String>()
    private var commands = 0L
    private var audioRequests = 0L

    @Synchronized fun advertised(config: AirPlayConfig, info: Map<String, Any?>): String = append(
        "advertised output=${!config.disableAudioOutput} inputEndpoint=${config.microphone} " +
            "entertainmentRate=${numeric(config.entertainmentSampleRate, 0, 384000)} " +
            "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
            "inputFormats=${(info["audioFormats"] as? List<*>)?.count { (it as? Map<*,*>)?.get("audioInputFormats") is Number } ?: 0}")

    @Synchronized fun requested(type: Int, stream: Map<String, Any?>) {
        if(type in 100..102) {
            audioRequests++
            append(AirPlaySetupDiagnostics.request(type, stream))
        }
    }

    @Synchronized fun command(type: String, params: Map<String, Any?>): String {
        commands++
        val safeType = type.takeIf { it in TYPES } ?: "other"
        val detail = if(type == "modesChanged") {
            val resources = (params["resources"] as? List<*>)?.take(8)?.mapNotNull { value ->
                val resource = value as? Map<*, *> ?: return@mapNotNull null
                "id=${numeric(resource["resourceID"], 1, 2)} owner=${numeric(resource["entity"], 0, 2)} " +
                    "permanent=${numeric(resource["permanentEntity"], 0, 2)}"
            }?.joinToString(" | ") ?: "missing"
            val apps = (params["appStates"] as? List<*>)?.take(8)?.mapNotNull { value ->
                val app = value as? Map<*, *> ?: return@mapNotNull null
                "id=${numeric(app["appStateID"], 1, 3)} owner=${numeric(app["entity"], 0, 2)} " +
                    "speechMode=${numeric(app["speechMode"], -1, 3)}"
            }?.joinToString(" | ") ?: "missing"
            " resources=[$resources] appStates=[$apps]"
        } else " fieldCount=${params.size.coerceAtMost(1000)}"
        return append("command=$safeType$detail")
    }

    @Synchronized fun snapshot(): String =
        "CarPlay音频端点：clockOriginNs=$originNs commands=$commands audioSetupRequests=$audioRequests；" +
            "modes owner=1为iPhone、2为车机、0为无；输入端点不等于物理录音，发送/播放头不等于实际出声。\n" +
            events.joinToString("\n")

    private fun append(message: String): String {
        val line = "+${(System.nanoTime()-originNs)/1_000_000}ms $message"
        events.addLast(line)
        while(events.size>32) events.removeFirst()
        return "airplay audio endpoint $line"
    }

    private fun numeric(value: Any?, minimum: Int, maximum: Int): String = when(value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it in minimum.toLong()..maximum.toLong() }?.toString() ?: "unknown"
        else -> "unknown"
    }

    private companion object {
        val TYPES=setOf("modesChanged", "duckAudio", "unduckAudio", "flushAudio", "setUpStreams",
            "tearDownStreams", "requestUI", "hidSetReport", "hidSetInputMode", "hidCopyInputMode",
            "setNightMode", "setLimitedUI", "updateVehicleInformation", "disableBluetooth")
    }
}
