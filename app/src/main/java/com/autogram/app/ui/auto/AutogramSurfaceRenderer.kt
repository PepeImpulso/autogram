package com.autogram.app.ui.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Custom canvas renderer for the Autogram Android Auto home screen.
 *
 * Why not GridTemplate? Every version from v1.17.1 → v1.17.8 ran into
 * AA's uniform-tile constraint: you can't make one tile bigger than
 * another, you can't arrange tiles in non-grid layouts, and you can't
 * render chips next to circles the way the phone does. GridTemplate is
 * a supermarket shelf — all products the same size.
 *
 * This renderer uses the same approach as Waze and Google Maps: the
 * app declares `androidx.car.app.category.NAVIGATION`, returns a
 * [androidx.car.app.navigation.model.NavigationTemplate], and
 * registers itself as a [SurfaceCallback] via `AppManager`. AA then
 * hands us a raw [android.view.Surface] onto which we draw the whole
 * UI ourselves, including hit-test regions for click dispatch.
 *
 * Layout matches the phone home (landscape-redistributed):
 *
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │ [ big Merlin circle ]    [⚡ Mejora          ]               │
 *   │ [  photo + gold ring ]   [⚡ Correo          ]               │
 *   │ [  + mic badge       ]   [🎤 Tiempo          ]               │
 *   │      Merlin              (stacked chips)                     │
 *   │                                                              │
 *   │  (CSO)  (CFO)  (CTO)  (CHRO)  (CIO)  (CMO)                   │
 *   │  secondaries row, smaller circles                            │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Click handling is pixel-level: [onClick] hit-tests the click point
 * against a list of [ClickRegion]s computed during the last draw and
 * dispatches the matched action.
 */
