package com.autogram.app.infrastructure.telegram.mtproto

import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

data class TelegramReportOption(
    val id: ByteArray,
    val label: String
)

sealed interface TelegramReportStep {
    data object Submitted : TelegramReportStep
    data class ChooseOption(
        val title: String,
        val options: List<TelegramReportOption>
    ) : TelegramReportStep
    data class EnterDetails(
        val optionId: ByteArray,
        val optional: Boolean
    ) : TelegramReportStep
    data object ChooseMessage : TelegramReportStep
    data class Failed(val message: String) : TelegramReportStep
}

sealed interface TelegramModerationAction {
    data object Completed : TelegramModerationAction
    data class Failed(val message: String) : TelegramModerationAction
}

/**
 * Provides the Telegram-backed moderation controls required for messaging UGC.
 * Reports are sent directly to Telegram moderators; Autogram never receives or
 * stores the report contents.
 */
@Singleton
class TelegramModerationService @Inject constructor(
    private val sender: TdLibSender
) {
    suspend fun blockChat(chatId: Long): TelegramModerationAction {
        val chat = sender.send(TdApi.GetChat(chatId))
        if (chat is TdApi.Error) return TelegramModerationAction.Failed(chat.message)
        if (chat !is TdApi.Chat) {
            return TelegramModerationAction.Failed("Telegram returned an unexpected chat response")
        }

        val messageSender = when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> TdApi.MessageSenderUser(type.userId)
            is TdApi.ChatTypeSupergroup -> TdApi.MessageSenderChat(chat.id)
            else -> return TelegramModerationAction.Failed("This Telegram chat type cannot be blocked")
        }
        val result = sender.send(
            TdApi.SetMessageSenderBlockList(messageSender, TdApi.BlockListMain())
        )
        return when (result) {
            is TdApi.Ok -> TelegramModerationAction.Completed
            is TdApi.Error -> TelegramModerationAction.Failed(result.message)
            else -> TelegramModerationAction.Failed("Telegram returned an unexpected block response")
        }
    }

    suspend fun report(
        chatId: Long,
        messageIds: LongArray = longArrayOf(),
        optionId: ByteArray = byteArrayOf(),
        details: String = ""
    ): TelegramReportStep {
        val result = sender.send(TdApi.ReportChat(chatId, optionId, messageIds, details))
        return when (result) {
            is TdApi.ReportChatResultOk -> TelegramReportStep.Submitted
            is TdApi.ReportChatResultOptionRequired -> TelegramReportStep.ChooseOption(
                title = result.title,
                options = result.options.map { TelegramReportOption(it.id, it.text) }
            )
            is TdApi.ReportChatResultTextRequired -> TelegramReportStep.EnterDetails(
                optionId = result.optionId,
                optional = result.isOptional
            )
            is TdApi.ReportChatResultMessagesRequired -> TelegramReportStep.ChooseMessage
            is TdApi.Error -> TelegramReportStep.Failed(result.message)
            else -> TelegramReportStep.Failed("Telegram returned an unexpected report response")
        }
    }
}
