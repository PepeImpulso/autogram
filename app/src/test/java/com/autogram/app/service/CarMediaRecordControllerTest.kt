package com.autogram.app.service

import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaRecordControllerTest {

    @Test
    fun `record media id round-trips the conversation id`() {
        val id = CarMediaRecordController.recordMediaId("conv-42")
        assertEquals("conv-42", CarMediaRecordController.recordTargetOf(id))
    }

    @Test
    fun `history and pending ids are not record items`() {
        assertNull(CarMediaRecordController.recordTargetOf("history"))
        assertNull(CarMediaRecordController.recordTargetOf("b3f1-message-uuid"))
        assertNull(CarMediaRecordController.recordTargetOf("record:"))
    }

    @Test
    fun `shortcut ids are kept apart from record ids`() {
        val id = CarMediaRecordController.shortcutMediaId("qr-7")
        assertEquals("qr-7", CarMediaRecordController.shortcutIdOf(id))
        assertNull(CarMediaRecordController.recordTargetOf(id))
        assertNull(CarMediaRecordController.shortcutIdOf(CarMediaRecordController.recordMediaId("conv-1")))
    }

    @Test
    fun `loaded recipient artwork is only applied to its active recording`() {
        assertTrue(CarMediaRecordController.shouldUpdateRecordingCard(SendStatus.Recording, "recipient", "recipient"))
        assertFalse(CarMediaRecordController.shouldUpdateRecordingCard(SendStatus.Recording, "other", "recipient"))
        assertFalse(CarMediaRecordController.shouldUpdateRecordingCard(SendStatus.Recording, null, "recipient"))
        assertFalse(CarMediaRecordController.shouldUpdateRecordingCard(SendStatus.Sending, "recipient", "recipient"))
    }
}
