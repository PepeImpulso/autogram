package com.autogram.app.ui.auto

import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase.SendStatus
import com.autogram.app.ui.auto.CarRecordToggle.TapAction
import com.autogram.app.ui.auto.CarRecordToggle.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

class CarRecordToggleTest {

    @Test
    fun `tap starts recording from idle and terminal states`() {
        val startable = listOf(
            SendStatus.Idle,
            SendStatus.Sent(1L),
            SendStatus.Error("x"),
            SendStatus.Discarded("empty"),
            SendStatus.QueuedOffline
        )
        for (status in startable) {
            assertEquals(status.toString(), TapAction.START, CarRecordToggle.onTap(status, null, "a"))
        }
    }

    @Test
    fun `tap on the recording contact stops and sends`() {
        assertEquals(TapAction.STOP_AND_SEND, CarRecordToggle.onTap(SendStatus.Recording, "a", "a"))
    }

    @Test
    fun `untargeted recording is stopped by the untargeted tile`() {
        assertEquals(TapAction.STOP_AND_SEND, CarRecordToggle.onTap(SendStatus.Recording, null, null))
    }

    @Test
    fun `tap on another contact while recording is ignored`() {
        assertEquals(TapAction.IGNORE, CarRecordToggle.onTap(SendStatus.Recording, "a", "b"))
    }

    @Test
    fun `taps during the send pipeline are ignored`() {
        val busy = listOf(
            SendStatus.Validating,
            SendStatus.Sending,
            SendStatus.PreviewReady(1000L),
            SendStatus.PreviewPlaying
        )
        for (status in busy) {
            assertEquals(status.toString(), TapAction.IGNORE, CarRecordToggle.onTap(status, "a", "a"))
        }
    }

    @Test
    fun `only the active tile shows recording`() {
        assertEquals(TileState.RECORDING, CarRecordToggle.tileState(SendStatus.Recording, "a", "a", "a"))
        assertEquals(TileState.IDLE, CarRecordToggle.tileState(SendStatus.Recording, "a", "a", "b"))
    }

    @Test
    fun `send progress and result follow the last target`() {
        // activeTargetId is already cleared by the use case after a send.
        assertEquals(TileState.SENDING, CarRecordToggle.tileState(SendStatus.Sending, "a", "a", "a"))
        assertEquals(TileState.SENT, CarRecordToggle.tileState(SendStatus.Sent(1L), null, "a", "a"))
        assertEquals(TileState.SENT, CarRecordToggle.tileState(SendStatus.QueuedOffline, null, "a", "a"))
        assertEquals(TileState.FAILED, CarRecordToggle.tileState(SendStatus.Error("x"), null, "a", "a"))
        assertEquals(TileState.FAILED, CarRecordToggle.tileState(SendStatus.Discarded("x"), null, "a", "a"))
        assertEquals(TileState.IDLE, CarRecordToggle.tileState(SendStatus.Sent(1L), null, "a", "b"))
    }

    @Test
    fun `idle shows every tile idle`() {
        assertEquals(TileState.IDLE, CarRecordToggle.tileState(SendStatus.Idle, null, "a", "a"))
    }
}
