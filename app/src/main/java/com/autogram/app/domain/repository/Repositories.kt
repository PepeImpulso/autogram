package com.autogram.app.domain.repository

import com.autogram.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeAll(conversationId: String): Flow<List<Message>>
    suspend fun getById(id: String): Message?
    suspend fun insert(message: Message)
    suspend fun update(message: Message)
    suspend fun deleteOlderThan(conversationId: String, timestampMs: Long)
    suspend fun getPendingRetries(limit: Int): List<Message>
}

interface ConversationRepository {
    suspend fun getActive(): Conversation?
    suspend fun getById(id: String): Conversation?
    suspend fun getAll(): List<Conversation>
    suspend fun upsert(conversation: Conversation)
    suspend fun setActive(id: String)
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
