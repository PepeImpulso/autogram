package com.autogram.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
