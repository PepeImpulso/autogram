package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.PendingReason
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.model.TranscriptionState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.AudioFocusDeniedException
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.persistence.dao.PendingPlaybackDao
import com.autogram.app.service.AutogramMediaPlaybackController
import com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * State-machine + gating tests for [PlayReplyUseCase]. The class is heavy in
 * dependencies but each branch is small — these tests pin the rules we
 * actually rely on (silent mode, in-call, autoplay-off, missing file,
 * concurrent playback) so a future refactor doesn't silently regress them.
 *
 * AudioPlayer.play and TtsPlayer.speak are stubbed to return success
 * immediately without invoking their completion callbacks; tests that need
 * the success-path callback path invoke the captured lambda manually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayReplyUseCaseTest {

    private lateinit var audioPlayer: AudioPlayer
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var pendingPlaybackDao: PendingPlaybackDao
    private lateinit var pendingReplyNotifier: PendingReplyNotifier
    private lateinit var mediaPlayback: AutogramMediaPlaybackController
    private lateinit var useCase: PlayReplyUseCase

    private lateinit var existingAudio: File
    private val defaultSettings = AppSettings()

    @Before fun setUp() {
        audioPlayer = mockk(relaxed = true)
        ttsPlayer = mockk(relaxed = true)
        audioRecorder = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        pendingPlaybackDao = mockk(relaxed = true)
        pendingReplyNotifier = mockk(relaxed = true)
        mediaPlayback = mockk(relaxed = true)

        every { audioPlayer.isPlaying() } returns false
        every { audioPlayer.play(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        every { audioRecorder.isRecording() } returns false
        coEvery { settingsRepository.get() } returns defaultSettings
        coEvery { pendingPlaybackDao.getAll() } returns emptyList()
        coEvery { messageRepository.getStaleNowPlaying() } returns emptyList()

        existingAudio = File.createTempFile("autogram-test-", ".ogg").apply { writeText("dummy") }

        useCase = PlayReplyUseCase(
            audioPlayer = audioPlayer,
            ttsPlayer = ttsPlayer,
            audioRecorder = audioRecorder,
            messageRepository = messageRepository,
            settingsRepository = settingsRepository,
            conversationRepository = conversationRepository,
            pendingPlaybackDao = pendingPlaybackDao,
            pendingReplyNotifier = pendingReplyNotifier,
            mediaPlayback = mediaPlayback
        )
    }

    @After fun tearDown() {
        existingAudio.delete()
    }

    private fun voiceMessage(id: String = "m1", path: String? = existingAudio.absolutePath) =
        Message(
            id = id,
            conversationId = "c1",
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            audioFilePath = path,
            audioDurationMs = 5000L
        )

    private fun textMessage(id: String = "t1", text: String? = "hello") =
        Message(
            id = id,
            conversationId = "c1",
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            textContent = text
        )

    // --- playVoiceMessage gates --------------------------------------------

    @Test fun `in call enqueues without playing`() = runTest {
        val msg = voiceMessage()
        useCase.playVoiceMessage(msg, isCallActive = true)
        // The message gets a state update to PENDING_PLAYBACK and the DAO
        // gets an insert for the pending row.
        coVerify { messageRepository.update(match { it.state == MessageState.PENDING_PLAYBACK }) }
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.IN_CALL }) }
        // And no playback was kicked off.
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `autoPlay off enqueues with SILENT_MODE reason`() = runTest {
        // v1.20.11: silentMode collapsed into autoPlayReplies — autoPlay=false
        // now means "queue for manual drain" (was "drop silently").
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        val msg = voiceMessage()
        useCase.playVoiceMessage(msg, isCallActive = false)
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.SILENT_MODE }) }
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `something else playing - new message goes to queue`() = runTest {
        every { audioPlayer.isPlaying() } returns true
        val msg = voiceMessage()
        useCase.playVoiceMessage(msg, isCallActive = false)
        // Status flips to PendingCount(1).
        val s = useCase.playbackStatus.value
        assertTrue(s is PlayReplyUseCase.PlaybackStatus.PendingCount)
        assertEquals(1, (s as PlayReplyUseCase.PlaybackStatus.PendingCount).count)
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `missing audio file - no play, no state churn`() = runTest {
        val msg = voiceMessage(path = "/nonexistent/missing.ogg")
        useCase.playVoiceMessage(msg, isCallActive = false)
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `null audio path - no play`() = runTest {
        val msg = voiceMessage(path = null)
        useCase.playVoiceMessage(msg, isCallActive = false)
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `playVoiceMessage with autoPlay off enqueues instead of dropping`() = runTest {
        // v1.20.11 regression guard: autoPlay=false used to drop messages
        // silently; now it must enqueue them so the "Play N pending"
        // surface in HeroHeader can drain them later.
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        useCase.playVoiceMessage(voiceMessage(), isCallActive = false)
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.SILENT_MODE }) }
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `happy path - audioPlayer play called, status flips to Playing`() = runTest {
        val msg = voiceMessage()
        useCase.playVoiceMessage(msg, isCallActive = false)
        verify { audioPlayer.play(any(), any(), any(), any(), any()) }
        coVerify { messageRepository.update(match { it.state == MessageState.PLAYING }) }
        val s = useCase.playbackStatus.value
        assertTrue(s is PlayReplyUseCase.PlaybackStatus.Playing)
        assertEquals(msg.id, (s as PlayReplyUseCase.PlaybackStatus.Playing).messageId)
    }

    @Test fun `voice playback uses configured playback speed`() = runTest {
        coEvery { settingsRepository.get() } returns defaultSettings.copy(playbackSpeed = 1.75f)

        useCase.playVoiceMessage(voiceMessage(), isCallActive = false)

        verify { audioPlayer.play(any(), 1.75f, any(), any(), any()) }
    }

    @Test fun `recording startup gate queues voice before recorder reports recording`() = runTest {
        every { audioRecorder.isRecording() } returns false
        val msg = voiceMessage()

        useCase.markUserRecordingStarting()
        useCase.playVoiceMessage(msg, isCallActive = false)

        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.USER_RECORDING }) }
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `recording startup gate queues text before recorder reports recording`() = runTest {
        every { audioRecorder.isRecording() } returns false
        val msg = textMessage()

        useCase.markUserRecordingStarting()
        useCase.playTextMessage(msg, isCallActive = false)

        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.USER_RECORDING }) }
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    @Test fun `recording drain waits two seconds after gate clears`() = runTest {
        useCase.markUserRecordingStarting()
        useCase.playVoiceMessage(voiceMessage("queued"), isCallActive = false)
        useCase.clearUserRecordingGate()

        val drainJob = launch { useCase.drainPendingIfAutoPlay() }
        runCurrent()
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }

        advanceTimeBy(1_999)
        runCurrent()
        verify(exactly = 0) { audioPlayer.play(any(), any(), any(), any(), any()) }

        advanceTimeBy(1)
        runCurrent()
        verify { audioPlayer.play(any(), any(), any(), any(), any()) }
        drainJob.cancel()
    }

    // --- playTextMessage gates --------------------------------------------

    @Test fun `playTextMessage with autoPlay off enqueues with SILENT_MODE reason`() = runTest {
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        useCase.playTextMessage(textMessage(), isCallActive = false)
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.SILENT_MODE }) }
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    @Test fun `playTextMessage respects in-call`() = runTest {
        useCase.playTextMessage(textMessage(), isCallActive = true)
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.IN_CALL }) }
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    @Test fun `playTextMessage with autoPlay off enqueues for manual drain`() = runTest {
        // v1.20.11: !autoPlay now queues text replies too (was: silent drop).
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        useCase.playTextMessage(textMessage(), isCallActive = false)
        coVerify { pendingPlaybackDao.insert(match { it.reason == PendingReason.SILENT_MODE }) }
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    @Test fun `playManually speaks text even when autoPlayReplies is off`() = runTest {
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        useCase.playManually(textMessage(text = "hola"))
        coVerify { ttsPlayer.speak("hola", any(), any(), any(), any()) }
    }

    @Test fun `playAllPending bypasses autoPlayReplies off`() = runTest {
        val msg = voiceMessage("pending")
        useCase.playVoiceMessage(msg, isCallActive = true)
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)

        useCase.playAllPending()

        verify { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `playTextMessage happy path calls speak`() = runTest {
        useCase.playTextMessage(textMessage(text = "hola"), isCallActive = false)
        coVerify { ttsPlayer.speak("hola", any(), any(), defaultSettings.playbackSpeed, any()) }
    }

    @Test fun `playTextMessage uses configured playback speed for TTS`() = runTest {
        coEvery { settingsRepository.get() } returns defaultSettings.copy(playbackSpeed = 1.75f)

        useCase.playTextMessage(textMessage(text = "hola"), isCallActive = false)

        coVerify { ttsPlayer.speak("hola", any(), any(), 1.75f, any()) }
    }

    @Test fun `playTextMessage skips blank text`() = runTest {
        useCase.playTextMessage(textMessage(text = ""), isCallActive = false)
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    // --- playSummaryOrTranscription preference order ----------------------

    @Test fun `playSummaryOrTranscription prefers summary over transcription`() = runTest {
        val msg = voiceMessage().copy(
            summary = "short summary",
            summaryState = SummaryState.DONE,
            transcription = "longer transcription text",
            transcriptionState = TranscriptionState.DONE
        )
        useCase.playSummaryOrTranscription(msg, isCallActive = false)
        coVerify { ttsPlayer.speak("short summary", any(), any(), defaultSettings.playbackSpeed, any()) }
    }

    @Test fun `playSummaryOrTranscription falls back to transcription when no summary`() = runTest {
        val msg = voiceMessage().copy(
            transcription = "transcription text",
            transcriptionState = TranscriptionState.DONE
        )
        useCase.playSummaryOrTranscription(msg, isCallActive = false)
        coVerify { ttsPlayer.speak("transcription text", any(), any(), defaultSettings.playbackSpeed, any()) }
    }

    @Test fun `playSummaryOrTranscription returns silently with no usable text`() = runTest {
        val msg = voiceMessage().copy(
            summary = null,
            transcription = null,
            textContent = null
        )
        useCase.playSummaryOrTranscription(msg, isCallActive = false)
        coVerify(exactly = 0) { ttsPlayer.speak(any(), any(), any(), any(), any()) }
    }

    // --- stopAll resets state ---------------------------------------------

    @Test fun `stopAll preserves pending queue (v1_20_11) and refreshes notifier`() = runTest {
        // Seed the queue first via the autoPlay-off path so we have something
        // that should survive the stop.
        coEvery { settingsRepository.get() } returns defaultSettings.copy(autoPlayReplies = false)
        useCase.playVoiceMessage(voiceMessage("a"), false)
        useCase.playVoiceMessage(voiceMessage("b"), false)
        assertEquals(2, useCase.pendingCount())

        useCase.stopAll()
        verify { audioPlayer.stop() }
        // Queue is data, not playback chrome — must NOT be wiped just because
        // the user pressed Stop / started a new recording.
        assertEquals(2, useCase.pendingCount())
        // Notifier stays alive with the surviving count instead of being
        // cancelled outright.
        verify { pendingReplyNotifier.notifyPending(2) }
        val status = useCase.playbackStatus.value
        assertTrue(status is PlayReplyUseCase.PlaybackStatus.PendingCount)
        assertEquals(2, (status as PlayReplyUseCase.PlaybackStatus.PendingCount).count)
    }

    @Test fun `stopAll on empty queue idles status and cancels notifier`() = runTest {
        useCase.stopAll()
        verify { audioPlayer.stop() }
        verify { pendingReplyNotifier.cancel() }
        assertEquals(PlayReplyUseCase.PlaybackStatus.Idle, useCase.playbackStatus.value)
    }

    // --- Pause / resume forward to underlying player ----------------------

    @Test fun `pause and resume forward to AudioPlayer`() = runTest {
        useCase.pause()
        verify { audioPlayer.pause() }
        useCase.resume()
        verify { audioPlayer.resume() }
    }

    // --- Persistence path on enqueue --------------------------------------

    @Test fun `enqueuePending writes a row with the source messageId`() = runTest {
        val captured = slot<PendingPlaybackEntity>()
        coEvery { pendingPlaybackDao.insert(capture(captured)) } returns Unit

        useCase.playVoiceMessage(voiceMessage("m1"), isCallActive = true)

        assertEquals("m1", captured.captured.messageId)
        assertEquals(PendingReason.IN_CALL, captured.captured.reason)
    }

    @Test fun `pending notifier is informed of the new count`() = runTest {
        useCase.playVoiceMessage(voiceMessage("m1"), isCallActive = true)
        verify { pendingReplyNotifier.notifyPending(1) }
        useCase.playVoiceMessage(voiceMessage("m2"), isCallActive = true)
        verify { pendingReplyNotifier.notifyPending(2) }
    }

    @Test fun `accepted TTS request keeps a rapid second text in the queue`() = runTest {
        every { ttsPlayer.isSpeaking() } returns false

        useCase.playTextMessage(textMessage("first", "first reply"))
        useCase.playTextMessage(textMessage("second", "second reply"))

        coVerify(exactly = 1) {
            ttsPlayer.speak("first reply", any(), any(), any(), any())
        }
        coVerify {
            pendingPlaybackDao.insert(match {
                it.messageId == "second" && it.reason == PendingReason.PLAYBACK_BUSY
            })
        }
    }

    @Test fun `audio focus denial returns voice to pending instead of marking failed`() = runTest {
        every {
            audioPlayer.play(any(), any(), any(), any(), any())
        } returns Result.failure(AudioFocusDeniedException("voice"))

        useCase.playVoiceMessage(voiceMessage("focus-denied"))

        coVerify {
            pendingPlaybackDao.insert(match {
                it.messageId == "focus-denied" &&
                    it.reason == PendingReason.PLAYBACK_INTERRUPTED
            })
        }
        coVerify(exactly = 0) {
            messageRepository.update(match {
                it.id == "focus-denied" && it.state == MessageState.FAILED
            })
        }
        assertEquals(1, useCase.pendingCount())
    }

    @Test fun `pending persistence is deleted only after audible completion`() = runTest {
        val completion = slot<(Result<Unit>) -> Unit>()
        every {
            audioPlayer.play(any(), any(), any(), any(), capture(completion))
        } returns Result.success(Unit)
        val message = voiceMessage("durable")
        useCase.playVoiceMessage(message, isCallActive = true)

        useCase.playAllPending()

        coVerify(exactly = 0) { pendingPlaybackDao.deleteByMessageId("durable") }
        completion.captured(Result.success(Unit))
        coVerify(timeout = 2_000) { pendingPlaybackDao.deleteByMessageId("durable") }
    }

    @Test fun `stale playing message is restored as pending not acknowledged`() = runTest {
        val stale = voiceMessage("interrupted").copy(state = MessageState.PLAYING)
        coEvery { messageRepository.getStaleNowPlaying() } returns listOf(stale)

        useCase.restorePendingQueue()

        coVerify {
            messageRepository.update(match {
                it.id == "interrupted" && it.state == MessageState.PENDING_PLAYBACK
            })
        }
        coVerify {
            pendingPlaybackDao.insert(match {
                it.messageId == "interrupted" &&
                    it.reason == PendingReason.PLAYBACK_INTERRUPTED
            })
        }
        coVerify(exactly = 0) {
            messageRepository.update(match {
                it.id == "interrupted" && it.state == MessageState.PLAYED
            })
        }
    }

    @Test fun `mark all acknowledges the current item and the remaining queue`() = runTest {
        val current = voiceMessage("current")
        val queued = voiceMessage("queued")
        coEvery { messageRepository.getById("current") } returns
            current.copy(state = MessageState.PLAYING)

        useCase.playVoiceMessage(current)
        useCase.playVoiceMessage(queued)
        useCase.markAllPendingAsListened()

        coVerify { pendingPlaybackDao.deleteByMessageId("current") }
        coVerify { pendingPlaybackDao.deleteByMessageId("queued") }
        coVerify {
            messageRepository.update(match {
                it.id == "current" && it.state == MessageState.PLAYED
            })
        }
        coVerify {
            messageRepository.update(match {
                it.id == "queued" && it.state == MessageState.PLAYED
            })
        }
        assertEquals(0, useCase.pendingCount())
    }

    @Test fun `late completion cannot clear a replacement playback`() = runTest {
        val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
        every {
            audioPlayer.play(any(), any(), any(), any(), any())
        } answers {
            callbacks += arg<(Result<Unit>) -> Unit>(4)
            Result.success(Unit)
        }
        val first = voiceMessage("first")
        val replacement = voiceMessage("replacement")
        coEvery { messageRepository.getById("first") } returns first

        useCase.playVoiceMessage(first)
        useCase.playManually(replacement)
        callbacks.first().invoke(Result.success(Unit))
        Thread.sleep(150)

        assertEquals(
            PlayReplyUseCase.PlaybackStatus.Playing("replacement"),
            useCase.playbackStatus.value
        )
        coVerify(exactly = 0) {
            messageRepository.update(match {
                it.id == "first" && it.state == MessageState.PLAYED
            })
        }
        verify(exactly = 2) { audioPlayer.play(any(), any(), any(), any(), any()) }
    }

    @Test fun `repeated play all starts only one queued item`() = runTest {
        useCase.playVoiceMessage(voiceMessage("first"), isCallActive = true)
        useCase.playVoiceMessage(voiceMessage("second"), isCallActive = true)

        useCase.playAllPending()
        useCase.playAllPending()

        verify(exactly = 1) { audioPlayer.play(any(), any(), any(), any(), any()) }
        assertEquals(1, useCase.pendingCount())
    }

    @Test fun `repeated stop does not cancel current item requeue`() = runTest {
        val current = voiceMessage("current")
        coEvery { messageRepository.getById("current") } returns current
        useCase.playVoiceMessage(current)

        useCase.stopAll()
        useCase.stopAll()

        coVerify(timeout = 2_000) {
            pendingPlaybackDao.insert(match { it.messageId == "current" })
        }
        assertEquals(1, useCase.pendingCount())
    }

    @Test fun `queued item without playable content fails and drain continues`() = runTest {
        val invalid = voiceMessage("invalid", path = null)
        useCase.playVoiceMessage(invalid, isCallActive = true)

        useCase.playAllPending()

        coVerify {
            messageRepository.update(match {
                it.id == "invalid" && it.state == MessageState.FAILED
            })
        }
        coVerify { pendingPlaybackDao.deleteByMessageId("invalid") }
        assertEquals(0, useCase.pendingCount())
    }
}
