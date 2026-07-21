package com.autogram.app.infrastructure.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autogram.app.domain.model.Conversation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingMessageNotifierAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        notificationManager.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun incomingMessage_isAndroidAutoCompliant() {
        IncomingMessageNotifier(context).postTextReceived(
            conversation = Conversation(
                id = "notification-test",
                targetChatId = "10001",
                targetTitle = "Family"
            ),
            preview = "Dinner is ready at eight.",
            timestampMs = 1_700_000_000_000,
            messageId = "message-test"
        )

        val notification = notificationManager.activeNotifications
            .single { it.notification.channelId == IncomingMessageNotifier.CHANNEL_ID }
            .notification

        assertEquals(NotificationCompat.CATEGORY_MESSAGE, notification.category)
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        assertNotNull(style)
        assertEquals("Family", style?.conversationTitle?.toString())
        assertEquals(1, style?.messages?.size)

        val reply = notification.actions.single {
            it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        }
        assertFalse(
            reply.extras.getBoolean("android.support.action.showsUserInterface", true)
        )
        assertEquals(1, reply.remoteInputs?.size)

        val markRead = NotificationCompat.getInvisibleActions(notification).single {
            it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
        }
        assertFalse(markRead.getShowsUserInterface())
        assertTrue(markRead.remoteInputs.isNullOrEmpty())

        assertNotNull(markRead.actionIntent)
        markRead.actionIntent!!.send()
        SystemClock.sleep(500)
        assertTrue(
            notificationManager.activeNotifications.none {
                it.notification.channelId == IncomingMessageNotifier.CHANNEL_ID
            }
        )
    }
}
