package com.autogram.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.ui.phone.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.22.1 — singleton wrapping [MediaSessionCompat] so Autogram is
 * recognized as a "media app" by Android Auto / Bluetooth car
 * stereos / lock-screen media controls.
 *
 * Why this matters: pre-v1.22.1, AudioPlayer set the right
 * [android.media.AudioAttributes] (USAGE_MEDIA + CONTENT_TYPE_SPEECH)
 * but no MediaSession. That's enough for Bluetooth A2DP routing in
 * isolation, but on USB Android Auto (where the phone speaker is
 * muted by AA's audio policy) the audio had nowhere to go — AA only
 * surfaces playback through its media stream when the playing app
 * has a registered MediaSession AA can observe.
 *
 * This controller centralizes the session lifecycle:
 *   - PlayReplyUseCase calls [markPlaying] / [markPaused] /
 *     [markStopped] as the underlying [AudioPlayer] /
 *     [TtsPlayer] state changes.
 *   - The session token is published via [sessionToken] so
 *     AutogramMediaBrowserService can hand it to AA on connect.
 *   - AA's transport callbacks (Play / Pause / Stop / Skip) flow
 *     back through [Listener] which PlayReplyUseCase implements.
 */
@Singleton
class AutogramMediaPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Surface for AA's transport-control taps — implemented by
     * PlayReplyUseCase. Set via [setListener] once the use case is
     * constructed.
     */
    interface Listener {
        fun onPlay()
        fun onPause()
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onStop()
        fun onPlayFromMediaId(mediaId: String, extras: Bundle?)
    }

    @Volatile private var listener: Listener? = null

    private val session: MediaSessionCompat by lazy {
        MediaSessionCompat(context, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    listener?.onPlay() ?: Timber.w("MediaSession.onPlay before listener attached")
                }
                override fun onPause() {
                    listener?.onPause() ?: Timber.w("MediaSession.onPause before listener attached")
                }
                override fun onStop() {
                    listener?.onStop() ?: Timber.w("MediaSession.onStop before listener attached")
                }
                override fun onSkipToNext() {
                    listener?.onSkipToNext() ?: Timber.w("MediaSession.onSkipToNext before listener attached")
                }
                override fun onSkipToPrevious() {
                    listener?.onSkipToPrevious() ?: Timber.w("MediaSession.onSkipToPrevious before listener attached")
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == null) return
                    listener?.onPlayFromMediaId(mediaId, extras)
                        ?: Timber.w("MediaSession.onPlayFromMediaId before listener attached")
                }
            })
            // Empty initial state so AA / system controls show the
            // session as available even before the first playback.
            setPlaybackState(idleState())
            // v1.22.3 — wire the activity to launch when the user taps
            // the Now Playing area in AA / lock-screen media controls.
            // Without this, AA's Now Playing card may have no tap
            // affordance and the user can't deep-link from the car
            // back to Autogram on the phone.
            setSessionActivity(buildSessionActivityIntent())
            // v1.22.2 — activate the session immediately so Android Auto
            // and Bluetooth car stereos can discover it on connect, not
            // only after the first incoming-message playback. Without
            // this, an AA session connected during an idle period would
            // see the session as "not available" and the app may not be
            // listed in the car's media picker at all.
            isActive = true
        }
    }

    private fun buildSessionActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            SESSION_ACTIVITY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun setListener(l: Listener) { listener = l }

    /** Hand the session token to [AutogramMediaBrowserService] on its onCreate. */
    fun sessionToken(): MediaSessionCompat.Token = session.sessionToken

    /**
     * Activate the session and publish PLAYING state + metadata. Call
     * this from PlayReplyUseCase.playVoiceMessage / playManually /
     * playQueued / playSummaryOrTranscription right after the
     * underlying player has been told to start.
     */
    fun markPlaying(
        message: Message,
        conversation: Conversation?,
        durationMs: Long? = null,
        positionMs: Long = 0L
    ) {
        if (!session.isActive) session.isActive = true
        session.setMetadata(metadataFor(message, conversation, durationMs))
        session.setPlaybackState(playingState(positionMs))
    }

    /**
     * v1.22.3 — refresh just the metadata (title / subtitle / duration)
     * for the currently-playing message without touching the playback
     * state. PlayReplyUseCase calls this from a fire-and-forget
     * coroutine after the conversation lookup completes, so AA's
     * "now playing" name updates from the immediate fallback ("Autogram")
     * to the real contact title once Room hands the row back.
     * Decoupled from [markPlaying] so we don't reset playback position
     * to 0 every time we enrich metadata.
     */
    fun updateMetadata(message: Message, conversation: Conversation?, durationMs: Long? = null) {
        session.setMetadata(metadataFor(message, conversation, durationMs))
    }

    fun markPaused(positionMs: Long = 0L) {
        session.setPlaybackState(pausedState(positionMs))
    }

    /**
     * v1.22.3 — bring the session back to PLAYING after a [markPaused]
     * without rebuilding metadata. Cheaper + race-free vs going through
     * [markPlaying] again (which would re-do the conversation lookup +
     * reset position to 0). Used by `PlayReplyUseCase.resume()` so the
     * session state flips back the same instant the audio resumes,
     * not after a Room round-trip.
     */
    fun markResumed(positionMs: Long = 0L) {
        if (!session.isActive) session.isActive = true
        session.setPlaybackState(playingState(positionMs))
    }

    /** Playback fully stopped — keep the session active so AA still shows our app. */
    fun markStopped() {
        session.setPlaybackState(idleState())
    }

    /** Permanent teardown (e.g. process death). Releases the underlying session. */
    fun release() {
        session.isActive = false
        session.release()
    }

    private fun metadataFor(
        message: Message,
        conversation: Conversation?,
        durationMs: Long?
    ): MediaMetadataCompat {
        val title = conversation?.let {
            it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle?.takeIf { t -> t.isNotBlank() }
        } ?: "Autogram"
        val subtitle = when {
            !message.audioFilePath.isNullOrBlank() -> "Voice message"
            !message.textContent.isNullOrBlank() -> message.textContent.take(80)
            else -> "Message"
        }
        val effectiveDuration = durationMs
            ?: message.audioDurationMs
            ?: 0L
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, message.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Autogram")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, effectiveDuration)
            .build()
    }

    private fun playingState(positionMs: Long): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(TRANSPORT_ACTIONS)
            .setState(PlaybackStateCompat.STATE_PLAYING, positionMs, 1.0f)
            .build()

    private fun pausedState(positionMs: Long): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(TRANSPORT_ACTIONS)
            // Speed 0.0f freezes AA / system controls' progress bar at
            // `positionMs`. v1.22.x-pre passed 1.0f, which made AA keep
            // extrapolating position while paused — progress bar drifted
            // past the real pause point.
            .setState(PlaybackStateCompat.STATE_PAUSED, positionMs, 0.0f)
            .build()

    private fun idleState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(TRANSPORT_ACTIONS)
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1.0f)
            .build()

    companion object {
        private const val SESSION_TAG = "AutogramMediaSession"
        private const val SESSION_ACTIVITY_REQUEST_CODE = 0xA10
        private const val TRANSPORT_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
