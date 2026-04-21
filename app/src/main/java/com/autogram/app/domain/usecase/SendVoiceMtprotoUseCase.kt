package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.EmptyMessageFilter
import com.autogram.app.infrastructure.audio.RecordingSounds
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2b: voice (and text) messages sent via MTProto to whichever chat +
 * optional forum topic the user selected in the target picker.
 */
@Singleton
class SendVoiceMtprotoUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tdLibClient: TdLibClient,
    private val audioRecorder: AudioRecorder,
    private val emptyMessageFilter: EmptyMessageFilter,
    private val conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase,
    private val recordingSounds: RecordingSounds
) {

    sealed class SendStatus {
        object Idle : SendStatus()
        object Recording : SendStatus()
        object Validating : SendStatus()
        object Sending : SendStatus()
        data class Sent(val telegramMessageId: Long) : SendStatus()
        data class Error(val message: String) : SendStatus()
        data class Discarded(val reason: String) : SendStatus()
    }

    private val _status = MutableStateFlow<SendStatus>(SendStatus.Idle)
    val status: StateFlow<SendStatus> = _status.asStateFlow()

    // Id of the conversation that is currently recording/sending, so the UI can
    // highlight the right circle when several targets are pinned.
    private val _activeTargetId = MutableStateFlow<String?>(null)
    val activeTargetId: StateFlow<String?> = _activeTargetId.asStateFlow()

    @Volatile private var currentAudioFile: File? = null
    @Volatile private var currentTargetId: String? = null

    suspend fun startRecording(targetConversationId: String? = null): Result<Unit> {
        if (audioRecorder.isRecording()) {
            return Result.failure(IllegalStateException("Ya hay una grabación en curso"))
        }
        // Interrupt any voice reply currently playing so the driver can speak over
        // an unwanted/long-winded answer from Merlin and send their own prompt.
        // Also drops any queued pending playbacks.
        playReplyUseCase.stopAll()
        val audioDir = File(context.cacheDir, "audio_outgoing").apply { mkdirs() }
        val audioFile = File(audioDir, "${UUID.randomUUID()}.ogg")
        val result = audioRecorder.start(audioFile)
        return if (result.isSuccess) {
            currentAudioFile = audioFile
            currentTargetId = targetConversationId
            _activeTargetId.value = targetConversationId
            _status.value = SendStatus.Recording
            recordingSounds.playStart()
            Result.success(Unit)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Error al iniciar grabación"
            _status.value = SendStatus.Error(err)
            Result.failure(result.exceptionOrNull() ?: Exception(err))
        }
    }

    suspend fun cancelRecording() {
        val audioFile = audioRecorder.stop()
        audioFile?.delete()
        currentAudioFile = null
        currentTargetId = null
        _activeTargetId.value = null
        _status.value = SendStatus.Idle
        recordingSounds.playEnd()
    }

    suspend fun stopAndSend(): Result<Long> {
        val startedFile = currentAudioFile
            ?: return Result.failure(IllegalStateException("No hay grabación en curso"))
        val targetId = currentTargetId

        val audioFile = audioRecorder.stop()
        currentAudioFile = null
        recordingSounds.playEnd()
        if (audioFile == null || !audioFile.exists()) {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("No se pudo guardar el audio")
            return Result.failure(Exception("No se pudo guardar el audio"))
        }

        _status.value = SendStatus.Validating
        val filter = emptyMessageFilter.validate(audioFile, minDurationMs = 500)
        if (!filter.accepted) {
            audioFile.delete()
            _activeTargetId.value = null
            _status.value = SendStatus.Discarded(filter.reason ?: "Mensaje vacío")
            return Result.failure(Exception("Descartado: ${filter.reason}"))
        }

        if (tdLibClient.authState.value !is AuthState.Ready) {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("No autenticado con Telegram")
            return Result.failure(IllegalStateException("MTProto no autenticado"))
        }

        val target = resolveTarget(targetId)
        if (target == null) {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }

        _status.value = SendStatus.Sending
        return try {
            val chatId = target.targetChatId.toLong()
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.topicId = target.topicId?.let { TdApi.MessageTopicForum(it.toInt()) }
                    this.inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(audioFile.absolutePath)
                        duration = ((emptyMessageFilter.getDurationMs(audioFile) ?: 0L) / 1000).toInt()
                    }
                }
            )
            val out = handleSendResult(sendResult)
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "MTProto send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            Result.failure(e)
        } finally {
            currentTargetId = null
            _activeTargetId.value = null
        }
    }

    suspend fun sendQuickReply(text: String, targetConversationId: String? = null): Result<Long> {
        if (tdLibClient.authState.value !is AuthState.Ready) {
            _status.value = SendStatus.Error("No autenticado con Telegram")
            return Result.failure(IllegalStateException("MTProto no autenticado"))
        }
        val target = resolveTarget(targetConversationId)
        if (target == null) {
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }
        _activeTargetId.value = target.id
        _status.value = SendStatus.Sending
        return try {
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = target.targetChatId.toLong()
                    this.topicId = target.topicId?.let { TdApi.MessageTopicForum(it.toInt()) }
                    this.inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(text, emptyArray())
                    }
                }
            )
            val out = handleSendResult(sendResult)
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "MTProto text send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            Result.failure(e)
        } finally {
            _activeTargetId.value = null
        }
    }

    private suspend fun resolveTarget(id: String?) =
        if (id != null) conversationRepository.getById(id) else conversationRepository.getActive()

    fun reset() {
        _status.value = SendStatus.Idle
    }

    private fun handleSendResult(result: TdApi.Object): Result<Long> = when (result) {
        is TdApi.Message -> {
            _status.value = SendStatus.Sent(result.id)
            Result.success(result.id)
        }
        is TdApi.Error -> {
            val msg = "Telegram ${result.code}: ${result.message}"
            _status.value = SendStatus.Error(msg)
            Result.failure(Exception(msg))
        }
        else -> {
            _status.value = SendStatus.Error("Respuesta inesperada de TDLib")
            Result.failure(Exception("Unexpected TDLib response: ${result.javaClass.simpleName}"))
        }
    }
}
