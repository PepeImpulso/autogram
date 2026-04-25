package com.autogram.app.infrastructure.platform

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import com.autogram.app.BuildConfig
import com.autogram.app.R
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture the current Activity window into a PNG, drop it into the public
 * Pictures/Autogram folder via MediaStore (so it shows up in the Gallery
 * with no extra permission on API 29+), and fire the system share sheet
 * so the user can immediately send it to the developer over Telegram /
 * email / whatever.
 *
 * Used by the on-screen screenshot button that appears in the Home header
 * when Settings → Developer mode is ON. Designed so the whole capture +
 * share flow is one tap from the user's POV.
 */
object ScreenshotCapture {

    /** PNG quality is irrelevant; PNG is lossless. The arg is ignored. */
    private const val PNG_QUALITY: Int = 100
    // Concatenation with Environment.DIRECTORY_PICTURES isn't a `const`
    // expression at compile time — `val` so it resolves at class init.
    private val MEDIA_RELATIVE_DIR: String = Environment.DIRECTORY_PICTURES + "/Autogram"

    /**
     * Capture [activity]'s root view into a bitmap, persist it to the
     * Gallery, and launch the system share sheet pointing at the new
     * file. Returns the saved [Uri] on success or null on failure.
     */
    fun captureAndShare(activity: Activity): Uri? {
        return try {
            val bitmap = activity.window.decorView.rootView.drawToBitmap()
            val uri = saveToGallery(activity, bitmap) ?: return null
            launchShareSheet(activity, uri)
            uri
        } catch (t: Throwable) {
            Timber.e(t, "ScreenshotCapture failed")
            null
        }
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val filename = "autogram-${DATE_FMT.format(Date())}.png"
        // MediaStore on API 29+ doesn't need WRITE_EXTERNAL_STORAGE for
        // public gallery folders we own (RELATIVE_PATH starts with the
        // standard Pictures dir). minSdk = 29, so this branch is always
        // taken; no legacy fallback needed.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, MEDIA_RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            // Bestowed-then-orphaned MediaStore rows clutter the gallery
            // forever. Roll the row back if we couldn't actually write to
            // the stream.
            runCatching { resolver.delete(uri, null, null) }
            Timber.w(t, "ScreenshotCapture.saveToGallery write failed")
            return null
        }
    }

    private fun launchShareSheet(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Autogram screenshot v${BuildConfig.VERSION_NAME}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send,
            activity.getString(R.string.debug_screenshot_share_title)
        )
        activity.startActivity(chooser)
    }

    private val DATE_FMT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
}
