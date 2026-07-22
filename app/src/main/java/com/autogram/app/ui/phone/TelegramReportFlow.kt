package com.autogram.app.ui.phone

import com.autogram.app.infrastructure.telegram.mtproto.TelegramModerationService
import com.autogram.app.infrastructure.telegram.mtproto.TelegramReportOption
import com.autogram.app.infrastructure.telegram.mtproto.TelegramReportStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TelegramReportUiState {
    data object Loading : TelegramReportUiState
    data class ChooseOption(
        val title: String,
        val options: List<TelegramReportOption>
    ) : TelegramReportUiState
    data class EnterDetails(val optional: Boolean) : TelegramReportUiState
    data object ChooseMessage : TelegramReportUiState
    data object Submitted : TelegramReportUiState
    data class Failed(val message: String) : TelegramReportUiState
}

/** Keeps Telegram's multi-step report protocol out of individual screens. */
class TelegramReportFlow(
    private val service: TelegramModerationService,
    private val scope: CoroutineScope
) {
    private data class Request(
        val chatId: Long,
        val messageIds: LongArray,
        val optionId: ByteArray = byteArrayOf()
    )

    private val _state = MutableStateFlow<TelegramReportUiState?>(null)
    val state: StateFlow<TelegramReportUiState?> = _state.asStateFlow()
    private var request: Request? = null

    fun start(chatId: Long, messageIds: LongArray = longArrayOf()) {
        request = Request(chatId, messageIds)
        execute()
    }

    fun choose(option: TelegramReportOption) {
        val current = request ?: return
        request = current.copy(optionId = option.id)
        execute()
    }

    fun submitDetails(details: String) {
        execute(details.trim())
    }

    fun dismiss() {
        request = null
        _state.value = null
    }

    private fun execute(details: String = "") {
        val current = request ?: return
        _state.value = TelegramReportUiState.Loading
        scope.launch {
            _state.value = when (
                val result = service.report(
                    chatId = current.chatId,
                    messageIds = current.messageIds,
                    optionId = current.optionId,
                    details = details
                )
            ) {
                TelegramReportStep.Submitted -> TelegramReportUiState.Submitted
                is TelegramReportStep.ChooseOption -> TelegramReportUiState.ChooseOption(
                    result.title,
                    result.options
                )
                is TelegramReportStep.EnterDetails -> {
                    request = current.copy(optionId = result.optionId)
                    TelegramReportUiState.EnterDetails(result.optional)
                }
                TelegramReportStep.ChooseMessage -> TelegramReportUiState.ChooseMessage
                is TelegramReportStep.Failed -> TelegramReportUiState.Failed(result.message)
            }
        }
    }
}
