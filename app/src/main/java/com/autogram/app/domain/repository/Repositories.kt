package com.autogram.app.domain.repository

import com.autogram.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeAll(conversationId: String): Flow<List<Message>>
    fun observeAllAcrossConversations(): Flow<List<Message>>
    /** Latest incoming message (voice with audio, or text) — targeted query for the replay button. */
    fun observeLatestIncoming(): Flow<Message?>
    /** Unbounded one-shot snapshot for retention + lifetime stats. */
    suspend fun getAll(): List<Message>
    suspend fun getById(id: String): Message?
    suspend fun insert(message: Message)
    suspend fun update(message: Message)
    suspend fun deleteOlderThan(conversationId: String, timestampMs: Long)
    /**
     * User-initiated wipe of every message in [conversationId]. Removes
     * audio files from disk first, then deletes the rows. Pending-playback
     * rows tied to those messages cascade-delete via the FK on
     * pending_playbacks → messages.
     */
    suspend fun clearForConversation(conversationId: String)
    suspend fun getPendingRetries(limit: Int): List<Message>
    suspend fun getOutbox(limit: Int = 50, maxRetries: Int = 3): List<Message>
    /** Returns count of rows that were promoted back to QUEUED_OFFLINE. */
    suspend fun resetStuckSending(): Int
    fun observeOutboxCount(): Flow<Int>
    fun observePendingReplyCounts(): Flow<Map<String, Int>>
    /** Live queue contents for the Pending Queue screen, in drain order. */
    fun observePendingPlaybackQueue(): Flow<List<Message>>
    /** Single currently-playing (or paused) message, for Now Playing panel. */
    fun observeNowPlaying(): Flow<Message?>
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
    /** Home-screen feed: only shortcuts the user has left enabled. */
    fun observeAll(): Flow<List<QuickReply>>
    /** Editor feed: includes disabled shortcuts so they can be re-shown later. */
    fun observeAllIncludingDisabled(): Flow<List<QuickReply>>
    suspend fun getAll(): List<QuickReply>
    suspend fun upsert(reply: QuickReply)
    suspend fun delete(id: String)
    /**
     * Persist a new ordering for the shortcut list. [orderedIds] is the
     * full list of shortcut IDs in the order the user wants them to
     * appear; this method writes `position = index` for each so the
     * home-screen and editor feeds (both `ORDER BY position ASC`) reflect
     * the new order. Used by the drag-reorder UX in the Shortcuts
     * editor.
     */
    suspend fun reorder(orderedIds: List<String>)
}
