package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.Message
import com.autogram.app.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Caso de uso: gestionar el historial de mensajes.
 */
class ManageHistoryUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageRepository.observeAll(conversationId)

    suspend fun getMessage(id: String): Message? = messageRepository.getById(id)

    suspend fun cleanupOldMessages(conversationId: String, retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
        messageRepository.deleteOlderThan(conversationId, cutoff)
    }
}
