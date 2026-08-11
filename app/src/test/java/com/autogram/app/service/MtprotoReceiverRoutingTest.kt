package com.autogram.app.service

import com.autogram.app.domain.model.Conversation
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtprotoReceiverRoutingTest {

    @Test
    fun `local message id is stable and scoped by Telegram chat`() {
        assertEquals("telegram:-10042:73", receiverMessageId(-10042, 73))
        assertEquals(
            receiverMessageId(-10042, 73),
            receiverMessageId(-10042, 73)
        )
        assertFalse(receiverMessageId(-10042, 73) == receiverMessageId(-10043, 73))
    }

    @Test
    fun `routing keeps first target for a duplicate exact key`() {
        val primary = conversation("primary", chatId = "42", topicId = 7)
        val duplicate = conversation("duplicate", chatId = "42", topicId = 7)

        val snapshot = buildReceiverRouting(listOf(primary, duplicate))

        val key = ReceiverRouteKey(42, 7)
        assertEquals(primary, snapshot.routes[key])
        assertEquals(setOf(key), snapshot.duplicateKeys)
    }

    @Test
    fun `routing separates chat root and forum topic and rejects invalid chat ids`() {
        val root = conversation("root", chatId = "42", topicId = null)
        val topic = conversation("topic", chatId = "42", topicId = 9)
        val invalid = conversation("invalid", chatId = "not-a-number", topicId = null)

        val snapshot = buildReceiverRouting(listOf(root, topic, invalid))

        assertEquals(root, snapshot.routes[ReceiverRouteKey(42, null)])
        assertEquals(topic, snapshot.routes[ReceiverRouteKey(42, 9)])
        assertEquals(listOf(invalid), snapshot.invalidTargets)
    }

    @Test
    fun `message route supports forum topics only`() {
        assertEquals(ReceiverRouteKey(11, null), receiverRouteKey(11, null))
        assertEquals(ReceiverRouteKey(11, 3), receiverRouteKey(11, TdApi.MessageTopicForum(3)))
        assertNull(receiverRouteKey(11, TdApi.MessageTopicDirectMessages(3)))
    }

    @Test
    fun `classifier accepts voice audio and audio documents`() {
        val voice = TdApi.MessageVoiceNote().apply {
            voiceNote = TdApi.VoiceNote().apply {
                duration = 4
                mimeType = "audio/ogg"
                this.voice = tdFile(1)
            }
        }
        val audio = TdApi.MessageAudio().apply {
            this.audio = TdApi.Audio().apply {
                duration = 8
                mimeType = "audio/mpeg"
                fileName = "reply.mp3"
                this.audio = tdFile(2)
            }
        }
        val document = TdApi.MessageDocument().apply {
            this.document = TdApi.Document().apply {
                mimeType = "audio/ogg"
                fileName = "agent-reply.ogg"
                this.document = tdFile(3)
            }
        }

        val voiceResult = classifyReceiverContent(voice) as ReceiverContent.Audio
        val audioResult = classifyReceiverContent(audio) as ReceiverContent.Audio
        val documentResult = classifyReceiverContent(document) as ReceiverContent.Audio

        assertTrue(voiceResult.oggTranscriptionCompatible)
        assertFalse(audioResult.oggTranscriptionCompatible)
        assertTrue(documentResult.oggTranscriptionCompatible)
        assertEquals(8, audioResult.durationSeconds)
    }

    @Test
    fun `classifier rejects non-audio documents`() {
        val document = TdApi.MessageDocument().apply {
            this.document = TdApi.Document().apply {
                mimeType = "application/pdf"
                fileName = "report.pdf"
                this.document = tdFile(4)
            }
        }

        assertTrue(classifyReceiverContent(document) is ReceiverContent.Unsupported)
    }

    @Test
    fun `classifier accepts audio extension when document mime is generic`() {
        val document = TdApi.MessageDocument().apply {
            this.document = TdApi.Document().apply {
                mimeType = "application/octet-stream"
                fileName = "agent-reply.OGG"
                this.document = tdFile(5)
            }
        }

        val result = classifyReceiverContent(document) as ReceiverContent.Audio

        assertTrue(result.oggTranscriptionCompatible)
    }

    @Test
    fun `download retry classifier distinguishes transient and permanent errors`() {
        assertTrue(isTransientDownloadError(598, "request timeout"))
        assertTrue(isTransientDownloadError(400, "NETWORK unavailable"))
        assertFalse(isTransientDownloadError(400, "FILE_ID_INVALID"))
    }

    @Test
    fun `ogg classifier accepts codec parameters and filename fallback`() {
        assertTrue(isOggAudio("audio/ogg; codecs=opus", null))
        assertTrue(isOggAudio("application/octet-stream", "reply.OGG"))
        assertFalse(isOggAudio("audio/mpeg", "reply.mp3"))
    }

    private fun conversation(id: String, chatId: String, topicId: Long?): Conversation =
        Conversation(id = id, targetChatId = chatId, targetTitle = id, topicId = topicId)

    private fun tdFile(id: Int): TdApi.File = TdApi.File().apply { this.id = id }
}
