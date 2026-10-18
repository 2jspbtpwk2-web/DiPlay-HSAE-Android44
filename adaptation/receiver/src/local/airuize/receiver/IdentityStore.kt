package local.airuize.receiver

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity

/** Persists the head unit's long-term Ed25519 AirPlay identity (shared by the wired session). */
object IdentityStore {
    fun load(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences("receiver-identity", Context.MODE_PRIVATE)
        val saved = prefs.getString("private", null)
        if (saved != null) return AirPlayIdentity(
            android.util.Base64.decode(saved, 2),
            android.util.Base64.decode(prefs.getString("public", ""), 2),
            prefs.getString("pairingId", "")!!
        )
        return AirPlayIdentity.generate().also { id ->
            prefs.edit()
                .putString("private", android.util.Base64.encodeToString(id.privateKey, 2))
                .putString("public", android.util.Base64.encodeToString(id.publicKey, 2))
                .putString("pairingId", id.pairingId).commit()
        }
    }
}
