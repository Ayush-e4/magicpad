package app.magicpad

import android.content.Context
import androidx.core.content.edit
import app.magicpad.net.Crypto
import app.magicpad.net.toHex
import app.magicpad.settings.PadBackground
import app.magicpad.settings.PadSettings
import app.magicpad.settings.StripPlace
import app.magicpad.settings.StripSide
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

    fun loadPadSettings(): PadSettings =
        PadSettings(
            background = PadBackground.fromKey(prefs.getString(KEY_BACKGROUND, null)),
            photoDim = prefs.getFloat(KEY_PHOTO_DIM, PadSettings.DEFAULT_PHOTO_DIM),
            brightness = if (prefs.contains(KEY_BRIGHTNESS)) prefs.getFloat(KEY_BRIGHTNESS, 0f) else null,
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false),
            shortcuts = prefs.getBoolean(KEY_SHORTCUTS, false),
            stripSide = StripSide.fromKey(prefs.getString(KEY_STRIP_SIDE, null)),
            stripPlace = StripPlace.fromKey(prefs.getString(KEY_STRIP_PLACE, null)),
        )

    fun savePadSettings(settings: PadSettings) =
        prefs.edit {
            putString(KEY_BACKGROUND, settings.background.key)
            putFloat(KEY_PHOTO_DIM, settings.photoDim)
            val b = settings.brightness
            if (b == null) remove(KEY_BRIGHTNESS) else putFloat(KEY_BRIGHTNESS, b)
            putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            putBoolean(KEY_SHORTCUTS, settings.shortcuts)
            putString(KEY_STRIP_SIDE, settings.stripSide.key)
            putString(KEY_STRIP_PLACE, settings.stripPlace.key)
        }

    /** Changes whenever a new pad photo is saved; 0 = none yet. */
    var photoVersion: Long
        get() = prefs.getLong(KEY_PHOTO_VERSION, 0L)
        set(value) = prefs.edit { putLong(KEY_PHOTO_VERSION, value) }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_PAIR = "pair_"
        const val KEY_LAST_ID = "last_mac_id"
        const val KEY_LAST_NAME = "last_mac_name"
        const val KEY_BACKGROUND = "pad_background"
        const val KEY_PHOTO_DIM = "pad_photo_dim"
        const val KEY_BRIGHTNESS = "pad_brightness"
        const val KEY_PHOTO_VERSION = "pad_photo_version"
        const val KEY_SHORTCUTS = "shortcuts_on"
        const val KEY_STRIP_SIDE = "shortcuts_side"
        const val KEY_STRIP_PLACE = "shortcuts_place"
        const val KEY_AUTO_CONNECT = "auto_connect_on_open" // renamed so the old default (on) is dropped
    }
}
