package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.infrastructure.persistence.dao.MessageDao
import com.autogram.app.infrastructure.persistence.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun observeAll(conversationId: String): Flow<List<Message>> {
        return messageDao.observeAll(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeAllAcrossConversations(): Flow<List<Message>> {
        return messageDao.observeAllAcrossConversations().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeLatestIncoming(): Flow<Message?> {
        return messageDao.observeLatestIncoming().map { it?.toDomain() }
    }

    override suspend fun getAll(): List<Message> {
        return messageDao.getAll().map { it.toDomain() }
    }

    override suspend fun getById(id: String): Message? {
        return messageDao.getById(id)?.toDomain()
    }

    override suspend fun insert(message: Message) {
        messageDao.insert(message.toEntity())
    }

    override suspend fun update(message: Message) {
        messageDao.update(message.toEntity())
    }

    override suspend fun deleteOlderThan(conversationId: String, timestampMs: Long) {
        messageDao.deleteOlderThan(conversationId, timestampMs)
    }

    override suspend fun clearForConversation(conversationId: String) {
        // Files first — once the rows are gone we lose the paths and the
        // .ogg blobs become orphaned in the audio cache directory.
        val paths = messageDao.audioPathsForConversation(conversationId)
        for (path in paths) {
            runCatching { java.io.File(path).delete() }
        }
        messageDao.deleteAllForConversation(conversationId)
    }

    override suspend fun getPendingRetries(limit: Int): List<Message> {
        return messageDao.getPendingRetries(limit).map { it.toDomain() }
    }

    override suspend fun getOutbox(limit: Int, maxRetries: Int): List<Message> =
        messageDao.getOutbox(limit, maxRetries).map { it.toDomain() }

    override suspend fun resetStuckSending(): Int = messageDao.resetStuckSending()

    override fun observeOutboxCount(): Flow<Int> = messageDao.observeOutboxCount()

    override fun observePendingReplyCounts(): Flow<Map<String, Int>> =
        messageDao.observePendingReplyCounts().map { rows ->
            rows.associate { it.conversationId to it.count }
        }

    override fun observePendingPlaybackQueue(): Flow<List<Message>> =
        messageDao.observePendingPlaybackQueue().map { it.map { e -> e.toDomain() } }

    override fun observeNowPlaying(): Flow<Message?> =
        messageDao.observeNowPlaying().map { it?.toDomain() }

    override suspend fun getStaleNowPlaying(): List<Message> =
        messageDao.getStaleNowPlaying().map { it.toDomain() }

    private fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        direction = direction,
        state = state,
        telegramMessageId = telegramMessageId,
        audioFilePath = audioFilePath,
        audioDurationMs = audioDurationMs,
        textContent = textContent,
        createdAt = createdAt,
        sentAt = sentAt,
        playedAt = playedAt,
        errorMessage = errorMessage,
        retryCount = retryCount,
        quickReplyId = quickReplyId,
        transcription = transcription,
        transcriptionState = transcriptionState,
        summary = summary,
        summaryState = summaryState
    )

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        direction = direction,
        state = state,
        telegramMessageId = telegramMessageId,
        audioFilePath = audioFilePath,
        audioDurationMs = audioDurationMs,
        textContent = textContent,
        createdAt = createdAt,
        sentAt = sentAt,
        playedAt = playedAt,
        errorMessage = errorMessage,
        retryCount = retryCount,
        quickReplyId = quickReplyId,
        transcription = transcription,
        transcriptionState = transcriptionState,
        summary = summary,
        summaryState = summaryState
    )
}
