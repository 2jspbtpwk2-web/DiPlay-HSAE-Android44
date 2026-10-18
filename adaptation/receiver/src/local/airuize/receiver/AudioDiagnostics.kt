package local.airuize.receiver

import java.util.Locale

object AudioDiagnostics {
    fun purpose(value: String): String = when (val normalized = value.toLowerCase(Locale.US)) {
        "media", "telephony", "speechrecognition", "alert", "default", "compatibility" -> normalized
        else -> "其他"
    }
    fun androidStreamName(stream: Int): String = when (stream) {
        0 -> "STREAM_VOICE_CALL"
        3 -> "STREAM_MUSIC"
        5 -> "STREAM_NOTIFICATION"
        else -> "其他"
    }
}
