package com.autogram.app.domain.repository

import com.autogram.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeAll(conversationId: String): Flow<List<Message>>
    fun observeAllAcrossConversations(): Flow<List<Message>>
    /** Unbounded one-shot snapshot for retention + lifetime stats. */
    suspend fun getAll(): List<Message>
    suspend fun getById(id: String): Message?
    suspend fun insert(message: Message)
    suspend fun update(message: Message)
    suspend fun deleteOlderThan(conversationId: String, timestampMs: Long)
    suspend fun getPendingRetries(limit: Int): List<Message>
    suspend fun getOutbox(limit: Int = 50, maxRetries: Int = 3): List<Message>
    fun observeOutboxCount(): Flow<Int>
    fun observePendingReplyCounts(): Flow<Map<String, Int>>
}

interface ConversationRepository {
    suspend fun getActive(): Conversation?
    fun observeActive(): Flow<Conversation?>
    suspend fun getById(id: String): Conversation?
    suspend fun getAll(): List<Conversation>
    fun observePinned(): Flow<List<Conversation>>
    suspend fun getPinned(): List<Conversation>
    suspend fun upsert(conversation: Conversation)
    suspend fun setActive(id: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setPhotoPath(id: String, path: String?)
    suspend fun setSortOrder(id: String, order: Int)
    suspend fun setLastRepliedAt(id: String, timestampMs: Long)
    suspend fun setFolder(id: String, folderId: String?)
    suspend fun delete(id: String)
}

interface CircleFolderRepository {
    fun observeAll(): Flow<List<CircleFolder>>
    suspend fun getAll(): List<CircleFolder>
    suspend fun upsert(folder: CircleFolder)
    suspend fun delete(id: String)
}

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(settings: AppSettings)
}

interface QuickReplyRepository {
    fun observeAll(): Flow<List<QuickReply>>
    suspend fun getAll(): List<QuickReply>
    suspend fun upsert(reply: QuickReply)
    suspend fun delete(id: String)
}