class AutogramSurfaceRenderer(
    private val carContext: CarContext,
    private val screenScope: CoroutineScope
) : SurfaceCallback {

    // Actions wired by MainScreen; invoked from the UI thread in response
    // to hit-tested clicks. Kept as lambdas so this renderer stays
    // independent of the use-case graph.
    var onPrimaryTap: (conversationId: String) -> Unit = {}
    var onSecondaryTap: (conversationId: String) -> Unit = {}
    var onShortcutTap: (quickReply: QuickReply) -> Unit = {}

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect = Rect()

    // State snapshots refreshed via `updateState` whenever MainScreen's
    // collected flows emit new data. Kept as @Volatile so reads from
    // the rendering path (invoked by AA's host thread) see the latest
    // value without needing a lock.
    @Volatile private var pinned: List<Conversation> = emptyList()
    @Volatile private var quickReplies: List<QuickReply> = emptyList()

    // Photo bitmap cache: loading from disk on every frame would burn
    // CPU and jank the scroll. Keyed by `(path, file.lastModified)` so
    // the cache auto-invalidates when the user sets a new photo.
    private val photoCache: MutableMap<String, Bitmap> = HashMap()

    // Hit-test regions computed during the last `drawFrame`. `onClick`
    // walks this list in reverse so chips on top of circles win.
    private data class ClickRegion(val rect: RectF, val action: () -> Unit)
    @Volatile private var regions: List<ClickRegion> = emptyList()

    fun updateState(pinned: List<Conversation>, quickReplies: List<QuickReply>) {
        this.pinned = pinned
        this.quickReplies = quickReplies
        requestRender()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Timber.i("AutogramSurfaceRenderer: surface available ${surfaceContainer.width}×${surfaceContainer.height}")
        this.surfaceContainer = surfaceContainer
        requestRender()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Stable area is the safe drawing zone once AA overlay is applied.
        // For simplicity we use visibleArea, which is looser; when a
        // navigation banner appears, stableArea shrinks. Future work.
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Timber.i("AutogramSurfaceRenderer: surface destroyed")
        this.surfaceContainer = null
    }

    override fun onClick(x: Float, y: Float) {
        // Reverse-walk so regions added last (drawn on top) hit-test first.
        regions.asReversed().firstOrNull { it.rect.contains(x, y) }?.action?.invoke()
    }

    private fun requestRender() {
        screenScope.launch(Dispatchers.Default) { drawIfReady() }
    }

    private fun drawIfReady() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return
        val width = container.width.coerceAtLeast(1)
        val height = container.height.coerceAtLeast(1)
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Timber.w(e, "lockCanvas failed")
            return
        } ?: return
        try {
            drawFrame(canvas, width, height)
        } catch (e: Throwable) {
            Timber.e(e, "drawFrame threw")
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    // ─── Drawing ────────────────────────────────────────────────────

    private fun drawFrame(canvas: Canvas, width: Int, height: Int) {
        // Clear to AA's dark tile background. ActionStrip (top-right)
        // is drawn by AA over our surface; we leave a small margin so
        // our circles don't collide with it.
        canvas.drawColor(COLOR_SURFACE_BG)

        val newRegions = mutableListOf<ClickRegion>()
        val w = width.toFloat()
        val h = height.toFloat()

        // Reserve top-right area for AA's ActionStrip.
        val topPadding = h * 0.14f
        val sidePadding = w * 0.03f
        val contentTop = topPadding
        val contentBottom = h - h * 0.04f

        val localPinned = pinned
        val primary = localPinned.firstOrNull()
        val secondaries = localPinned.drop(1)
        val localShortcuts = quickReplies

        // Primary Merlin circle — flush with top + left margin. User
        // asked to push it to the top-left corner so the secondaries
        // row can breathe at the bottom. Centre = (sidePadding + r +
        // tiny gutter, contentTop + r). Size unchanged — ~33% of width,
        // capped by available vertical room above the secondaries.
        val primaryRadius = (w * 0.15f).coerceAtMost(h * 0.25f)
        val primaryCx = sidePadding + primaryRadius + w * 0.015f
        val primaryCy = contentTop + primaryRadius

        if (primary != null) {
            drawAvatar(
                canvas = canvas,
                conv = primary,
                cx = primaryCx,
                cy = primaryCy,
                radius = primaryRadius,
                isPrimary = true
            )
            drawTextCentered(
                canvas = canvas,
                text = tileTitle(primary),
                cx = primaryCx,
                cy = primaryCy + primaryRadius + w * 0.025f,
                sizePx = w * 0.024f,
                color = Color.WHITE
            )
            val hitBox = RectF(
                primaryCx - primaryRadius,
                primaryCy - primaryRadius,
                primaryCx + primaryRadius,
                primaryCy + primaryRadius
            )
            newRegions += ClickRegion(hitBox) { onPrimaryTap(primary.id) }
        }

        // Shortcut chips — right of Merlin, vertical stack aligned to
        // Merlin's full top-to-bottom span so three chips slot neatly
        // next to the big circle without leaving dead space above.
        val chipsX = primaryCx + primaryRadius + w * 0.04f
        val chipsRight = w - sidePadding
        val chipAreaTop = primaryCy - primaryRadius
        val chipAreaBottom = primaryCy + primaryRadius
        val chipAreaHeight = chipAreaBottom - chipAreaTop
        val visibleShortcuts = localShortcuts.take(3)
        if (visibleShortcuts.isNotEmpty()) {
            val chipHeight = (chipAreaHeight / visibleShortcuts.size) * 0.78f
            val chipGap = (chipAreaHeight - chipHeight * visibleShortcuts.size) / (visibleShortcuts.size + 1)
            visibleShortcuts.forEachIndexed { index, qr ->
                val top = chipAreaTop + chipGap + (chipHeight + chipGap) * index
                val rect = RectF(chipsX, top, chipsRight, top + chipHeight)
                drawShortcutChip(canvas, qr, rect)
                newRegions += ClickRegion(rect) { onShortcutTap(qr) }
            }
        }

        // Secondary circles — bottom row. Radius is about 60% of the
        // primary so the hierarchy is obvious. We fit up to 6 per row;
        // further secondaries are currently clipped (car context favours
        // fewer tappable items while driving).
        val secondaryRadius = primaryRadius * 0.52f
        val secondaryRowY = contentBottom - secondaryRadius - h * 0.06f
        val maxSecondaries = 6
        val visibleSecondaries = secondaries.take(maxSecondaries)
        if (visibleSecondaries.isNotEmpty()) {
            val rowLeft = sidePadding + secondaryRadius
            val rowRight = w - sidePadding - secondaryRadius
            val slotCount = visibleSecondaries.size
            visibleSecondaries.forEachIndexed { index, conv ->
                val t = if (slotCount == 1) 0.5f else index / (slotCount - 1f)
                val cx = rowLeft + (rowRight - rowLeft) * t
                drawAvatar(
                    canvas = canvas,
                    conv = conv,
                    cx = cx,
                    cy = secondaryRowY,
                    radius = secondaryRadius,
                    isPrimary = false
                )
                drawTextCentered(
                    canvas = canvas,
                    text = tileTitle(conv),
                    cx = cx,
                    cy = secondaryRowY + secondaryRadius + w * 0.018f,
                    sizePx = w * 0.015f,
                    color = 0xFFE0E0E0.toInt()
                )
                val hitBox = RectF(
                    cx - secondaryRadius,
                    secondaryRowY - secondaryRadius,
                    cx + secondaryRadius,
                    secondaryRowY + secondaryRadius
                )
                newRegions += ClickRegion(hitBox) { onSecondaryTap(conv.id) }
            }
        }

        regions = newRegions
    }

    private fun drawAvatar(
        canvas: Canvas,
        conv: Conversation,
        cx: Float,
        cy: Float,
        radius: Float,
        isPrimary: Boolean
    ) {
        // Ring stroke: thinner than v1.18.x. User said the gold band
        // was too heavy; 4% on primary / 5% on secondaries reads as a
        // clear accent without framing the photo into an icon.
        val ringStroke = radius * (if (isPrimary) 0.04f else 0.05f)
        val photoRadius = radius - ringStroke

        // Photo layer — masked to a circle.
        val photoSize = (photoRadius * 2).toInt().coerceAtLeast(1)
        val photoBitmap = loadPhotoBitmap(conv, photoSize)
        val photoCircled = toCircleBitmap(photoBitmap, photoSize)
        canvas.drawBitmap(photoCircled, cx - photoRadius, cy - photoRadius, null)

        // Gold ring.
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringStroke
            color = COLOR_GOLD_RING
        }
        canvas.drawCircle(cx, cy, radius - ringStroke / 2f, ringPaint)

        // Peach mic badge bottom-right, anchored on the 45° edge of
        // the ring so it reads as "tap here to record" like on the phone.
        val badgeRadius = radius * (if (isPrimary) 0.32f else 0.30f)
        val angle = Math.toRadians(45.0)
        val badgeCx = cx + (radius - badgeRadius * 0.2f) * Math.cos(angle).toFloat()
        val badgeCy = cy + (radius - badgeRadius * 0.2f) * Math.sin(angle).toFloat()
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_PEACH_BADGE
        }
        val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.015f
            color = COLOR_SURFACE_BG
        }
        canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgePaint)
        canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeRingPaint)
        drawDrawableCentered(
            canvas = canvas,
            resId = R.drawable.ic_mic,
            tint = Color.WHITE,
            cx = badgeCx,
            cy = badgeCy,
            sizePx = badgeRadius * 1.25f
        )
    }

    private fun drawShortcutChip(canvas: Canvas, qr: QuickReply, rect: RectF) {
        val chipRadius = rect.height() * 0.5f
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_CHIP_BG
        }
        canvas.drawRoundRect(rect, chipRadius, chipRadius, chipPaint)

        val isVoice = qr.kind == QuickReplyKind.VOICE
        val iconSize = rect.height() * 0.55f
        val iconCx = rect.left + rect.height() * 0.6f
        val iconCy = rect.centerY()
        drawDrawableCentered(
            canvas = canvas,
            resId = if (isVoice) R.drawable.ic_mic else R.drawable.ic_bolt,
            tint = if (isVoice) COLOR_PEACH_BADGE else COLOR_GOLD_RING,
            cx = iconCx,
            cy = iconCy,
            sizePx = iconSize
        )

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = rect.height() * 0.42f
            textAlign = Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        }
        val textX = iconCx + iconSize * 0.8f
        val textY = rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
        val maxTextWidth = rect.right - textX - rect.height() * 0.3f
        val ellipsized = ellipsize(qr.label, labelPaint, maxTextWidth)
        canvas.drawText(ellipsized, textX, textY, labelPaint)
    }

    private fun drawTextCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        sizePx: Float,
        color: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
        return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private fun tileTitle(conv: Conversation): String {
        return conv.topicName?.takeIf { it.isNotBlank() }
            ?: conv.targetTitle?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.main_default_primary_name)
    }

    // ─── Bitmap helpers ─────────────────────────────────────────────

    private fun loadPhotoBitmap(conv: Conversation, sizePx: Int): Bitmap {
        val path = conv.photoLocalPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val key = "$path:${file.lastModified()}"
                photoCache[key]?.let { return it }
                val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (decoded != null) {
                    photoCache[key] = decoded
                    return decoded
                }
            }
        }
        // Fallback: muted silhouette tile.
        val fallback = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fallback)
        canvas.drawColor(COLOR_SILHOUETTE_BG)
        drawDrawableCentered(
            canvas = canvas,
            resId = R.drawable.ic_person,
            tint = Color.WHITE,
            cx = sizePx / 2f,
            cy = sizePx / 2f,
            sizePx = sizePx * 0.55f
        )
        return fallback
    }

    private fun toCircleBitmap(src: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val srcShort = minOf(src.width, src.height)
        val srcLeft = (src.width - srcShort) / 2
        val srcTop = (src.height - srcShort) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + srcShort, srcTop + srcShort)
        val dstRect = Rect(0, 0, sizePx, sizePx)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }

    private fun drawDrawableCentered(
        canvas: Canvas,
        resId: Int,
        tint: Int,
        cx: Float,
        cy: Float,
        sizePx: Float
    ) {
        val drawable = ContextCompat.getDrawable(carContext, resId)?.mutate() ?: return
        val intSize = sizePx.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(intSize, intSize, Bitmap.Config.ARGB_8888)
        val tmpCanvas = Canvas(bitmap)
        drawable.setTint(tint)
        drawable.setBounds(0, 0, intSize, intSize)
        drawable.draw(tmpCanvas)
        canvas.drawBitmap(bitmap, cx - intSize / 2f, cy - intSize / 2f, null)
    }

    companion object {
        // AA's dark tile background. Near-black with a whisper of warmth
        // so the gold ring reads against it without flattening to pure
        // #000 (which looks like a display glitch on some OLED cars).
        private const val COLOR_SURFACE_BG: Int = 0xFF121212.toInt()
        private const val COLOR_GOLD_RING: Int = 0xFFD4A44C.toInt()
        private const val COLOR_PEACH_BADGE: Int = 0xFFFFA66A.toInt()
        private const val COLOR_CHIP_BG: Int = 0xFF3D2F26.toInt()
        private const val COLOR_SILHOUETTE_BG: Int = 0xFF3E4A56.toInt()
    }
}
