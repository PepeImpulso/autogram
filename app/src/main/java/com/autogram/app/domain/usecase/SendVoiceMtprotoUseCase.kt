package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.EmptyMessageFilter
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
    private val conversationRepository: ConversationRepository
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

    @Volatile private var currentAudioFile: File? = null

    suspend fun startRecording(): Result<Unit> {
        if (audioRecorder.isRecording()) {
            return Result.failure(IllegalStateException("Ya hay una grabación en curso"))
        }
        val audioDir = File(context.cacheDir, "audio_outgoing").apply { mkdirs() }
        val audioFile = File(audioDir, "${UUID.randomUUID()}.ogg")
        val result = audioRecorder.start(audioFile)
        return if (result.isSuccess) {
            currentAudioFile = audioFile
            _status.value = SendStatus.Recording
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
        _status.value = SendStatus.Idle
    }

    suspend fun stopAndSend(): Result<Long> {
        val startedFile = currentAudioFile
            ?: return Result.failure(IllegalStateException("No hay grabación en curso"))

        val audioFile = audioRecorder.stop()
        currentAudioFile = null
        if (audioFile == null || !audioFile.exists()) {
            _status.value = SendStatus.Error("No se pudo guardar el audio")
            return Result.failure(Exception("No se pudo guardar el audio"))
        }

        _status.value = SendStatus.Validating
        val filter = emptyMessageFilter.validate(audioFile, minDurationMs = 500)
        if (!filter.accepted) {
            audioFile.delete()
            _status.value = SendStatus.Discarded(filter.reason ?: "Mensaje vacío")
            return Result.failure(Exception("Descartado: ${filter.reason}"))
        }

        if (tdLibClient.authState.value !is AuthState.Ready) {
            _status.value = SendStatus.Error("No autenticado con Telegram")
            return Result.failure(IllegalStateException("MTProto no autenticado"))
        }

        val target = conversationRepository.getActive()
        if (target == null) {
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
            handleSendResult(sendResult)
        } catch (e: Exception) {
            Timber.e(e, "MTProto send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            Result.failure(e)
        }
    }

    suspend fun sendQuickReply(text: String): Result<Long> {
        if (tdLibClient.authState.value !is AuthState.Ready) {
            _status.value = SendStatus.Error("No autenticado con Telegram")
            return Result.failure(IllegalStateException("MTProto no autenticado"))
        }
        val target = conversationRepository.getActive()
        if (target == null) {
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }
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
            handleSendResult(sendResult)
        } catch (e: Exception) {
            Timber.e(e, "MTProto text send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            Result.failure(e)
        }
    }

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
