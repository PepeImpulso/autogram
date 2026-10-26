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
import androidx.media.utils.MediaConstants
import com.autogram.app.R
import com.autogram.app.ui.auto.CarRecordToggle
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    @Inject lateinit var quickReplyRepository: com.autogram.app.domain.repository.QuickReplyRepository
    @Inject lateinit var settingsRepository: com.autogram.app.domain.repository.SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var browseActionsSupported = false

    override fun onCreate() {
        super.onCreate()
        sessionToken = mediaPlayback.sessionToken()
        // Redraw the record grid (stop badge, subtitles, buttons) whenever
        // the recording state changes, from the car or from the phone.
        serviceScope.launch {
            merge(
                carMediaRecord.tileChanges.drop(1),
                messageRepository.observePendingReplyCounts().distinctUntilChanged().drop(1),
                quickReplyRepository.observeAll().distinctUntilChanged().drop(1)
            ).collect { notifyChildrenChanged(RECORD_ID) }
        }
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
            val limit = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT, 0) ?: 0
            // In-grid buttons left the car grid empty on a real head unit in
            // v1.28.0, so they are opt-in (Settings → Android Auto). Read on
            // every connection: toggling applies after Android Auto reconnects.
            browseActionsSupported = limit > 0 && carGridButtonsEnabled()
            BrowserRoot(ROOT_ID, if (browseActionsSupported) browseActionsRootExtras() else null)
        } else {
            null
        }
    }

    private fun carGridButtonsEnabled(): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(500) { settingsRepository.get().carGridButtons } ?: false
        }
    }.getOrDefault(false)

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
        val unread = withTimeoutOrNull(BROWSER_QUERY_TIMEOUT_MS) {
            runCatching { messageRepository.observePendingReplyCounts().first() }.getOrDefault(emptyMap())
        } ?: emptyMap()
        // Same contacts and order as the phone's Home: the primary circle
        // first, then the secondaries that are not hidden from Home.
        val sorted = pinned.sortedBy { it.sortOrder }
        val home = sorted.filter { it.isActive } + sorted.filter { !it.isActive && it.showOnHome }
        return home.map { conv ->
            val title = conv.topicName?.takeIf { it.isNotBlank() }
                ?: conv.targetTitle?.takeIf { it.isNotBlank() }
                ?: "Autogram"
            val state = carMediaRecord.tileState(conv.id)
            val subtitle = when (state) {
                CarRecordToggle.TileState.IDLE -> getString(R.string.media_record_tap_to_record)
                CarRecordToggle.TileState.RECORDING -> getString(
                    if (browseActionsSupported) R.string.media_record_tile_recording
                    else R.string.media_record_recording_subtitle
                )
                CarRecordToggle.TileState.SENDING -> getString(R.string.media_record_sending, title)
                CarRecordToggle.TileState.SENT -> getString(R.string.media_record_sent, title)
                CarRecordToggle.TileState.FAILED -> getString(R.string.media_record_failed, title)
            }
            val extras = Bundle()
            if (browseActionsSupported) {
                // In-grid button: record when idle, send (+ cancel) while
                // recording. Tapping it keeps the driver on this grid.
                val actions = if (state == CarRecordToggle.TileState.RECORDING) {
                    arrayListOf(BROWSE_ACTION_SEND, BROWSE_ACTION_CANCEL)
                } else {
                    arrayListOf(BROWSE_ACTION_RECORD)
                }
                extras.putStringArrayList(MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST, actions)
            }
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(CarMediaRecordController.recordMediaId(conv.id))
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconBitmap(
                    circlePhoto(
                        conv.photoLocalPath,
                        recording = state == CarRecordToggle.TileState.RECORDING,
                        unread = unread[conv.id] ?: 0
                    )
                )
                .setExtras(extras)
                .build()
            MediaItem(desc, MediaItem.FLAG_PLAYABLE)
        } + runCatching { shortcutChildren() }
            .onFailure { Timber.w(it, "shortcutChildren failed") }
            .getOrDefault(emptyList())
    }

    /** The phone's shortcut chips (Correo, Tiempo, ...) as tiles after the contacts. */
    private suspend fun shortcutChildren(): List<MediaItem> {
        val show = runCatching { settingsRepository.get().showQuickReplyShortcuts }.getOrDefault(true)
        if (!show) return emptyList()
        val shortcuts = withTimeoutOrNull(BROWSER_QUERY_TIMEOUT_MS) {
            runCatching { quickReplyRepository.getAll() }.getOrDefault(emptyList())
        } ?: emptyList()
        return shortcuts.sortedBy { it.position }.map { qr ->
            val voice = qr.kind == com.autogram.app.domain.model.QuickReplyKind.VOICE
            val extras = Bundle()
            if (browseActionsSupported) {
                extras.putStringArrayList(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST,
                    arrayListOf(BROWSE_ACTION_SEND)
                )
            }
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(CarMediaRecordController.shortcutMediaId(qr.id))
                .setTitle(qr.label)
                .setSubtitle(getString(R.string.media_shortcut_subtitle))
                .setIconBitmap(shortcutIcon(if (voice) R.drawable.ic_mic else R.drawable.ic_bolt))
                .setExtras(extras)
                .build()
            MediaItem(desc, MediaItem.FLAG_PLAYABLE)
        }
    }

    /** Dark-brown circle with the gold shortcut glyph, like the phone's chips. */
    private fun shortcutIcon(drawableRes: Int): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(RECORD_ICON_PX, RECORD_ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val c = RECORD_ICON_PX / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E2A1E.toInt() })
        val glyph = androidx.core.content.ContextCompat.getDrawable(this, drawableRes)?.mutate()
            ?: return null
        glyph.setTint(0xFFE0B04A.toInt())
        val inset = (RECORD_ICON_PX * 0.28f).toInt()
        glyph.setBounds(inset, inset, RECORD_ICON_PX - inset, RECORD_ICON_PX - inset)
        glyph.draw(canvas)
        out
    }.onFailure { Timber.w(it, "shortcutIcon failed") }.getOrNull()

    /**
     * In-grid record / send / cancel buttons (Android Auto "custom browse
     * actions"). Unlike tapping the tile, which makes Android Auto open the
     * player, these run without leaving the grid; the tile is refreshed to
     * show the new state.
     */
    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {
        val mediaId = extras?.getString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID)
        val shortcutId = mediaId?.let { CarMediaRecordController.shortcutIdOf(it) }
        if (shortcutId != null && action == BROWSE_ACTION_SEND) {
            carMediaRecord.sendShortcut(shortcutId)
            result.sendResult(Bundle().apply {
                putString(
                    MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE,
                    getString(R.string.media_shortcut_sent_toast)
                )
            })
            return
        }
        val targetId = mediaId?.let { CarMediaRecordController.recordTargetOf(it) }
        if (targetId == null) {
            result.sendError(null)
            return
        }
        when (action) {
            BROWSE_ACTION_RECORD, BROWSE_ACTION_SEND -> carMediaRecord.toggle(targetId)
            BROWSE_ACTION_CANCEL -> carMediaRecord.cancel()
            else -> {
                result.sendError(null)
                return
            }
        }
        result.sendResult(Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM, mediaId)
        })
    }

    private fun browseActionsRootExtras(): Bundle = Bundle().apply {
        putParcelableArrayList(
            MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST,
            arrayListOf(
                browseAction(BROWSE_ACTION_RECORD, R.string.media_browser_record, R.drawable.ic_mic),
                browseAction(BROWSE_ACTION_SEND, R.string.media_record_action_send, R.drawable.ic_send),
                browseAction(BROWSE_ACTION_CANCEL, R.string.media_record_action_cancel, R.drawable.ic_cancel)
            )
        )
    }

    private fun browseAction(id: String, labelRes: Int, iconRes: Int): Bundle = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID, id)
        putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_LABEL, getString(labelRes))
        putString(
            MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ICON_URI,
            android.net.Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(packageName)
                .appendPath(resources.getResourceTypeName(iconRes))
                .appendPath(resources.getResourceEntryName(iconRes))
                .build()
                .toString()
        )
    }

    /**
     * Small circular contact photo for the record grid. Kept at
     * [RECORD_ICON_PX] because every bitmap travels over binder with the
     * children list; null falls back to the host's default tile.
     */
    private fun circlePhoto(path: String?, recording: Boolean, unread: Int): Bitmap? = runCatching {
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
        if (recording) drawStopBadge(canvas)
        if (unread > 0) drawUnreadBadge(canvas, unread)
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

    /** Pink unread counter in the top-right corner, like the phone's circles. */
    private fun drawUnreadBadge(canvas: Canvas, count: Int) {
        val r = RECORD_ICON_PX * 0.17f
        val cx = RECORD_ICON_PX - r - 2f
        val cy = r + 2f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4A3A3.toInt() })
        val text = if (count > 99) "99+" else count.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3A1010.toInt()
            textAlign = Paint.Align.CENTER
            textSize = r * (if (text.length > 2) 0.8f else 1.05f)
            isFakeBoldText = true
        }
        canvas.drawText(text, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }

    /** Red stop badge, like the phone's recording circle. */
    private fun drawStopBadge(canvas: Canvas) {
        val r = RECORD_ICON_PX * 0.22f
        val cx = RECORD_ICON_PX - r - 2f
        val cy = RECORD_ICON_PX - r - 2f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() })
        val half = r * 0.42f
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, Paint().apply { color = 0xFFFFFFFF.toInt() })
    }

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
        private const val RECORD_ICON_PX = 128

        private const val BROWSE_ACTION_RECORD = "com.autogram.app.browse.RECORD"
        private const val BROWSE_ACTION_SEND = "com.autogram.app.browse.SEND"
        private const val BROWSE_ACTION_CANCEL = "com.autogram.app.browse.CANCEL"

        /** Cap browser DB reads so a stuck query doesn't hang AA's UI. */
        private const val BROWSER_QUERY_TIMEOUT_MS: Long = 5_000

        private val ROW_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
