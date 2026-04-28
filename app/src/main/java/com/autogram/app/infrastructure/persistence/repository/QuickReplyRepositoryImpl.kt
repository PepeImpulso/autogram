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

    override fun observeAllIncludingDisabled(): Flow<List<QuickReply>> {
        return quickReplyDao.observeAllIncludingDisabled().map { it.map { e -> e.toDomain() } }
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

    override suspend fun reorder(orderedIds: List<String>) {
        // Two-phase write so drag-reorder doesn't ever observe a state
        // where two shortcuts share the same position (which would
        // make ORDER BY position ASC return them in arbitrary order
        // and the editor feed would visibly flicker mid-drag).
        // Phase 1 pushes everyone past the existing max so positions
        // are unique-but-temporary; phase 2 writes the final 0..N-1.
        val all = quickReplyDao.getAll()
        val maxPos = all.maxOfOrNull { it.position } ?: 0
        orderedIds.forEachIndexed { index, id ->
            quickReplyDao.setPosition(id, maxPos + 1 + index)
        }
        orderedIds.forEachIndexed { index, id ->
            quickReplyDao.setPosition(id, index)
        }
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
