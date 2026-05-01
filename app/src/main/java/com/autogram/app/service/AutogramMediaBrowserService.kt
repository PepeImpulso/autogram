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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    // v1.22.1 — injecting PlayReplyUseCase here is intentional, even
    // though the field isn't read directly. PlayReplyUseCase's @Inject
    // constructor registers itself as the MediaSessionCompat listener
    // in its `init {}` block. If it weren't constructed before AA
    // tapped a media item, `onPlayFromMediaId` would log "before
    // listener attached" and silently drop the play request. Hilt
    // singleton construction is lazy-on-first-injection — listing it
    // here forces the dependency before AA binds the browser.
    @Inject lateinit var playReplyUseCase: com.autogram.app.domain.usecase.PlayReplyUseCase

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        sessionToken = mediaPlayback.sessionToken()
        // v1.22.3 — explicitly read playReplyUseCase so R8's "remove unused
        // fields" optimization can't strip the @Inject lateinit var. The
        // injection itself triggers PlayReplyUseCase's init {} block which
        // registers the MediaSessionCompat listener; without that, AA
        // transport callbacks (Play / Pause / PlayFromMediaId) would log
        // "before listener attached" and silently drop. Hilt's generated
        // proguard rules already preserve @Inject fields, but reading the
        // hashCode here is bulletproof insurance.
        Timber.i(
            "AutogramMediaBrowserService onCreate — session attached, " +
                "playReplyUseCase=${playReplyUseCase.hashCode()}"
        )
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
        browseable(PENDING_ID, getString(com.autogram.app.R.string.media_browser_pending)),
        browseable(HISTORY_ID, getString(com.autogram.app.R.string.media_browser_history))
    )

    private suspend fun pendingChildren(): List<MediaItem> {
        // v1.22.3 — wrap in withTimeoutOrNull so a stuck DB query doesn't
        // freeze AA's UI. AA waits indefinitely on result.detach() until
        // sendResult fires, so a hung query == frozen browser. 5s is more
        // than enough for a normal Room read; on timeout we surface an
        // empty list and AA shows the folder as empty.
        val pending = withTimeoutOrNull(BROWSER_QUERY_TIMEOUT_MS) {
            runCatching { messageRepository.observePendingPlaybackQueue().first() }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val convos = conversationsById()
        return pending.map { msg -> playableItem(msg, convos[msg.conversationId]) }
    }

    private suspend fun historyChildren(): List<MediaItem> {
        val recent = withTimeoutOrNull(BROWSER_QUERY_TIMEOUT_MS) {
            runCatching {
                messageRepository.getAll()
                    .asSequence()
                    .filter {
                        it.direction == com.autogram.app.domain.model.MessageDirection.INCOMING &&
                            !it.audioFilePath.isNullOrBlank()
                    }
                    .sortedByDescending { it.createdAt }
                    .take(50)
                    .toList()
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val convos = conversationsById()
        return recent.map { msg -> playableItem(msg, convos[msg.conversationId]) }
    }

    private suspend fun conversationsById(): Map<String, Conversation> =
        // getAll() over getPinned() so messages from non-pinned (e.g.
        // archived) conversations also resolve to the right contact name
        // in AA's media tab instead of falling back to "Autogram".
        runCatching { conversationRepository.getAll().associateBy { it.id } }
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
        val kind = getString(
            if (voice) com.autogram.app.R.string.media_browser_kind_voice
            else com.autogram.app.R.string.media_browser_kind_text
        )
        return "$kind · $time"
    }

    companion object {
        const val ROOT_ID = "/"
        const val PENDING_ID = "pending"
        const val HISTORY_ID = "history"

        /** Cap browser DB reads so a stuck query doesn't hang AA's UI. */
        private const val BROWSER_QUERY_TIMEOUT_MS: Long = 5_000

        private val ROW_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
