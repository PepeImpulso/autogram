package com.autogram.app.infrastructure.persistence.dao

import androidx.room.*
import com.autogram.app.infrastructure.persistence.entity.ConversationEntity
import com.autogram.app.infrastructure.persistence.entity.DeliveryAttemptEntity
import com.autogram.app.infrastructure.persistence.entity.MessageEntity
import com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity
import com.autogram.app.infrastructure.persistence.entity.QuickReplyEntity
import com.autogram.app.infrastructure.persistence.entity.SettingsEntity
import com.autogram.app.domain.model.MessageState
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // UI-facing observe queries are bounded to the N most-recent rows.
    // Without a LIMIT they grow unboundedly: after months of daily use
    // a long-tail user can accumulate 10k+ rows, and the MainViewModel
    // flow combines on top of this query → a full-table scan fires
    // per incoming message, blocks recomposition, and heap-spikes
    // with ≈several-KB-per-row due to transcription/summary columns.
    // 500 comfortably covers the longest realistic History scroll.
    // Retention + stats use the unbounded `getAll()` below since they
    // genuinely need every row.
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 500")
    fun observeAll(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT 500")
    fun observeAllAcrossConversations(): Flow<List<MessageEntity>>

    // Unbounded snapshot used by the retention worker's orphan-audio
    // sweep and by GetStatsUseCase (lifetime stats). Runs on a
    // background thread — NOT a Flow, so it's a single one-shot query
    // and doesn't keep an open cursor while traversing.
    @Query("SELECT * FROM messages")
    suspend fun getAll(): List<MessageEntity>

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

    // Outbox: both states that should be retried when the network/TDLib comes
    // back — offline queue + transient failures. Ordered oldest-first so the
    // user sees replies land in the order they tried to send them.
    @Query("""
        SELECT * FROM messages
        WHERE (state = 'QUEUED_OFFLINE'
               OR (state = 'FAILED' AND retryCount < :maxRetries))
          AND direction = 'OUTGOING'
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getOutbox(limit: Int = 50, maxRetries: Int = 3): List<MessageEntity>

    /**
     * Un-stuck rows left in SENDING by a retry worker that was
     * cancelled mid-drain (e.g. by ExistingWorkPolicy.REPLACE when a
     * fresh message arrives during the prior run). Without this sweep
     * those rows are invisible to `getOutbox` (which only scans
     * QUEUED_OFFLINE and FAILED) and would sit in SENDING forever.
     */
    @Query("""
        UPDATE messages SET state = 'QUEUED_OFFLINE'
        WHERE direction = 'OUTGOING' AND state = 'SENDING'
    """)
    suspend fun resetStuckSending(): Int

    // Live count for the UI pill: how many outgoing messages are waiting to go out.
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE direction = 'OUTGOING'
          AND (state = 'QUEUED_OFFLINE' OR state = 'FAILED')
    """)
    fun observeOutboxCount(): Flow<Int>

    // BUG-41 FIX: Removed broken query (MessageEntity has no enqueuedAt column).
    // Pending playbacks are tracked in PendingPlaybackDao instead.

    @Query("SELECT COUNT(*) FROM messages WHERE state = 'PENDING_PLAYBACK'")
    fun pendingPlaybackCount(): Flow<Int>

    // Badge counter: incoming messages received since the user last replied to
    // that conversation. Joins with conversations.lastRepliedAt; NULL means the
    // user has never replied, so every incoming still counts.
    @Query("""
        SELECT m.conversationId AS conversationId, COUNT(*) AS count
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        WHERE m.direction = 'INCOMING'
          AND m.createdAt > COALESCE(c.lastRepliedAt, 0)
        GROUP BY m.conversationId
    """)
    fun observePendingReplyCounts(): Flow<List<PendingReplyCountRow>>
}

data class PendingReplyCountRow(
    val conversationId: String,
    val count: Int
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY createdAt ASC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE isPinned = 1 OR isActive = 1 ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    fun observePinned(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isPinned = 1 OR isActive = 1 ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    suspend fun getPinned(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE conversations SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET photoLocalPath = :path WHERE id = :id")
    suspend fun setPhotoPath(id: String, path: String?)

    @Query("UPDATE conversations SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)

    @Query("UPDATE conversations SET lastRepliedAt = :ts WHERE id = :id")
    suspend fun setLastRepliedAt(id: String, ts: Long)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Query("UPDATE conversations SET folderId = NULL WHERE folderId = :folderId")
    suspend fun detachFolder(folderId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

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

    @Query("DELETE FROM pending_playbacks WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("DELETE FROM pending_playbacks")
    suspend fun deleteAll()
}

@Dao
abstract class CircleFolderDao {
    @Query("SELECT * FROM circle_folders ORDER BY sortOrder ASC, name ASC")
    abstract fun observeAll(): Flow<List<com.autogram.app.infrastructure.persistence.entity.CircleFolderEntity>>

    @Query("SELECT * FROM circle_folders ORDER BY sortOrder ASC, name ASC")
    abstract suspend fun getAll(): List<com.autogram.app.infrastructure.persistence.entity.CircleFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(folder: com.autogram.app.infrastructure.persistence.entity.CircleFolderEntity)

    @Query("DELETE FROM circle_folders WHERE id = :id")
    abstract suspend fun deleteRow(id: String)

    @Query("UPDATE conversations SET folderId = NULL WHERE folderId = :folderId")
    abstract suspend fun detachConversations(folderId: String)

    /**
     * Atomically detach member conversations and drop the folder row. Wrapped
     * in @Transaction so a crash between the two operations can't leave
     * conversations referencing a folder that was half-deleted.
     */
    @Transaction
    open suspend fun detachAndDelete(id: String) {
        detachConversations(id)
        deleteRow(id)
    }
}
