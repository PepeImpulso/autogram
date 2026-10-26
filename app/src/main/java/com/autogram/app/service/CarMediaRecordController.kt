package com.autogram.app.service

import android.content.Context
import com.autogram.app.R
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.model.approvalPendingIn
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase.SendStatus
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.ui.auto.CarRecordToggle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tap-to-record from Android Auto's media screen.
 *
 * On real head units Autogram is reached through the media surface
 * (MediaBrowserService), not the Car App Library home, so the record
 * controls have to live there too. The browse tree exposes one playable
 * "record:<conversationId>" item per pinned contact:
 *
 *   - tap a contact            → start recording for it
 *   - tap it again / ⏸ / Send  → stop and send
 *   - Cancel custom action     → discard the recording
 *
 * While recording, the Now Playing screen shows "Recording · <contact>"
 * with a progress bar up to the recorder's hard limit. The session state
 * follows [SendVoiceMtprotoUseCase.status], so a recording started on the
 * phone also shows up in the car.
 */
@Singleton
class CarMediaRecordController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaPlayback: AutogramMediaPlaybackController,
    private val sendVoice: SendVoiceMtprotoUseCase,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val quickReplyRepository: QuickReplyRepository
) : AutogramMediaPlaybackController.RecordInterceptor {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Contact the car last started recording for; terminal states are labelled with it. */
    @Volatile private var lastTargetId: String? = null

    init {
        mediaPlayback.setRecordInterceptor(this)
        scope.launch {
            var previous: SendStatus = SendStatus.Idle
            combine(sendVoice.status, sendVoice.activeTargetId) { s, t -> s to t }
                .collect { (status, target) ->
                    if (target != null) lastTargetId = target
                    publish(previous, status)
                    previous = status
                }
        }
    }

    override fun onPlayFromMediaId(mediaId: String): Boolean {
        shortcutIdOf(mediaId)?.let { sendShortcut(it); return true }
        val targetId = recordTargetOf(mediaId) ?: return false
        toggle(targetId)
        return true
    }

    /**
     * Send the phone's shortcut [shortcutId] (text or pre-recorded voice),
     * like the car home's shortcut tiles: a targeted shortcut goes to its
     * contact, an untargeted one to the primary contact.
     */
    fun sendShortcut(shortcutId: String) {
        scope.launch {
            val qr = runCatching { quickReplyRepository.getAll() }.getOrDefault(emptyList())
                .firstOrNull { it.id == shortcutId } ?: return@launch
            val target = qr.targetConversationId
                ?: runCatching { conversationRepository.getActive()?.id }.getOrNull()
            if (approvalPending(target)) return@launch
            lastTargetId = target
            when (qr.kind) {
                QuickReplyKind.TEXT -> sendVoice.sendQuickReply(qr.textContent, qr.targetConversationId)
                QuickReplyKind.VOICE -> qr.audioFilePath?.takeIf { it.isNotBlank() }?.let { path ->
                    sendVoice.sendVoiceShortcut(
                        audioFilePath = path,
                        audioDurationMs = qr.audioDurationMs,
                        targetConversationId = qr.targetConversationId
                    )
                }
            }
        }
    }

    /**
     * Same guard as the car home: no reply of any kind while the contact's
     * newest message is an approval request (approve it in Telegram).
     */
    private suspend fun approvalPending(conversationId: String?): Boolean {
        val pending = runCatching { messageRepository.getAll().approvalPendingIn(conversationId) }
            .getOrDefault(false)
        if (pending) Timber.i("CarMediaRecord: reply blocked, approval request pending")
        return pending
    }

    /**
     * Phone-style toggle for [targetId]: start recording, or stop and send
     * when that contact is the one recording. Used by both a tap on the
     * tile and the tile's in-grid record / send button.
     */
    fun toggle(targetId: String) {
        when (CarRecordToggle.onTap(sendVoice.status.value, sendVoice.activeTargetId.value, targetId)) {
            CarRecordToggle.TapAction.START -> scope.launch {
                if (approvalPending(targetId)) return@launch
                lastTargetId = targetId
                sendVoice.startRecording(targetId)
                    .onFailure { Timber.w(it, "CarMediaRecord: startRecording failed") }
            }
            CarRecordToggle.TapAction.STOP_AND_SEND -> sendNow()
            CarRecordToggle.TapAction.IGNORE -> Timber.i("CarMediaRecord: tap ignored in ${sendVoice.status.value::class.simpleName}")
        }
    }

    fun cancel() {
        if (sendVoice.status.value == SendStatus.Recording) scope.launch { sendVoice.cancelRecording() }
    }

    /** What the record grid should draw on [conversationId]'s tile right now. */
    fun tileState(conversationId: String): CarRecordToggle.TileState =
        CarRecordToggle.tileState(
            sendVoice.status.value,
            sendVoice.activeTargetId.value,
            lastTargetId,
            conversationId
        )

    /** Emits whenever tile states may have changed, so the grid can refresh. */
    val tileChanges = combine(sendVoice.status, sendVoice.activeTargetId) { s, t -> s to t }

    override fun onTransport(): Boolean {
        if (sendVoice.status.value != SendStatus.Recording) return false
        sendNow()
        return true
    }

    override fun onCustomAction(action: String): Boolean {
        if (sendVoice.status.value != SendStatus.Recording) return action in OWN_ACTIONS
        when (action) {
            ACTION_SEND -> sendNow()
            ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    fun sendNow() {
        scope.launch {
            // No preview sheet in the car: send directly, like the car home does.
            sendVoice.stopAndSend(skipPreview = true)
                .onFailure { Timber.w(it, "CarMediaRecord: stopAndSend failed") }
        }
    }

    private suspend fun publish(previous: SendStatus, status: SendStatus) {
        val name = contactName(lastTargetId)
        when (status) {
            SendStatus.Recording -> mediaPlayback.markRecording(
                title = context.getString(R.string.media_record_recording_title, name),
                subtitle = context.getString(R.string.media_record_recording_subtitle),
                maxDurationMs = AudioRecorder.MAX_RECORDING_MS,
                sendLabel = context.getString(R.string.media_record_action_send),
                cancelLabel = context.getString(R.string.media_record_action_cancel)
            )
            SendStatus.Validating,
            SendStatus.Sending -> mediaPlayback.markRecordInfo(
                context.getString(R.string.media_record_sending, name)
            )
            // No "Sent" screen: clear the player so the car can go back to
            // the grid, where the contact tile shows "Sent".
            is SendStatus.Sent -> mediaPlayback.markRecordDone()
            SendStatus.QueuedOffline -> mediaPlayback.markRecordInfo(
                context.getString(R.string.media_record_queued, name)
            )
            is SendStatus.Error -> mediaPlayback.markRecordInfo(
                context.getString(R.string.media_record_failed, name)
            )
            // Only reset the session when leaving a recording state, so an
            // idle status at startup never clobbers a voice message that is
            // playing.
            SendStatus.Idle,
            is SendStatus.Discarded -> if (previous == SendStatus.Recording) mediaPlayback.markStopped()
            is SendStatus.PreviewReady,
            SendStatus.PreviewPlaying -> Unit
        }
    }

    private suspend fun contactName(id: String?): String {
        val conv = id?.let { runCatching { conversationRepository.getById(it) }.getOrNull() }
        return conv?.topicName?.takeIf { it.isNotBlank() }
            ?: conv?.targetTitle?.takeIf { it.isNotBlank() }
            ?: "Autogram"
    }

    companion object {
        const val RECORD_PREFIX = "record:"
        const val SHORTCUT_PREFIX = "shortcut:"
        const val ACTION_SEND = "com.autogram.app.action.CAR_SEND_RECORDING"
        const val ACTION_CANCEL = "com.autogram.app.action.CAR_CANCEL_RECORDING"
        private val OWN_ACTIONS = setOf(ACTION_SEND, ACTION_CANCEL)

        fun recordMediaId(conversationId: String) = RECORD_PREFIX + conversationId

        fun shortcutMediaId(shortcutId: String) = SHORTCUT_PREFIX + shortcutId

        internal fun shortcutIdOf(mediaId: String): String? =
            mediaId.takeIf { it.startsWith(SHORTCUT_PREFIX) }
                ?.removePrefix(SHORTCUT_PREFIX)
                ?.takeIf { it.isNotBlank() }

        internal fun recordTargetOf(mediaId: String): String? =
            mediaId.takeIf { it.startsWith(RECORD_PREFIX) }
                ?.removePrefix(RECORD_PREFIX)
                ?.takeIf { it.isNotBlank() }
    }
}
