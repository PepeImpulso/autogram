package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.infrastructure.persistence.dao.QuickReplyDao
import com.autogram.app.infrastructure.persistence.entity.QuickReplyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickReplyRepositoryImpl @Inject constructor(
    private val quickReplyDao: QuickReplyDao
) : QuickReplyRepository {

    override fun observeAll(): Flow<List<QuickReply>> {
        return quickReplyDao.observeAll().map { it.map { e -> e.toDomain() } }
    }

    override suspend fun getAll(): List<QuickReply> {
        return quickReplyDao.getAll().map { it.toDomain() }
    }

    override suspend fun upsert(reply: QuickReply) {
        quickReplyDao.upsert(reply.toEntity())
    }

    override suspend fun delete(id: String) {
        quickReplyDao.delete(id)
    }

    private fun QuickReplyEntity.toDomain() = QuickReply(
        id = id,
        label = label,
        kind = runCatching { QuickReplyKind.valueOf(kind) }.getOrDefault(QuickReplyKind.TEXT),
        textContent = textContent,
        audioFilePath = audioFilePath,
        audioDurationMs = audioDurationMs,
        position = position,
        isEnabled = isEnabled
    )

    private fun QuickReply.toEntity() = QuickReplyEntity(
        id = id,
        label = label,
        kind = kind.name,
        textContent = textContent,
        audioFilePath = audioFilePath,
        audioDurationMs = audioDurationMs,
        position = position,
        isEnabled = isEnabled
    )
}
