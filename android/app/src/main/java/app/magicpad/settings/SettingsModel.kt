package app.magicpad.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.magicpad.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The pad look settings as observable UI state, saved to SharedPreferences on every change. */
class SettingsModel(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = Store(appContext)

    var value: PadSettings by mutableStateOf(store.loadPadSettings())
        private set

    /** Changes whenever a new photo is saved; 0 = no photo yet. */
    var photoVersion: Long by mutableLongStateOf(store.photoVersion)
        private set

    val photoFile: File = File(appContext.filesDir, PHOTO_FILE)

    fun update(change: (PadSettings) -> PadSettings) {
        val next = change(value)
        if (next == value) return
        value = next
        store.savePadSettings(next)
    }

    /** Copies the picked image into app storage; false if it could not be read. */
    suspend fun importPhoto(uri: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) { PhotoImporter.import(appContext, uri, photoFile) }
        if (ok) {
            photoVersion = System.currentTimeMillis()
            store.photoVersion = photoVersion
        }
        return ok
    }

    private companion object {
        const val PHOTO_FILE = "pad_photo.jpg"
    }
}
