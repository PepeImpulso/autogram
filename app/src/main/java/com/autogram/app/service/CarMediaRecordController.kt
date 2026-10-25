package com.autogram.app.service

import android.content.Context
import com.autogram.app.R
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase.SendStatus
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
 * with a 60 s progress bar (the recorder's hard limit). The session state
 * follows [SendVoiceMtprotoUseCase.status], so a recording started on the
 * phone also shows up in the car.
 */
@Singleton
class CarMediaRecordController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaPlayback: AutogramMediaPlaybackController,
    private val sendVoice: SendVoiceMtprotoUseCase,
    private val conversationRepository: ConversationRepository
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
        val targetId = recordTargetOf(mediaId) ?: return false
        when (CarRecordToggle.onTap(sendVoice.status.value, sendVoice.activeTargetId.value, targetId)) {
            CarRecordToggle.TapAction.START -> scope.launch {
                lastTargetId = targetId
                sendVoice.startRecording(targetId)
                    .onFailure { Timber.w(it, "CarMediaRecord: startRecording failed") }
            }
            CarRecordToggle.TapAction.STOP_AND_SEND -> sendNow()
            CarRecordToggle.TapAction.IGNORE -> Timber.i("CarMediaRecord: tap ignored in ${sendVoice.status.value::class.simpleName}")
        }
        return true
    }

    override fun onTransport(): Boolean {
        if (sendVoice.status.value != SendStatus.Recording) return false
        sendNow()
        return true
    }

    override fun onCustomAction(action: String): Boolean {
        if (sendVoice.status.value != SendStatus.Recording) return action in OWN_ACTIONS
        when (action) {
            ACTION_SEND -> sendNow()
            ACTION_CANCEL -> scope.launch { sendVoice.cancelRecording() }
            else -> return false
        }
        return true
    }

    private fun sendNow() {
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
                maxDurationMs = MAX_RECORDING_MS,
                sendLabel = context.getString(R.string.media_record_action_send),
                cancelLabel = context.getString(R.string.media_record_action_cancel)
            )
            SendStatus.Validating,
            SendStatus.Sending -> mediaPlayback.markRecordInfo(
                context.getString(R.string.media_record_sending, name)
            )
            is SendStatus.Sent -> mediaPlayback.markRecordInfo(
                context.getString(R.string.media_record_sent, name)
            )
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
        const val ACTION_SEND = "com.autogram.app.action.CAR_SEND_RECORDING"
        const val ACTION_CANCEL = "com.autogram.app.action.CAR_CANCEL_RECORDING"
        private val OWN_ACTIONS = setOf(ACTION_SEND, ACTION_CANCEL)

        /** AudioRecorder stops at 60 s; used for the Now Playing progress bar. */
        private const val MAX_RECORDING_MS = 60_000L

        fun recordMediaId(conversationId: String) = RECORD_PREFIX + conversationId

        internal fun recordTargetOf(mediaId: String): String? =
            mediaId.takeIf { it.startsWith(RECORD_PREFIX) }
                ?.removePrefix(RECORD_PREFIX)
                ?.takeIf { it.isNotBlank() }
    }
}
