package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.persistence.dao.ConversationDao
import com.autogram.app.infrastructure.persistence.entity.ConversationEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override suspend fun getActive(): Conversation? {
        return conversationDao.getActive()?.toDomain()
    }

    override suspend fun getById(id: String): Conversation? {
        return conversationDao.getById(id)?.toDomain()
    }

    override suspend fun upsert(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
    }

    override suspend fun setActive(id: String) {
        conversationDao.deactivateAll()
        conversationDao.setActive(id)
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id, targetChatId = targetChatId, targetTitle = targetTitle,
        botToken = botToken, isActive = isActive, createdAt = createdAt, lastMessageAt = lastMessageAt
    )

    private fun Conversation.toEntity() = ConversationEntity(
        id = id, targetChatId = targetChatId, targetTitle = targetTitle,
        botToken = botToken, isActive = isActive, createdAt = createdAt, lastMessageAt = lastMessageAt
    )
}
