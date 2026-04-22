package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.CircleFolder
import com.autogram.app.domain.repository.CircleFolderRepository
import com.autogram.app.infrastructure.persistence.dao.CircleFolderDao
import com.autogram.app.infrastructure.persistence.entity.CircleFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CircleFolderRepositoryImpl @Inject constructor(
    private val dao: CircleFolderDao
) : CircleFolderRepository {

    override fun observeAll(): Flow<List<CircleFolder>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<CircleFolder> =
        dao.getAll().map { it.toDomain() }

    override suspend fun upsert(folder: CircleFolder) {
        dao.upsert(folder.toEntity())
    }

    override suspend fun delete(id: String) {
        // Atomic detach + delete via a @Transaction method on the DAO. Without
        // the transaction, a crash between the two statements could leave
        // conversations pointing at a folder that's no longer in the DB.
        dao.detachAndDelete(id)
    }

    private fun CircleFolderEntity.toDomain() = CircleFolder(
        id = id, name = name, color = color, sortOrder = sortOrder
    )

    private fun CircleFolder.toEntity() = CircleFolderEntity(
        id = id, name = name, color = color, sortOrder = sortOrder
    )
}
