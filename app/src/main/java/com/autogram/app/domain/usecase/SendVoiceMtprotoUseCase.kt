package com.autogram.app.domain.usecase

import android.content.Context
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
 * Phase 2a: voice messages sent via MTProto so they appear as from the user
 * (not the bot) in @pepemaldo_bot's chat.
 *
 * Destination is hardcoded to the DM with @pepemaldo_bot. The topic/chat picker
 * is Phase 2b.
 */
@Singleton
class SendVoiceMtprotoUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tdLibClient: TdLibClient,
    private val audioRecorder: AudioRecorder,
    private val emptyMessageFilter: EmptyMessageFilter
) {
    private companion object {
        const val TARGET_USERNAME = "pepemaldo_bot"
    }

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
    @Volatile private var cachedChatId: Long? = null

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
        // Minimal duration comes from settings repo in the legacy path; for Phase 2a
        // we pass 500ms — matches the existing default and keeps scope small.
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

        _status.value = SendStatus.Sending
        return try {
            val chatId = resolveTargetChatId()
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(audioFile.absolutePath)
                        duration = ((emptyMessageFilter.getDurationMs(audioFile) ?: 0L) / 1000).toInt()
                    }
                }
            )
            when (sendResult) {
                is TdApi.Message -> {
                    _status.value = SendStatus.Sent(sendResult.id)
                    Result.success(sendResult.id)
                }
                is TdApi.Error -> {
                    val msg = "Telegram ${sendResult.code}: ${sendResult.message}"
                    _status.value = SendStatus.Error(msg)
                    Result.failure(Exception(msg))
                }
                else -> {
                    _status.value = SendStatus.Error("Respuesta inesperada de TDLib")
                    Result.failure(Exception("Unexpected TDLib response: ${sendResult.javaClass.simpleName}"))
                }
            }
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
        _status.value = SendStatus.Sending
        return try {
            val chatId = resolveTargetChatId()
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(text, emptyArray())
                    }
                }
            )
            when (sendResult) {
                is TdApi.Message -> {
                    _status.value = SendStatus.Sent(sendResult.id)
                    Result.success(sendResult.id)
                }
                is TdApi.Error -> {
                    val msg = "Telegram ${sendResult.code}: ${sendResult.message}"
                    _status.value = SendStatus.Error(msg)
                    Result.failure(Exception(msg))
                }
                else -> {
                    _status.value = SendStatus.Error("Respuesta inesperada de TDLib")
                    Result.failure(Exception("Unexpected TDLib response"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MTProto text send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            Result.failure(e)
        }
    }

    fun reset() {
        _status.value = SendStatus.Idle
    }

    private suspend fun resolveTargetChatId(): Long {
        cachedChatId?.let { return it }
        val res = tdLibClient.send(TdApi.SearchPublicChat(TARGET_USERNAME))
        if (res is TdApi.Error) {
            throw IllegalStateException("No se pudo resolver @$TARGET_USERNAME: ${res.code} ${res.message}")
        }
        val chat = res as TdApi.Chat
        cachedChatId = chat.id
        Timber.d("Resolved @$TARGET_USERNAME → chatId=${chat.id}")
        return chat.id
    }
}
