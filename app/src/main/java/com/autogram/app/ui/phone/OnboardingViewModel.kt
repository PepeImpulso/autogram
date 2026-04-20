package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.TelegramApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val telegramApiClient: TelegramApiClient,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    enum class Step { WELCOME, TOKEN, CHAT, DONE }

    data class UiState(
        val step: Step = Step.WELCOME,
        val token: String = "",
        val tokenValid: Boolean = false,
        val tokenError: String? = null,
        val testing: Boolean = false,
        val botUsername: String? = null,
        val chatId: String? = null,
        val chatTitle: String? = null,
        val chatError: String? = null,
        val detecting: Boolean = false,
        val finished: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun goNext() {
        _state.value = _state.value.copy(
            step = when (_state.value.step) {
                Step.WELCOME -> Step.TOKEN
                Step.TOKEN -> Step.CHAT
                Step.CHAT -> Step.DONE
                Step.DONE -> Step.DONE
            }
        )
    }

    fun goBack() {
        _state.value = _state.value.copy(
            step = when (_state.value.step) {
                Step.WELCOME -> Step.WELCOME
                Step.TOKEN -> Step.WELCOME
                Step.CHAT -> Step.TOKEN
                Step.DONE -> Step.CHAT
            }
        )
    }

    fun setToken(value: String) {
        _state.value = _state.value.copy(
            token = value.trim(),
            tokenValid = false,
            tokenError = null,
            botUsername = null
        )
    }

    fun testToken() {
        val token = _state.value.token
        if (!isLikelyTokenFormat(token)) {
            _state.value = _state.value.copy(tokenError = "format_invalid")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, tokenError = null)
            try {
                telegramApiClient.configure(token)
                val result = telegramApiClient.testConnection()
                if (result.isSuccess) {
                    val info = result.getOrThrow()
                    _state.value = _state.value.copy(
                        testing = false,
                        tokenValid = true,
                        botUsername = info.username ?: info.first_name
                    )
                } else {
                    _state.value = _state.value.copy(
                        testing = false,
                        tokenValid = false,
                        tokenError = result.exceptionOrNull()?.message ?: "unknown"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Onboarding: testToken failed")
                _state.value = _state.value.copy(
                    testing = false,
                    tokenValid = false,
                    tokenError = e.message ?: "unknown"
                )
            }
        }
    }

    fun detectChat() {
        viewModelScope.launch {
            _state.value = _state.value.copy(detecting = true, chatError = null)
            try {
                telegramApiClient.configure(_state.value.token)

                // Defensive: drop any leftover webhook (would block getUpdates with 409 forever).
                runCatching { telegramApiClient.deleteWebhook(dropPendingUpdates = false) }

                // Short-poll (timeout=0) so we don't hold a long-poll that conflicts
                // with the AutogramPollingService if it's running. Retry once after
                // 5s on 409 to let any stale long-poll on Telegram's side expire.
                var result = telegramApiClient.getUpdatesShort()
                if (result.isFailure && result.exceptionOrNull()?.message?.contains("CONFLICT") == true) {
                    Timber.w("Onboarding: 409 detected, waiting 5s and retrying")
                    kotlinx.coroutines.delay(5000)
                    result = telegramApiClient.getUpdatesShort()
                }

                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "unknown"
                    val errorCode = if (msg.contains("CONFLICT")) "conflict" else msg
                    _state.value = _state.value.copy(detecting = false, chatError = errorCode)
                    return@launch
                }

                val updates = result.getOrThrow()
                val firstChat = updates.asSequence()
                    .mapNotNull { it.message ?: it.edited_message }
                    .map { it.chat }
                    .firstOrNull()
                if (firstChat == null) {
                    _state.value = _state.value.copy(detecting = false, chatError = "no_message")
                } else {
                    _state.value = _state.value.copy(
                        detecting = false,
                        chatId = firstChat.id.toString(),
                        chatTitle = firstChat.title
                            ?: firstChat.username
                            ?: firstChat.first_name
                            ?: "Chat ${firstChat.id}"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Onboarding: detectChat failed")
                _state.value = _state.value.copy(
                    detecting = false,
                    chatError = e.message ?: "unknown"
                )
            }
        }
    }

    /**
     * Manual chat_id entry as a fallback when detect-chat keeps failing.
     * Validates the value is a numeric Long; positive = private, negative = group/channel.
     */
    fun setManualChatId(value: String) {
        val trimmed = value.trim()
        val parsed = trimmed.toLongOrNull()
        if (parsed == null) {
            _state.value = _state.value.copy(chatError = "invalid_chat_id")
            return
        }
        _state.value = _state.value.copy(
            chatId = trimmed,
            chatTitle = "Chat $trimmed",
            chatError = null
        )
    }

    fun finish() {
        val s = _state.value
        if (!s.tokenValid || s.chatId.isNullOrBlank()) return
        viewModelScope.launch {
            val conversation = Conversation(
                id = UUID.randomUUID().toString(),
                botToken = s.token,
                targetChatId = s.chatId,
                targetTitle = s.chatTitle,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )
            conversationRepository.upsert(conversation)
            conversationRepository.setActive(conversation.id)

            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(activeConversationId = conversation.id))
            _state.value = s.copy(finished = true, step = Step.DONE)
        }
    }

    private fun isLikelyTokenFormat(token: String): Boolean {
        // Telegram bot token format: <digits>:<35 alphanumeric/-/_> (approximate)
        return Regex("""^\d{6,}:[A-Za-z0-9_-]{30,}$""").matches(token)
    }
}
