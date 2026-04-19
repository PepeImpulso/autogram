package com.autogram.app.infrastructure.persistence.dao

import androidx.room.*
import com.autogram.app.infrastructure.persistence.entity.MessageEntity
import com.autogram.app.domain.model.MessageState
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun observeAll(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC")
    fun observeAllAcrossConversations(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt < :timestampMs")
    suspend fun deleteOlderThan(conversationId: String, timestampMs: Long)

    @Query("SELECT * FROM messages WHERE state = 'FAILED' AND retryCount < :maxRetries LIMIT :limit")
    suspend fun getPendingRetries(limit: Int, maxRetries: Int = 3): List<MessageEntity>

    // BUG-41 FIX: Removed broken query (MessageEntity has no enqueuedAt column).
    // Pending playbacks are tracked in PendingPlaybackDao instead.

    @Query("SELECT COUNT(*) FROM messages WHERE state = 'PENDING_PLAYBACK'")
    fun pendingPlaybackCount(): Flow<Int>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY createdAt ASC")
    suspend fun getAll(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE conversations SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    // BUG-20: Atomic set active operation
    @Transaction
    suspend fun setActiveAtomic(id: String) {
        deactivateAll()
        setActive(id)
    }
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun observe(): Flow<com.autogram.app.infrastructure.persistence.entity.SettingsEntity>

    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun get(): com.autogram.app.infrastructure.persistence.entity.SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: com.autogram.app.infrastructure.persistence.entity.SettingsEntity)
}

@Dao
interface QuickReplyDao {
    @Query("SELECT * FROM quick_replies WHERE isEnabled = 1 ORDER BY position ASC")
    fun observeAll(): Flow<List<com.autogram.app.infrastructure.persistence.entity.QuickReplyEntity>>

    @Query("SELECT * FROM quick_replies ORDER BY position ASC")
    suspend fun getAll(): List<com.autogram.app.infrastructure.persistence.entity.QuickReplyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reply: com.autogram.app.infrastructure.persistence.entity.QuickReplyEntity)

    @Query("DELETE FROM quick_replies WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DeliveryAttemptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: com.autogram.app.infrastructure.persistence.entity.DeliveryAttemptEntity)

    @Query("SELECT * FROM delivery_attempts WHERE messageId = :messageId ORDER BY attemptNumber DESC")
    suspend fun getByMessageId(messageId: String): List<com.autogram.app.infrastructure.persistence.entity.DeliveryAttemptEntity>
}

// BUG-05: DAO for persisting pending playbacks across restarts
@Dao
interface PendingPlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playback: com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity)

    @Query("SELECT * FROM pending_playbacks ORDER BY enqueuedAt ASC")
    suspend fun getAll(): List<com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity>

    @Query("DELETE FROM pending_playbacks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_playbacks")
    suspend fun deleteAll()
}
