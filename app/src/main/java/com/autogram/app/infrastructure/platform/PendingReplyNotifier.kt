package com.autogram.app.infrastructure.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autogram.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a system notification when an incoming voice/text reply is queued for
 * deferred playback (silent mode, in-call, audio-focus-denied) so the user is
 * aware they have unplayed messages even when not in Android Auto.
 */
@Singleton
class PendingReplyNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun notifyPending(pendingCount: Int) {
        if (pendingCount <= 0) {
            cancel()
            return
        }
        ensureChannel(context)

        val openHistoryIntent = Intent().apply {
            setClassName(context, "com.autogram.app.ui.phone.HistoryActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openHistoryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_pending_replies_title)
        val body = context.resources.getQuantityString(
            R.plurals.notification_pending_replies_body,
            pendingCount,
            pendingCount
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        // POST_NOTIFICATIONS is declared in manifest; on API<33 the permission is implicit.
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // User denied POST_NOTIFICATIONS at runtime; silently skip.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "pending_replies"
        const val NOTIFICATION_ID = 4202

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_pending_replies),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_pending_replies_desc)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
