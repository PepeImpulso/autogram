package com.autogram.app.ui.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.car.app.CarContext
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

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                screenJob.cancel()
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
                invalidate()
            }
        }
        screenScope.launch {
            conversationRepository.observePinned().collect { list ->
                cachedPinned.value = list
                invalidate()
            }
        }
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
            else -> buildMainGrid()
        }
    }

    private fun buildMainGrid(): GridTemplate {
        val builder = GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(buildActionStrip())

        val items = ItemList.Builder()

        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        var itemCount = 0

        // Pinned conversations first. The primary is always index 0 of the
        // observePinned() flow (sortOrder=0 + isPrimary), matching the
        // phone's home-screen ordering — a user glancing at the car screen
        // should see the same visual prominence as on the phone.
        val pinned = cachedPinned.value
        for (conv in pinned) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(conv.targetTitle ?: carContext.getString(R.string.main_default_primary_name))
                    .setImage(circleAvatarIcon(conv), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { startRecording(conv.id) }
                    .build()
            )
            itemCount++
        }

        // If nothing is pinned (fresh install / in-flight config), fall
        // back to a single Record tile so the user isn't stuck.
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

        // Quick-reply shortcuts — text (Bolt) and voice (Mic) mixed in the
        // order the user configured in Settings → Shortcuts. Voice ones
        // send the pre-recorded .ogg; text ones send the configured string.
        for (qr in cachedQuickReplies.value) {
            if (itemCount >= maxItems) break
            val isVoice = qr.kind == QuickReplyKind.VOICE
            items.addItem(
                GridItem.Builder()
                    .setTitle(qr.label)
                    .setImage(
                        carIcon(
                            if (isVoice) R.drawable.ic_mic else R.drawable.ic_bolt,
                            if (isVoice) CarColor.BLUE else CarColor.SECONDARY
                        ),
                        GridItem.IMAGE_TYPE_ICON
                    )
                    .setOnClickListener { sendShortcut(qr) }
                    .build()
            )
            itemCount++
        }

        // Pending-count tile at the end (only when there are unheard
        // incoming messages). Tap → play all pending.
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
     * Circle-cropped photo as a [CarIcon] for conversation tiles. Falls
     * back to a tinted person silhouette when the conversation has no
     * photo or the file is missing. Size matches AA's preferred tile
     * image dimensions (Google's docs recommend up to 200dp; we use
     * 192px pre-scaled).
     */
    private fun circleAvatarIcon(conv: Conversation, sizePx: Int = 192): CarIcon {
        val path = conv.photoLocalPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (bitmap != null) {
                    val circled = toCircleBitmap(bitmap, sizePx)
                    return CarIcon.Builder(IconCompat.createWithBitmap(circled)).build()
                }
            }
        }
        // Fallback: person silhouette in primary-container tint. No
        // per-conversation color variation — too distracting in the car.
        return carIcon(R.drawable.ic_person, CarColor.DEFAULT)
    }

    /**
     * Scale [src] to `sizePx × sizePx` and mask to a centered circle so
     * the AA tile shows a round avatar (Google's distraction-optimized
     * UI masks tile images as rectangles by default).
     */
    private fun toCircleBitmap(src: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        // Cover-style crop: scale the short side of the source to fill,
        // then center-crop to the square target.
        val srcShort = minOf(src.width, src.height)
        val srcLeft = (src.width - srcShort) / 2
        val srcTop = (src.height - srcShort) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + srcShort, srcTop + srcShort)
        val dstRect = Rect(0, 0, sizePx, sizePx)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }
}
