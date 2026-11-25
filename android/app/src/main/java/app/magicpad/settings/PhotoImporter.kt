// The platform ExifInterface is used only on API 26–27 (minSdk 26), where it reads the
// orientation tag from a stream correctly; API 28+ uses ImageDecoder, which applies it itself.
@file:SuppressLint("ExifInterface")

package app.magicpad.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure sizing maths for the pad photo. */
object PhotoSizing {
    /**
     * Uniform scale (≤ 1, never upscaling) that makes a [w]×[h] image just cover a
     * landscape pad of [screenLong]×[screenShort], i.e. at most what the pad can show.
     */
    fun coverScale(
        w: Int,
        h: Int,
        screenLong: Int,
        screenShort: Int,
    ): Float {
        if (w <= 0 || h <= 0) return 1f
        return min(1f, max(screenLong.toFloat() / w, screenShort.toFloat() / h))
    }
}

/**
 * Copies a picked image into app storage, upright (EXIF orientation applied) and no larger
 * than the pad needs, so it survives reboots without keeping a URI permission.
 */
object PhotoImporter {
    private const val TAG = "MagicPad"
    private const val JPEG_QUALITY = 90

    /** Blocking; call on a background thread. Returns false if the image could not be read. */
    fun import(
        context: Context,
        uri: Uri,
        dest: File,
    ): Boolean {
        val dm = context.resources.displayMetrics
        val long = max(dm.widthPixels, dm.heightPixels)
        val short = min(dm.widthPixels, dm.heightPixels)
        val bitmap =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeP(context, uri, long, short) else decodeLegacy(context, uri, long, short)
            } catch (e: IOException) {
                Log.w(TAG, "could not read the picked photo", e)
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "no access to the picked photo", e)
                null
            } ?: return false
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            tmp.renameTo(dest)
        } catch (e: IOException) {
            Log.w(TAG, "could not save the photo", e)
            false
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    /** ImageDecoder applies EXIF orientation itself. */
    private fun decodeP(
        context: Context,
        uri: Uri,
        long: Int,
        short: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val s = PhotoSizing.coverScale(info.size.width, info.size.height, long, short)
            decoder.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** API 26–27: subsample while decoding, then scale and rotate per EXIF. */
    private fun decodeLegacy(
        context: Context,
        uri: Uri,
        long: Int,
        short: Int,
    ): Bitmap? {
        val resolver = context.contentResolver
        val rotation =
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val upright = rotation == 90 || rotation == 270
        val w = if (upright) bounds.outHeight else bounds.outWidth
        val h = if (upright) bounds.outWidth else bounds.outHeight
        val scale = PhotoSizing.coverScale(w, h, long, short)
        var sample = 1
        while (scale * sample * 2 <= 1f) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val m = Matrix()
        val remaining = scale * sample
        if (remaining < 1f) m.postScale(remaining, remaining)
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (m.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
}
