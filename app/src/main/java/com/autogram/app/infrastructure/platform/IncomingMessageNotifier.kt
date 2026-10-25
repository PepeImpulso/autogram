package com.autogram.app.infrastructure.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an incoming-message notification with the reply and mark-as-read
 * actions required by Android Auto. This complements Autogram's richer
 * templated car surface.
 *
 * The notification ID is the absolute-value hash of the conversation id, so
 * each circle keeps its own notification row (tap to open Autogram; Android
 * Auto stacks them by conversation title automatically).
 */
@Singleton
class IncomingMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Source of truth for the channel's setShowBadge value. Updated from
    // MainActivity.onResume via [reconfigureBadgeFor]. Read by
    // [ensureChannel] so that any post path landing in the tiny window
    // between deleteNotificationChannel + recreate sees the desired
    // value instead of clobbering it back to the default `true`.
    @Volatile private var desiredShowBadge: Boolean = true

    /**
     * Create the notification channel if it doesn't already exist. Uses
     * [desiredShowBadge] as the badge flag — never the per-call default
     * — so a notification posted concurrently with [reconfigureBadgeFor]
     * can't restore the wrong badge state. The badge value can only be
     * changed via [reconfigureBadgeFor] (Android forbids mutating
     * `setShowBadge` on an existing channel).
     */
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
                setShowBadge(desiredShowBadge)
                // Autogram owns reply playback itself. A notification chime
                // posted milliseconds before TTS/MediaPlayer starts can take
                // audio focus from that playback, especially in Android Auto.
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Re-create the channel with the updated `setShowBadge` value.
     * Android NotificationManager doesn't let us mutate `setShowBadge`
     * on an existing channel — only delete + re-create. Called from
     * MainActivity.onResume so the channel state always tracks the
     * current `showPendingBadges` setting. Sets [desiredShowBadge]
     * first so any notification posting between delete and recreate
     * uses the new value.
     */
    fun reconfigureBadgeFor(showBadge: Boolean) {
        desiredShowBadge = showBadge
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.canShowBadge() == showBadge) return
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
        ensureChannel()
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
     * has already received. v1.24.1 — when [audioFilePath] is a path our
     * FileProvider can expose, attach the audio Uri to the MessagingStyle
     * Message so AA's notification reader can play the original voice
     * through the car speakers itself. Defence in depth: even if our
     * in-app `AudioPlayer` / `TtsPlayer` playback gets muted by AA's
     * audio policy on a particular host, AA's media-pipeline path still
     * works through the notification.
     */
    fun postVoiceReceived(
        conversation: Conversation,
        timestampMs: Long,
        messageId: String? = null,
        audioFilePath: String? = null
    ) {
        postIncoming(
            conversation = conversation,
            timestampMs = timestampMs,
            bodyText = context.getString(R.string.notification_incoming_voice_body),
            messageId = messageId,
            audioFilePath = audioFilePath
        )
    }

    /**
     * Incoming *text* reply variant. Same MessagingStyle + CarAppExtender
     * wiring as voice, but shows the message preview (truncated) instead of
     * a generic "voice message" label. Android Auto reads the body aloud.
     */
    fun postTextReceived(
        conversation: Conversation,
        preview: String,
        timestampMs: Long,
        messageId: String? = null,
        // Hermes approval request (#87): no Reply/dictation action, so a spoken
        // "yes" in Android Auto cannot be sent back and read as approval.
        isApprovalNotice: Boolean = false
    ) {
        val trimmed = preview.take(120)
        postIncoming(
            conversation = conversation,
            timestampMs = timestampMs,
            bodyText = trimmed,
            messageId = messageId,
            audioFilePath = null,
            allowReply = !isApprovalNotice
        )
    }

    private fun postIncoming(
        conversation: Conversation,
        timestampMs: Long,
        bodyText: String,
        messageId: String?,
        audioFilePath: String?,
        allowReply: Boolean = true
    ) {
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

        // v1.24.1 — when there's a voice file, expose it via our existing
        // FileProvider so AA can read the audio Uri and play the original
        // voice through the car speakers. Falls back to text-only if
        // FileProvider can't expose the path (file outside its scope).
        val audioUri: Uri? = audioFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        File(path)
                    )
                }.onFailure {
                    Timber.w(it, "FileProvider couldn't expose voice note (ext=${File(path).extension})")
                }.getOrNull()
            }

        val message = NotificationCompat.MessagingStyle.Message(bodyText, timestampMs, sender)
        if (audioUri != null) {
            message.setData("audio/ogg", audioUri)
        }
        val messagingStyle = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.app_name)).build()
        )
            .setConversationTitle(buildString {
                append(senderName)
                conversation.topicName?.let { append(" › ").append(it) }
            })
            .addMessage(message)

        val listenAction = messageId?.let { buildListenAction(it, notificationId) }
        val replyAction = if (allowReply) buildReplyAction(conversation.id, notificationId) else null
        val markReadAction = buildMarkReadAction(conversation.id, messageId, notificationId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setContentTitle(senderName)
            .setContentText(bodyText)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .apply {
                listenAction?.let { addAction(it) }
                replyAction?.let { addAction(it) }
                addInvisibleAction(markReadAction)
            }
            // Keep the legacy car extension for older Android Auto hosts.
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(senderName)
                    .setContentText(bodyText)
                    .setContentIntent(openAppIntent)
                    .apply {
                        listenAction?.actionIntent?.let { pi ->
                            addAction(listenAction.icon, listenAction.title, pi)
                        }
                        replyAction?.actionIntent?.let { pi ->
                            addAction(replyAction.icon, replyAction.title, pi)
                        }
                        markReadAction.actionIntent?.let { pi ->
                            addAction(markReadAction.icon, markReadAction.title, pi)
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

    private fun buildMarkReadAction(
        conversationId: String,
        messageId: String?,
        notificationId: Int
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_AS_READ
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            messageId?.let { putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, it) }
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (conversationId.hashCode() xor "mark-read".hashCode()),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_menu_view),
            context.getString(R.string.notification_action_mark_read),
            pi
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
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
