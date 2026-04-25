package com.autogram.app.infrastructure.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.content.ContextCompat
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
 * Posts an incoming-voice notification with MessagingStyle + CarAppExtender.
 * The extender is kept as a best-effort path for hosts that consume it, but
 * Autogram's Android Auto descriptor intentionally declares only `template`
 * so the launcher opens our NAVIGATION surface instead of AA's empty
 * messaging inbox.
 *
 * The notification ID is the absolute-value hash of the conversation id, so
 * each circle keeps its own notification row (tap to open Autogram; Android
 * Auto stacks them by conversation title automatically).
 */
@Singleton
class IncomingMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Create / update the notification channel. The launcher-badge flag
     * (`setShowBadge`) is bound to the user's `showPendingBadges` setting:
     * v1.20.11 surfaced that this was previously hard-wired to true, so
     * users who disabled per-circle badges still got a launcher dot count
     * they couldn't make sense of. Now the channel is reconfigured every
     * time we ensure it; if the setting changes the channel needs to be
     * deleted-and-recreated (Android won't let us mutate setShowBadge
     * after creation), which we do via [reconfigureBadgeFor].
     */
    fun ensureChannel(showBadge: Boolean = true) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_incoming_voice),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_incoming_voice_desc)
                setShowBadge(showBadge)
            }
        )
    }

    /**
     * Re-create the channel with the updated `setShowBadge` value.
     * Android NotificationManager doesn't let us mutate `setShowBadge`
     * on an existing channel — only delete + re-create. Called from
     * MainActivity.onResume so the channel state always tracks the
     * current `showPendingBadges` setting.
     */
    fun reconfigureBadgeFor(showBadge: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.canShowBadge() == showBadge) return
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
        ensureChannel(showBadge = showBadge)
    }

    /**
     * Cancel every per-conversation incoming-message notification this
     * notifier has posted. Walks the system's active-notifications list
     * and dismisses anything on our [CHANNEL_ID]. Leaves notifications
     * on other channels (notably [PendingReplyNotifier.CHANNEL_ID])
     * untouched so the pending-playback nag stays visible until the
     * user actually drains the queue.
     *
     * Called from MainActivity.onResume — once the user is looking at
     * the app, the per-message badge has done its job.
     */
    fun cancelAllIncoming() {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val active = nm.activeNotifications ?: return
            val nmCompat = NotificationManagerCompat.from(context)
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID) {
                    nmCompat.cancel(sb.id)
                }
            }
        }.onFailure { Timber.w(it, "cancelAllIncoming failed") }
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
            // Best-effort car extension: do not add `uses name="notification"`
            // to automotive_app_desc.xml unless Autogram grows a separate real
            // templated-messaging surface; doing so makes some AA hosts launch
            // the built-in empty messaging inbox instead of our home screen.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
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
