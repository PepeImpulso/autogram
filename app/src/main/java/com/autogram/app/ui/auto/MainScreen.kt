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
import androidx.core.content.ContextCompat
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.model.VoiceCommand
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.platform.VoiceCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Android Auto home screen. Template-based (GridTemplate) because AA won't
 * let us render arbitrary Compose UI, but visually we try to match the
 * phone app as closely as the car library allows:
 *
 *   - Pinned conversations appear as tiles with their real photo cropped
 *     to a circle, in the same order the phone's home screen shows them.
 *     Tap a tile → start recording to that target.
 *   - Text quick-reply shortcuts render with the Bolt icon (matches the
 *     phone chip); voice quick-reply shortcuts render with the Mic icon
 *     (new in v1.17.0) and send the pre-recorded .ogg on tap.
 *   - Bottom ActionStrip keeps the Silent toggle + Voice-command CTA.
 *
 * Android Auto enforces a distraction-optimized tile limit (typically 6-8
 * depending on the head unit). When the user has more pinned circles +
 * shortcuts than the budget allows, pinned circles win — the car is a
 * conversation-first context, the shortcuts are phone-first.
 */
class MainScreen(
    carContext: CarContext,
    private val sendMessageUseCase: SendVoiceMtprotoUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val manageSettingsUseCase: ManageSettingsUseCase,
    private val quickReplyRepository: QuickReplyRepository,
    private val conversationRepository: ConversationRepository,
    private val voiceCommandRecognizer: VoiceCommandRecognizer
) : Screen(carContext) {

    private val screenJob = SupervisorJob()
    private val screenScope = CoroutineScope(Dispatchers.Main + screenJob)
    private var resetJob: kotlinx.coroutines.Job? = null

    private val silentMode = MutableStateFlow(false)
    private val cachedQuickReplies = MutableStateFlow<List<QuickReply>>(emptyList())
    private val cachedPinned = MutableStateFlow<List<Conversation>>(emptyList())

    // Unchanged from v1.17.x: icon cache only used by the fallback
    // GridTemplate on the non-home screens (recording / status).
    private val iconCache: MutableMap<String, CarIcon> = HashMap()

    // Custom canvas renderer for the home screen. Registered with AA's
    // AppManager on screen start so AA hands us the Surface when the
    // NavigationTemplate is shown. Unregistered on screen stop so other
    // screens (recording, status) don't accidentally draw over the
    // surface.
    private val surfaceRenderer = AutogramSurfaceRenderer(carContext, screenScope).apply {
        onPrimaryTap = { id -> startRecording(id) }
        onSecondaryTap = { id -> startRecording(id) }
        onShortcutTap = { qr -> sendShortcut(qr) }
    }

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                // Take ownership of the car surface whenever MainScreen
                // is the visible screen. Releasing on onStop keeps other
                // screens' templates (MessageTemplate for recording) from
                // rendering our canvas behind them.
                runCatching {
                    carContext.getCarService(androidx.car.app.AppManager::class.java)
                        .setSurfaceCallback(surfaceRenderer)
                }.onFailure { Timber.w(it, "AppManager.setSurfaceCallback failed") }
                pushState()
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                runCatching {
                    carContext.getCarService(androidx.car.app.AppManager::class.java)
                        .setSurfaceCallback(null)
                }
            }

            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                screenJob.cancel()
                iconCache.clear()
            }
        })
        screenScope.launch {
            sendMessageUseCase.status.collect { invalidate() }
        }
        screenScope.launch {
            manageSettingsUseCase.observe().collect {
                if (silentMode.value != it.silentMode) {
                    silentMode.value = it.silentMode
                    invalidate()
                }
            }
        }
        screenScope.launch {
            quickReplyRepository.observeAll().collect { list ->
                cachedQuickReplies.value = list
                pushState()
                invalidate()
            }
        }
        screenScope.launch {
            conversationRepository.observePinned().collect { list ->
                cachedPinned.value = list
                pushState()
                invalidate()
            }
        }
    }

    private fun pushState() {
        surfaceRenderer.updateState(cachedPinned.value, cachedQuickReplies.value)
    }

    override fun onGetTemplate(): Template {
        return when (val status = sendMessageUseCase.status.value) {
            is SendVoiceMtprotoUseCase.SendStatus.Recording -> buildRecordingTemplate()
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> buildStatusTemplate(carContext.getString(R.string.status_sending))
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> {
                resetJob?.cancel()
                resetJob = screenScope.launch {
                    delay(3000)
                    sendMessageUseCase.reset()
                    invalidate()
                }
                buildStatusTemplate(carContext.getString(R.string.status_sent))
            }
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> buildStatusTemplate(
                carContext.getString(R.string.status_discarded, status.reason)
            )
            is SendVoiceMtprotoUseCase.SendStatus.Error -> buildStatusTemplate(
                carContext.getString(R.string.status_error) + ": ${status.message}"
            )
            else -> buildHomeTemplate()
        }
    }

    /**
     * Home = NavigationTemplate so AA hands us a raw Surface to render on
     * (via AppManager.setSurfaceCallback → AutogramSurfaceRenderer). We
     * skip setNavigationInfo/setMapActionStrip so the template shows
     * nothing but our surface + the top ActionStrip.
     */
    private fun buildHomeTemplate(): Template {
        return androidx.car.app.navigation.model.NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip())
            .setBackgroundColor(CarColor.createCustom(0xFF121212.toInt(), 0xFF121212.toInt()))
            .build()
    }

    private fun buildMainGrid(): GridTemplate {
        val builder = GridTemplate.Builder()
            // Empty title + no header action so AA renders the grid
            // without the "Autogram" wordmark bar. Reclaims ~60dp of
            // vertical space for larger tiles. A single space is safer
            // than null across host versions.
            .setTitle(" ")
            .setActionStrip(buildActionStrip())

        // ITEM_SIZE_LARGE = 4 — biggest tile size AA supports. Uniform
        // across the whole grid (per-item size isn't allowed) so we
        // convey "primary" via ordering + bitmap content, not size.
        runCatching { builder.setItemSize(GridTemplate.ITEM_SIZE_LARGE) }

        val items = ItemList.Builder()

        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        var itemCount = 0

        val pinned = cachedPinned.value
        val primary = pinned.firstOrNull()
        val secondaries = pinned.drop(1)

        // 1. Primary tile first — bitmap fills the whole tile; AA's
        // native Badge API overlays a peach mic dot at the corner.
        if (primary != null && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(tileTitle(primary))
                    .setImage(
                        avatarTileIcon(primary, isPrimary = true),
                        GridItem.IMAGE_TYPE_LARGE,
                        micBadge()
                    )
                    .setOnClickListener { startRecording(primary.id) }
                    .build()
            )
            itemCount++
        }

        // Fresh install with no pinned circles: single Record tile so
        // the user isn't stuck on an empty screen.
        if (pinned.isEmpty() && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.aa_record))
                    .setImage(carIcon(R.drawable.ic_mic, CarColor.PRIMARY), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { startRecording(null) }
                    .build()
            )
            itemCount++
        }

        // 2. Quick-reply shortcuts next. In the car, one-tap shortcuts
        // are more urgent than the 7th pinned contact — a driver's
        // glance sees primary-target + canned replies before having
        // to scroll. Also matches the phone's "chips next to Merlin".
        for (qr in cachedQuickReplies.value) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(qr.label)
                    .setImage(shortcutTileIcon(qr), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { sendShortcut(qr) }
                    .build()
            )
            itemCount++
        }

        // 3. Secondary pinned conversations. Bitmap draws a smaller
        // circle inside the canvas so secondaries render visibly
        // smaller than the primary tile. Native Badge adds the same
        // peach mic dot for affordance consistency.
        for (conv in secondaries) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(tileTitle(conv))
                    .setImage(
                        avatarTileIcon(conv, isPrimary = false),
                        GridItem.IMAGE_TYPE_LARGE,
                        micBadge()
                    )
                    .setOnClickListener { startRecording(conv.id) }
                    .build()
            )
            itemCount++
        }

        // Pending-count tile at the very end when relevant.
        val pendingCount = playReplyUseCase.pendingCount()
        if (pendingCount > 0 && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.aa_pending_count, pendingCount))
                    .setImage(carIcon(R.drawable.ic_inbox, CarColor.YELLOW), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        screenScope.launch { playReplyUseCase.playAllPending() }
                        invalidate()
                    }
                    .build()
            )
        }

        builder.setSingleList(items.build())
        return builder.build()
    }

    /**
     * Tile title that matches the phone home: forum-topic name when the
     * conversation is a topic inside a group (otherwise every secondary
     * would just read as the group's name), falling back to the chat
     * title, then to a hard-coded "Merlin" default for the primary.
     */
    private fun tileTitle(conv: Conversation): String {
        return conv.topicName?.takeIf { it.isNotBlank() }
            ?: conv.targetTitle?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.main_default_primary_name)
    }

    private fun buildActionStrip(): ActionStrip {
        // AA rule: ActionStrip accepts at most ONE action with a custom
        // title; rest must be icon-only.
        val isSilent = silentMode.value
        val silentIcon = if (isSilent) R.drawable.ic_silent else R.drawable.ic_volume_up
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(silentIcon, if (isSilent) CarColor.YELLOW else CarColor.DEFAULT))
                    .setOnClickListener { toggleSilent() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_voice_command, CarColor.BLUE))
                    .setTitle(carContext.getString(R.string.aa_voice_command))
                    .setOnClickListener { triggerVoiceCommand() }
                    .build()
            )
            .build()
    }

    private fun triggerVoiceCommand() {
        if (!voiceCommandRecognizer.isAvailable()) {
            screenManager.push(VoiceCommandsHelpScreen(carContext))
            return
        }
        screenScope.launch {
            val command = voiceCommandRecognizer.listenOnce()
            dispatchVoiceCommand(command)
        }
    }

    private fun dispatchVoiceCommand(command: VoiceCommand) {
        Timber.d("VoiceCommand recibido: $command")
        when (command) {
            VoiceCommand.REPETIR -> screenScope.launch { playReplyUseCase.playAllPending() }
            VoiceCommand.CANCELAR -> screenScope.launch { sendMessageUseCase.cancelRecording(); invalidate() }
            VoiceCommand.PAUSAR -> { playReplyUseCase.pause(); invalidate() }
            VoiceCommand.REANUDAR -> { playReplyUseCase.resume(); invalidate() }
            VoiceCommand.ENVIAR -> stopAndSend()
            VoiceCommand.SILENCIO -> toggleSilent()
            VoiceCommand.UNKNOWN -> screenManager.push(VoiceCommandsHelpScreen(carContext))
        }
    }

    private fun buildRecordingTemplate(): MessageTemplate {
        return MessageTemplate.Builder(carContext.getString(R.string.status_recording))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(R.drawable.ic_mic, CarColor.RED))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_stop_send))
                    .setIcon(carIcon(R.drawable.ic_send, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { stopAndSend() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_cancel, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.SECONDARY)
                    .setOnClickListener {
                        screenScope.launch { sendMessageUseCase.cancelRecording() }
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun buildStatusTemplate(message: String): MessageTemplate {
        val (icon, color) = when (sendMessageUseCase.status.value) {
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> R.drawable.ic_send to CarColor.GREEN
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> R.drawable.ic_send to CarColor.BLUE
            is SendVoiceMtprotoUseCase.SendStatus.Error -> R.drawable.ic_cancel to CarColor.RED
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> R.drawable.ic_cancel to CarColor.YELLOW
            else -> R.drawable.ic_mic to CarColor.DEFAULT
        }
        return MessageTemplate.Builder(message)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(icon, color))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.common_back))
                    .setOnClickListener {
                        sendMessageUseCase.reset()
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun startRecording(targetId: String?) {
        resetJob?.cancel()
        screenScope.launch {
            val result = sendMessageUseCase.startRecording(targetId)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al iniciar grabación")
            }
            invalidate()
        }
    }

    private fun stopAndSend() {
        resetJob?.cancel()
        screenScope.launch {
            val result = sendMessageUseCase.stopAndSend()
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al enviar")
            }
            invalidate()
        }
    }

    private fun sendShortcut(qr: QuickReply) {
        screenScope.launch {
            when (qr.kind) {
                QuickReplyKind.TEXT -> sendMessageUseCase.sendQuickReply(qr.textContent)
                QuickReplyKind.VOICE -> {
                    val path = qr.audioFilePath
                    if (!path.isNullOrBlank()) {
                        sendMessageUseCase.sendVoiceShortcut(
                            audioFilePath = path,
                            audioDurationMs = qr.audioDurationMs,
                            targetConversationId = null
                        )
                    }
                }
            }
            invalidate()
        }
    }

    private fun toggleSilent() {
        screenScope.launch {
            manageSettingsUseCase.updateSilentMode(!silentMode.value)
        }
    }

    private fun carIcon(resId: Int, tint: CarColor): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId))
            .setTint(tint)
            .build()
    }

    /**
     * Native AA Badge for the mic affordance. Unlike the painted-in
     * badge we used before, this is rendered BY the car host on top of
     * the tile icon, so it stays crisp at any car screen resolution and
     * doesn't get crushed when AA downsamples our bitmap.
     */
    private fun micBadge(): androidx.car.app.model.Badge {
        // setBackgroundColor requires setHasDot(true) — Badge throws
        // IllegalStateException otherwise. "Dot" here means the filled
        // circle chrome around the icon; without the dot the background
        // colour has nothing to paint. With hasDot + backgroundColor +
        // icon we get exactly the phone's "peach disc with white mic
        // glyph" affordance, rendered by the car host natively.
        val peach = CarColor.createCustom(COLOR_PEACH_BADGE, COLOR_PEACH_BADGE)
        return androidx.car.app.model.Badge.Builder()
            .setHasDot(true)
            .setBackgroundColor(peach)
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic))
                    .setTint(CarColor.createCustom(android.graphics.Color.WHITE, android.graphics.Color.WHITE))
                    .build()
            )
            .build()
    }

    /**
     * Cached [CarIcon] for a conversation avatar tile. Composites at
     * [AVATAR_TILE_SIZE] px: circle-cropped photo (or silhouette fallback)
     * + gold ring + peach mic badge with a white mic glyph, matching the
     * phone's RecordCircle aesthetic.
     *
     * When [isPrimary] is true the drawn circle fills ~96% of the canvas
     * so Merlin looks visually larger than secondaries (which render at
     * ~76%). AA forces uniform tile container sizes so this is the only
     * way to convey "primary > secondary" at the bitmap level.
     */
    private fun avatarTileIcon(conv: Conversation, isPrimary: Boolean): CarIcon {
        val key = "avatar:${if (isPrimary) "P" else "S"}:${conv.id}:${conv.photoLocalPath ?: ""}:${photoMtime(conv.photoLocalPath)}"
        iconCache[key]?.let { return it }
        val bitmap = renderAvatarTile(conv, isPrimary = isPrimary)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        iconCache[key] = icon
        return icon
    }

    /**
     * Cached [CarIcon] for a quick-reply shortcut tile. Composites a
     * rounded-rectangle chip background + centered Bolt (text) or Mic
     * (voice) icon so the tile reads as a phone chip, visually distinct
     * from the circular avatars it shares the grid with.
     */
    private fun shortcutTileIcon(qr: QuickReply): CarIcon {
        val key = "shortcut:${qr.id}:${qr.kind.name}"
        iconCache[key]?.let { return it }
        val bitmap = renderShortcutTile(qr)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        iconCache[key] = icon
        return icon
    }

    private fun photoMtime(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return runCatching { File(path).lastModified() }.getOrDefault(0L)
    }

    /**
     * Avatar tile at [AVATAR_TILE_SIZE]px. Primary ([isPrimary]=true)
     * fills ~96% of the canvas; secondaries fill ~76% so Merlin reads
     * as visually bigger even though AA forces uniform tile containers.
     *
     * Output canvas is padded with transparent pixels where the circle
     * doesn't reach, so the AA tile shows blank space around smaller
     * secondaries — mimicking the phone's 176dp primary vs 88dp
     * secondary size difference.
     */
    private fun renderAvatarTile(conv: Conversation, isPrimary: Boolean): Bitmap {
        val sizePx = AVATAR_TILE_SIZE
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val center = sizePx / 2f

        // Anchor the canvas at near-zero alpha on all 4 corners. AA's
        // host appears to trim bitmaps to their opaque bounding box
        // before rendering, which was erasing the transparent padding
        // we use to make secondaries look smaller. A ~1.5%-alpha black
        // fill is functionally invisible against AA's dark tile bg but
        // forces the bounding box to include the full canvas, so the
        // size difference between primary and secondary actually
        // survives to the rendered tile.
        canvas.drawColor(0x04000000)

        // Fill ratios: primary fills the entire tile; secondaries
        // shrink to ~55% so the rendered tile shows a visible ≈2× size
        // difference between primary and secondary circles.
        val circleFill = if (isPrimary) 1.00f else 0.55f
        val circleRadius = (sizePx / 2f) * circleFill
        val ringStroke = circleRadius * (if (isPrimary) 0.10f else 0.09f)
        val photoRadius = circleRadius - ringStroke / 2f

        // ── Photo (or person-silhouette fallback) ────────────────────
        val photoSizePx = (photoRadius * 2).toInt()
        val photoBitmap = loadPhotoBitmap(conv, photoSizePx)
        val photoCircled = toCircleBitmap(photoBitmap, photoSizePx)
        canvas.drawBitmap(
            photoCircled,
            center - photoRadius,
            center - photoRadius,
            null
        )

        // ── Gold ring ────────────────────────────────────────────────
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringStroke
            color = COLOR_GOLD_RING
        }
        canvas.drawCircle(center, center, photoRadius + ringStroke / 2f, ringPaint)

        // Mic badge is no longer painted into the bitmap — AA renders
        // it natively via `Badge` on the GridItem. That gives crisper
        // results on high-DPI car screens and survives AA's bitmap
        // downsampling intact.

        return output
    }

    /**
     * Shortcut chip tile: rounded-rectangle dark-brown background with a
     * centered Bolt (text) or Mic (voice) icon tinted to match the phone
     * chip accent (gold for text, peach for voice). Sized to feel like
     * a secondary avatar tile (~76% of the canvas) so the user gets the
     * same visual weight whether they're looking at a contact or a
     * shortcut.
     */
    private fun renderShortcutTile(qr: QuickReply): Bitmap {
        val sizePx = AVATAR_TILE_SIZE
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Same alpha-anchor trick as the avatar tiles: forces AA's
        // renderer to honour the full canvas size rather than cropping
        // to opaque content. Without this the chip was being scaled
        // up to fill the whole tile, losing the intended padding.
        canvas.drawColor(0x04000000)

        // Chip fills nearly the whole canvas so the tile reads as a
        // substantial button, not a tiny mark floating in empty space.
        // Previous 0.76 fill was too timid — after AA's downsample the
        // chip barely registered against the dark tile background.
        val center = sizePx / 2f
        val chipSide = sizePx * 0.94f
        val chipLeft = center - chipSide / 2f
        val chipTop = center - chipSide / 2f
        val chipRect = RectF(chipLeft, chipTop, chipLeft + chipSide, chipTop + chipSide)
        val chipRadius = sizePx * 0.16f

        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_CHIP_BG
        }
        canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipPaint)

        val isVoice = qr.kind == QuickReplyKind.VOICE
        drawDrawableCentered(
            canvas = canvas,
            resId = if (isVoice) R.drawable.ic_mic else R.drawable.ic_bolt,
            tint = if (isVoice) COLOR_PEACH_BADGE else COLOR_GOLD_RING,
            cx = center,
            cy = center,
            sizePx = chipSide * 0.60f
        )

        return output
    }

    /**
     * Load the conversation's photo as a square bitmap of [sizePx],
     * falling back to the tinted person silhouette when no file is set
     * or the decode fails. Silhouette gets a muted dark background so
     * it reads as an intentional empty-state, not a missing-image error.
     */
    private fun loadPhotoBitmap(conv: Conversation, sizePx: Int): Bitmap {
        val path = conv.photoLocalPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (decoded != null) return decoded
            }
        }
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

    /**
     * Render a vector drawable into [canvas] centered at ([cx], [cy])
     * with the requested tint and pixel size. Uses a throwaway bitmap
     * because Drawable.draw + Canvas doesn't antialias vectors by default
     * on all API levels.
     */
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
        // Source bitmap size for every tile. The 2024 Seat Ateca's MIB3
        // screen at 9.2" is 1280×640 native → AA projects tiles around
        // 280-320px wide at ITEM_SIZE_LARGE. 768px gives ~2.5× oversample
        // for crisp downsampling (antialiased downscales look sharper
        // than a 1:1 match at AA's render-time filter settings).
        // Memory: 768×768×4B = 2.3MB per tile × ~10 tiles = ~23MB
        // cached, which is fine for a single-screen app.
        private const val AVATAR_TILE_SIZE: Int = 768

        // Match the phone theme (see ui/theme/Color.kt extended colors).
        // Gold ring around each avatar + on the Bolt shortcut icon.
        private const val COLOR_GOLD_RING: Int = 0xFFD4A44C.toInt()
        // Peach/coral mic badge + on the Mic shortcut icon.
        private const val COLOR_PEACH_BADGE: Int = 0xFFFFA66A.toInt()
        // Dark-brown shortcut chip background (tonalContainer dark).
        private const val COLOR_CHIP_BG: Int = 0xFF3D2F26.toInt()
        // Muted grey-blue behind the person-silhouette fallback so the
        // empty avatar doesn't read as a missing-image error.
        private const val COLOR_SILHOUETTE_BG: Int = 0xFF3E4A56.toInt()
    }
}
