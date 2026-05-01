package com.autogram.app.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * v1.22.1 / fix Android Auto audio routing.
 *
 * MediaBrowserServiceCompat that surfaces Autogram inside Android
 * Auto's "media apps" tab (alongside Spotify / Apple Music). The
 * browseable catalog has two folders:
 *
 *   /            — root, lists "Pending" and "History"
 *   /pending     — current pending playback queue, newest first
 *   /history     — recent incoming voice messages, newest first
 *
 * Tapping a [MediaItem] in the car routes through
 * [AutogramMediaPlaybackController]'s session callback to
 * `onPlayFromMediaId`, which PlayReplyUseCase resolves to the actual
 * `Message` and dispatches via `playFromHere` / `playManually`.
 *
 * This service ALSO publishes the same MediaSession token that
 * PlayReplyUseCase pushes state into, so AA observes our PLAYING
 * state and routes audio through the car's media stream — fixing
 * the "audio doesn't reach the car speakers" failure on USB AA + BT
 * simultaneous setups.
 */
@AndroidEntryPoint
class AutogramMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var mediaPlayback: AutogramMediaPlaybackController
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var conversationRepository: ConversationRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        sessionToken = mediaPlayback.sessionToken()
        Timber.i("AutogramMediaBrowserService onCreate — session attached")
    }

    /**
     * Allow any package to browse — Auto / Bluetooth car stereos /
     * Google Assistant connect with various signatures and there's
     * no sensitive data exposed (just public conversation titles +
     * voice metadata — same surface History already shows).
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        when (parentId) {
            ROOT_ID -> result.sendResult(rootChildren())
            PENDING_ID -> {
                result.detach()
                serviceScope.launch {
                    runCatching { result.sendResult(pendingChildren().toMutableList()) }
                        .onFailure { Timber.w(it, "onLoadChildren($PENDING_ID) failed") }
                }
            }
            HISTORY_ID -> {
                result.detach()
                serviceScope.launch {
                    runCatching { result.sendResult(historyChildren().toMutableList()) }
                        .onFailure { Timber.w(it, "onLoadChildren($HISTORY_ID) failed") }
                }
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun rootChildren(): MutableList<MediaItem> = mutableListOf(
        browseable(PENDING_ID, "Pending"),
        browseable(HISTORY_ID, "History")
    )

    private suspend fun pendingChildren(): List<MediaItem> {
        val pending = runCatching {
            // observePendingPlaybackQueue is a Flow — for a one-shot
            // load we just take the first emission via getAll +
            // filter by state, which the impl backs with the same
            // query.
            messageRepository.getAll().filter {
                it.state == com.autogram.app.domain.model.MessageState.PENDING_PLAYBACK
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
        val convos = conversationsById()
        return pending.map { msg -> playableItem(msg, convos[msg.conversationId]) }
    }

    private suspend fun historyChildren(): List<MediaItem> {
        val recent = runCatching {
            messageRepository.getAll()
                .filter {
                    it.direction == com.autogram.app.domain.model.MessageDirection.INCOMING &&
                        !it.audioFilePath.isNullOrBlank()
                }
                .sortedByDescending { it.createdAt }
                .take(50)
        }.getOrDefault(emptyList())
        val convos = conversationsById()
        return recent.map { msg -> playableItem(msg, convos[msg.conversationId]) }
    }

    private suspend fun conversationsById(): Map<String, Conversation> =
        runCatching { conversationRepository.getPinned().associateBy { it.id } }
            .getOrDefault(emptyMap())

    private fun browseable(mediaId: String, title: String): MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .build()
        return MediaItem(desc, MediaItem.FLAG_BROWSABLE)
    }

    private fun playableItem(message: Message, conversation: Conversation?): MediaItem {
        val title = conversation?.let {
            it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle?.takeIf { t -> t.isNotBlank() }
        } ?: "Autogram"
        val subtitle = subtitleFor(message)
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(message.id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaItem(desc, MediaItem.FLAG_PLAYABLE)
    }

    private fun subtitleFor(message: Message): String {
        val time = ROW_TIME_FORMAT.format(Date(message.createdAt))
        val voice = !message.audioFilePath.isNullOrBlank()
        return if (voice) "Voice · $time" else "Text · $time"
    }

    companion object {
        const val ROOT_ID = "/"
        const val PENDING_ID = "pending"
        const val HISTORY_ID = "history"

        private val ROW_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
