package com.autogram.app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import timber.log.Timber
import java.io.File

/**
 * Circular contact artwork for the media session's Now Playing card
 * (issue #3). Falls back to a coloured circle with the contact's initial
 * so a contact without a photo is still recognisable at a glance.
 */
internal object ContactArtwork {

    fun build(photoPath: String?, name: String, sizePx: Int = DEFAULT_SIZE_PX): Bitmap? =
        photo(photoPath, sizePx) ?: initials(name, sizePx)

    private fun photo(path: String?, sizePx: Int): Bitmap? = runCatching {
        if (path.isNullOrBlank() || !File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val side = minOf(src.width, src.height)
        val srcRect = Rect(
            (src.width - side) / 2, (src.height - side) / 2,
            (src.width + side) / 2, (src.height + side) / 2
        )
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, Rect(0, 0, sizePx, sizePx), paint)
        src.recycle()
        out
    }.onFailure { Timber.w(it, "ContactArtwork photo decode failed") }.getOrNull()

    private fun initials(name: String, sizePx: Int): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bg)
        val letter = initialOf(name)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.45f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val baseline = sizePx / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, sizePx / 2f, baseline, text)
        out
    }.onFailure { Timber.w(it, "ContactArtwork initials failed") }.getOrNull()

    internal fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val cp = trimmed.codePointAt(0)
        return String(Character.toChars(cp)).uppercase()
    }

    private const val DEFAULT_SIZE_PX = 256
    private val PALETTE = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt()
    )
}
