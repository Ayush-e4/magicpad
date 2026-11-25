package app.magicpad

import android.content.Context
import androidx.core.content.edit
import app.magicpad.net.Crypto
import app.magicpad.net.toHex
import java.util.Base64

/** This phone's id and the pairing key for each Mac, in private app storage. */
class Store(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("magicpad", Context.MODE_PRIVATE)

    val deviceId: String =
        prefs.getString(KEY_DEVICE, null) ?: Crypto.randomBytes(16).toHex().also { id ->
            prefs.edit { putString(KEY_DEVICE, id) }
        }

    fun pairingKey(macId: String): ByteArray? = prefs.getString(KEY_PAIR + macId, null)?.let { Base64.getDecoder().decode(it) }

    fun savePairingKey(
        macId: String,
        key: ByteArray,
    ) = prefs.edit { putString(KEY_PAIR + macId, Base64.getEncoder().encodeToString(key)) }

    fun forget(macId: String) = prefs.edit { remove(KEY_PAIR + macId) }

    /** The last Mac a session reached `ready` with: its discovery id and service name. */
    val lastMacId: String? get() = prefs.getString(KEY_LAST_ID, null)
    val lastMacName: String? get() = prefs.getString(KEY_LAST_NAME, null)

    fun saveLastMac(
        id: String,
        name: String,
    ) = prefs.edit {
        putString(KEY_LAST_ID, id)
        putString(KEY_LAST_NAME, name)
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_PAIR = "pair_"
        const val KEY_LAST_ID = "last_mac_id"
        const val KEY_LAST_NAME = "last_mac_name"
    }
}
