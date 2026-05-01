package com.autogram.app.service

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
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
        }
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

    fun markPaused(positionMs: Long = 0L) {
        session.setPlaybackState(pausedState(positionMs))
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
            .setState(PlaybackStateCompat.STATE_PAUSED, positionMs, 1.0f)
            .build()

    private fun idleState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(TRANSPORT_ACTIONS)
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1.0f)
            .build()

    companion object {
        private const val SESSION_TAG = "AutogramMediaSession"
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
