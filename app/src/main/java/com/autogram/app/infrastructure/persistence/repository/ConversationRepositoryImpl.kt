package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.persistence.dao.ConversationDao
import com.autogram.app.infrastructure.persistence.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override suspend fun getActive(): Conversation? =
        conversationDao.getActive()?.toDomain()

    override fun observeActive(): Flow<Conversation?> =
        conversationDao.observeActive().map { it?.toDomain() }

    override suspend fun getById(id: String): Conversation? =
        conversationDao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Conversation> =
        conversationDao.getAll().map { it.toDomain() }

    override fun observePinned(): Flow<List<Conversation>> =
        conversationDao.observePinned().map { list -> list.map { it.toDomain() } }

    override suspend fun getPinned(): List<Conversation> =
        conversationDao.getPinned().map { it.toDomain() }

    override suspend fun upsert(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
    }

    override suspend fun setActive(id: String) {
        // BUG-20: Use atomic transaction to avoid race condition
        conversationDao.setActiveAtomic(id)
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        conversationDao.setPinned(id, pinned)
    }

    override suspend fun setPhotoPath(id: String, path: String?) {
        conversationDao.setPhotoPath(id, path)
    }

    override suspend fun setSortOrder(id: String, order: Int) {
        conversationDao.setSortOrder(id, order)
    }

    override suspend fun setLastRepliedAt(id: String, timestampMs: Long) {
        conversationDao.setLastRepliedAt(id, timestampMs)
    }

    override suspend fun setShowOnHome(id: String, show: Boolean) {
        conversationDao.setShowOnHome(id, show)
    }

    override suspend fun setFolder(id: String, folderId: String?) {
        conversationDao.setFolder(id, folderId)
    }

    override suspend fun delete(id: String) {
        conversationDao.deleteById(id)
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id, targetChatId = targetChatId, targetTitle = targetTitle,
        isActive = isActive, createdAt = createdAt,
        lastMessageAt = lastMessageAt, topicId = topicId, topicName = topicName,
        isPinned = isPinned, photoLocalPath = photoLocalPath, sortOrder = sortOrder,
        lastRepliedAt = lastRepliedAt, showOnHome = showOnHome, folderId = folderId
    )

    private fun Conversation.toEntity() = ConversationEntity(
        id = id, targetChatId = targetChatId, targetTitle = targetTitle,
        isActive = isActive, createdAt = createdAt,
        lastMessageAt = lastMessageAt, topicId = topicId, topicName = topicName,
        isPinned = isPinned, photoLocalPath = photoLocalPath, sortOrder = sortOrder,
        lastRepliedAt = lastRepliedAt, showOnHome = showOnHome, folderId = folderId
    )
}
