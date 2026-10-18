package local.airuize.receiver

import android.os.Build
import java.security.MessageDigest

/** Firmware-scoped learning identity. Never contains a phone id, coordinate, key or USB address. */
internal object LearningDevice {
    private val key: String by lazy {
        val material = "learning-schema-1|${Build.FINGERPRINT}|${Build.CPU_ABI}|${Build.VERSION.SDK_INT}"
        MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun id(): String = key
    fun label(): String = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
}
