package com.autogram.app.infrastructure.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles the "Listen" and "Reply" actions exposed on the incoming-message
 * notifications ([IncomingMessageNotifier]).
 *
 * - ACTION_LISTEN: starts manual playback of the stored message. Works for
 *   voice (MediaPlayer) and text (TTS) alike — the use case dispatches by
 *   content type.
 * - ACTION_REPLY: reads the RemoteInput text and sends it via the existing
 *   quick-reply path. Dismisses the notification on success so the user
 *   doesn't see stale suggestions.
 *
 * Broadcast receivers run on the main thread with only a ~10 s budget before
 * Android kills them. We use goAsync() so the coroutine has time to hit
 * Room/TDLib without stalling the system.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var sendVoiceMtprotoUseCase: SendVoiceMtprotoUseCase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_LISTEN -> handleListen(intent)
                    ACTION_REPLY -> handleReply(context, intent, notificationId)
                    else -> Timber.w("NotificationActionReceiver: unknown action $action")
                }
            } catch (t: Throwable) {
                Timber.e(t, "NotificationActionReceiver handler crashed ($action)")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleListen(intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val message = messageRepository.getById(messageId) ?: run {
            Timber.w("Listen: message $messageId not found")
            return
        }
        playReplyUseCase.playManually(message)
    }

    private suspend fun handleReply(context: Context, intent: Intent, notificationId: Int) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = remoteInput.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val result = sendVoiceMtprotoUseCase.sendQuickReply(text, conversationId)
        if (result.isSuccess) {
            // Dismiss the notification: the user has replied, leaving it up
            // would invite them to reply again to the same message.
            if (notificationId > 0) {
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
        } else {
            Timber.w(result.exceptionOrNull(), "Reply send failed for $conversationId")
        }
    }

    companion object {
        const val ACTION_LISTEN = "com.autogram.app.action.LISTEN"
        const val ACTION_REPLY = "com.autogram.app.action.REPLY"
        const val EXTRA_MESSAGE_ID = "com.autogram.app.extra.MESSAGE_ID"
        const val EXTRA_CONVERSATION_ID = "com.autogram.app.extra.CONVERSATION_ID"
        const val EXTRA_NOTIFICATION_ID = "com.autogram.app.extra.NOTIFICATION_ID"
        const val KEY_REPLY_TEXT = "key_reply_text"
    }
}
