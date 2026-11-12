package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.infrastructure.persistence.dao.MessageDao
import com.autogram.app.infrastructure.persistence.entity.MessageEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarHistoryRepositoryTest {
    @Test
    fun `history maps the initial snapshot and subsequent text and audio cleanup updates`() = runTest {
        val voice = message("voice", 1).copy(audioFilePath = "/voice.ogg", transcription = "Transcript")
        val text = message("text", 2).copy(textContent = "Recent reply")
        val rows = MutableStateFlow(listOf(voice))
        val dao = mockk<MessageDao>()
        every { dao.observeCarHistory(50) } returns rows
        val repository = MessageRepositoryImpl(dao)
        val snapshots = mutableListOf<List<com.autogram.app.domain.model.Message>>()
        val collection = launch { repository.observeCarHistory().take(3).toList(snapshots) }
        runCurrent()
        rows.value = listOf(text, voice)
        runCurrent()
        rows.value = listOf(text, voice.copy(audioFilePath = null))
        runCurrent()
        collection.join()

        assertEquals(listOf("voice"), snapshots[0].map { it.id })
        assertEquals(listOf("text", "voice"), snapshots[1].map { it.id })
        assertEquals("Recent reply", snapshots[1][0].textContent)
        assertEquals(null, snapshots[2][1].audioFilePath)
        assertEquals("Transcript", snapshots[2][1].transcription)
        // Reopening History reads the current snapshot immediately.
        assertEquals(snapshots.last(), repository.observeCarHistory().first())
        verify(exactly = 2) { dao.observeCarHistory(50) }
    }

    private fun message(id: String, timestamp: Long) = MessageEntity(
        id = id,
        conversationId = "contact",
        direction = MessageDirection.INCOMING,
        state = MessageState.RECEIVED,
        createdAt = timestamp
    )
}
