package local.airuize.receiver

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.NmeaLocationEncoder

/** Retain upstream coordinates/time encoding without inventing unavailable GPS fields. */
object LegacyLocationEncoder {
    fun encode(fix: CarPlayLocationFix): String = NmeaLocationEncoder.encode(fix)
        .split("\r\n").filter { it.isNotEmpty() }.joinToString("\r\n", postfix = "\r\n") { sentence ->
            val fields = sentence.substring(1).substringBefore('*').split(',').toMutableList()
            if (fields[0] == "GPGGA") {
                fields[7] = "" // Satellite count is not supplied by Location.
                fields[8] = "" // Accuracy in metres is not measured HDOP.
                if (fix.altitudeMeters?.isFinite() != true) { fields[9] = ""; fields[10] = "" }
                fields[11] = ""; fields[12] = "" // Geoid separation is unknown.
            } else if (fields[0] == "GPRMC") {
                if (fix.speedMetersPerSecond?.let { it.isFinite() && it >= 0 } != true) fields[7] = ""
                if (fix.bearingDegrees?.let { it.isFinite() && it >= 0 && it < 360 } != true) fields[8] = ""
            }
            val body = fields.joinToString(",")
            var checksum = 0
            body.forEach { checksum = checksum xor it.code }
            "$" + body + "*" + String.format(java.util.Locale.US, "%02X", checksum)
        }
}
