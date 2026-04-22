package com.autogram.app.infrastructure.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.ui.phone.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an incoming-voice notification with MessagingStyle + CarAppExtender
 * so Android Auto can surface the message on the head unit and offer
 * Listen / Reply actions.
 *
 * The notification ID is the absolute-value hash of the conversation id, so
 * each circle keeps its own notification row (tap to open Autogram; Android
 * Auto stacks them by conversation title automatically).
 */
@Singleton
class IncomingMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_incoming_voice),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_incoming_voice_desc)
                setShowBadge(true)
            }
        )
    }

    /**
     * Fire a new-message notification for an incoming voice note the user
     * has already received. The actual audio is not attached — we point back
     * to Autogram where the playback queue handles it.
     */
    fun postVoiceReceived(conversation: Conversation, timestampMs: Long, messageId: String? = null) {
        postIncoming(
            conversation = conversation,
            timestampMs = timestampMs,
            bodyText = context.getString(R.string.notification_incoming_voice_body),
            messageId = messageId
        )
    }

    /**
     * Incoming *text* reply variant. Same MessagingStyle + CarAppExtender
     * wiring as voice, but shows the message preview (truncated) instead of
     * a generic "voice message" label. Android Auto reads the body aloud.
     */
    fun postTextReceived(conversation: Conversation, preview: String, timestampMs: Long, messageId: String? = null) {
        val trimmed = preview.take(120)
        postIncoming(
            conversation = conversation,
            timestampMs = timestampMs,
            bodyText = trimmed,
            messageId = messageId
        )
    }

    private fun postIncoming(conversation: Conversation, timestampMs: Long, bodyText: String, messageId: String?) {
        ensureChannel()

        val senderName = conversation.targetTitle ?: context.getString(R.string.main_default_primary_name)
        val sender = Person.Builder()
            .setKey(conversation.id)
            .setName(senderName)
            .setImportant(true)
            .build()

        val notificationId = notificationIdFor(conversation.id)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val messagingStyle = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.app_name)).build()
        )
            .setConversationTitle(buildString {
                append(senderName)
                conversation.topicName?.let { append(" › ").append(it) }
            })
            .addMessage(
                NotificationCompat.MessagingStyle.Message(bodyText, timestampMs, sender)
            )

        val listenAction = messageId?.let { buildListenAction(it, notificationId) }
        val replyAction = buildReplyAction(conversation.id, notificationId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setContentTitle(senderName)
            .setContentText(bodyText)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .apply {
                listenAction?.let { addAction(it) }
                addAction(replyAction)
            }
            // CarAppExtender is what makes Android Auto surface this notification
            // with "Listen" / "Reply" on the car screen. The extender duplicates
            // the actions so AA renders them as car-sized buttons instead of
            // falling back to the phone-style compact actions.
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(senderName)
                    .setContentText(bodyText)
                    .setContentIntent(openAppIntent)
                    .apply {
                        listenAction?.actionIntent?.let { pi ->
                            addAction(listenAction.icon, listenAction.title, pi)
                        }
                        replyAction.actionIntent?.let { pi ->
                            addAction(replyAction.icon, replyAction.title, pi)
                        }
                    }
                    .build()
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }.onFailure { Timber.w(it, "Failed to post MessagingStyle notification") }
    }

    private fun buildListenAction(messageId: String, notificationId: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LISTEN
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (messageId.hashCode() xor "listen".hashCode()),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_media_play),
            context.getString(R.string.notification_action_listen),
            pi
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
            .setShowsUserInterface(false)
            .build()
    }

    private fun buildReplyAction(conversationId: String, notificationId: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_action_reply_hint))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (conversationId.hashCode() xor "reply".hashCode()),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_menu_send),
            context.getString(R.string.notification_action_reply),
            pi
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    fun dismiss(conversationId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(conversationId))
    }

    private fun notificationIdFor(conversationId: String): Int =
        kotlin.math.abs(conversationId.hashCode()).coerceAtLeast(1)

    companion object {
        const val CHANNEL_ID = "autogram_incoming_voice"
    }
}
