package com.autogram.app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
import java.io.File
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
    @Inject lateinit var callerValidator: MediaBrowserCallerValidator
    // v1.22.1 — injecting PlayReplyUseCase here is intentional, even
    // though the field isn't read directly. PlayReplyUseCase's @Inject
    // constructor registers itself as the MediaSessionCompat listener
    // in its `init {}` block. If it weren't constructed before AA
    // tapped a media item, `onPlayFromMediaId` would log "before
    // listener attached" and silently drop the play request. Hilt
    // singleton construction is lazy-on-first-injection — listing it
    // here forces the dependency before AA binds the browser.
    @Inject lateinit var playReplyUseCase: com.autogram.app.domain.usecase.PlayReplyUseCase
    // Same eager-construction trick: the controller registers itself as the
    // session's record interceptor in its init block.
    @Inject lateinit var carMediaRecord: CarMediaRecordController

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
                "playReplyUseCase=${playReplyUseCase.hashCode()}, " +
                "carMediaRecord=${carMediaRecord.hashCode()}"
        )
    }

    /**
     * Gate the catalog behind a signed-package allowlist. Audit issue
     * #70: returning a non-null root for any caller let an arbitrary
     * installed app enumerate private message metadata and trigger
     * playback by guessing a media id. Untrusted callers now receive
     * `null`, which AndroidX MediaBrowserServiceCompat translates to
     * "this client cannot browse" — onLoadChildren is never reached.
     * onPlayFromMediaId from an untrusted controller is also rejected
     * because untrusted clients cannot acquire the MediaBrowser binder
     * to obtain the session token.
     *
     * Trust policy lives in [MediaBrowserCallerValidator]; debug builds
     * are permissive so DHU + local test apps keep working.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return if (callerValidator.isTrustedCaller(clientPackageName, clientUid)) {
            BrowserRoot(ROOT_ID, null)
        } else {
            null
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        when (parentId) {
            ROOT_ID -> result.sendResult(rootChildren())
            RECORD_ID -> {
                result.detach()
                serviceScope.launch {
                    runCatching { result.sendResult(recordChildren().toMutableList()) }
                        .onFailure { Timber.w(it, "onLoadChildren($RECORD_ID) failed") }
                }
            }
            HISTORY_ID -> {
                result.detach()
                serviceScope.launch {
                    runCatching { result.sendResult(historyChildren().toMutableList()) }
                        .onFailure { Timber.w(it, "onLoadChildren($HISTORY_ID) failed") }
                }
            }
            // v1.24.0 — PENDING folder removed from the Media tab. The
            // canonical pending-queue surface lives in the CarAppService
            // home (Pending tab on the new TabTemplate). The legacy ID is
            // still recognised here so a stale browser cache resolves to
            // the empty list instead of an error.
            PENDING_ID -> result.sendResult(mutableListOf())
            else -> result.sendResult(mutableListOf())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // v1.24.0 — Media tab only exposes History now. Pending duplicated the
    // CarAppService home's new Pending tab; users typing through the Media
    // tab were ending up in a browse-only surface with no reply path, which
    // is the whole point of the messaging template on the home screen.
    //
    // "Record" comes first: on real head units the media surface is the only
    // Autogram screen, so this is where the driver taps to record (see
    // CarMediaRecordController). Its children render as a grid of contacts.
    private fun rootChildren(): MutableList<MediaItem> = mutableListOf(
        browseable(
            RECORD_ID,
            getString(com.autogram.app.R.string.media_browser_record),
            Bundle().apply { putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID_ITEM) }
        ),
        browseable(HISTORY_ID, getString(com.autogram.app.R.string.media_browser_history))
    )

    private suspend fun recordChildren(): List<MediaItem> {
        val pinned = withTimeoutOrNull(BROWSER_QUERY_TIMEOUT_MS) {
            runCatching { conversationRepository.getPinned() }.getOrDefault(emptyList())
        } ?: emptyList()
        val subtitle = getString(com.autogram.app.R.string.media_record_tap_to_record)
        return pinned.sortedBy { it.sortOrder }.map { conv ->
            val title = conv.topicName?.takeIf { it.isNotBlank() }
                ?: conv.targetTitle?.takeIf { it.isNotBlank() }
                ?: "Autogram"
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(CarMediaRecordController.recordMediaId(conv.id))
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconBitmap(circlePhoto(conv.photoLocalPath))
                .build()
            MediaItem(desc, MediaItem.FLAG_PLAYABLE)
        }
    }

    /**
     * Small circular contact photo for the record grid. Kept at
     * [RECORD_ICON_PX] because every bitmap travels over binder with the
     * children list; null falls back to the host's default tile.
     */
    private fun circlePhoto(path: String?): Bitmap? = runCatching {
        if (path.isNullOrBlank() || !File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= RECORD_ICON_PX) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = Bitmap.createBitmap(RECORD_ICON_PX, RECORD_ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val side = minOf(src.width, src.height)
        val srcRect = Rect((src.width - side) / 2, (src.height - side) / 2,
            (src.width + side) / 2, (src.height + side) / 2)
        canvas.drawCircle(RECORD_ICON_PX / 2f, RECORD_ICON_PX / 2f, RECORD_ICON_PX / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, Rect(0, 0, RECORD_ICON_PX, RECORD_ICON_PX), paint)
        src.recycle()
        out
    }.onFailure { Timber.w(it, "circlePhoto failed") }.getOrNull()

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

    private fun browseable(mediaId: String, title: String, extras: Bundle? = null): MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setExtras(extras)
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
        const val RECORD_ID = "record"

        // androidx.media MediaConstants values, inlined: render the record
        // folder's children as a grid of contact tiles.
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_GRID_ITEM = 2
        private const val RECORD_ICON_PX = 160

        /** Cap browser DB reads so a stuck query doesn't hang AA's UI. */
        private const val BROWSER_QUERY_TIMEOUT_MS: Long = 5_000

        private val ROW_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
