package com.autogram.app.ui.auto

import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase.SendStatus

/**
 * Pure tap-to-record / tap-to-send decisions for the Android Auto screen
 * (#104). Mirrors the phone's RecordCircle: tap = start, tap the same
 * contact again = stop and send. Kept free of Car App Library types so it
 * can be unit tested on the JVM.
 */
object CarRecordToggle {

    enum class TapAction { START, STOP_AND_SEND, IGNORE }

    /** Visual state of one contact tile / row. */
    enum class TileState { IDLE, RECORDING, SENDING, SENT, FAILED }

    /**
     * What a tap on the contact [tappedId] should do. A tap on another
     * contact while one is recording is ignored, like on the phone, so a
     * stray tap never sends the note to the wrong person. Taps during the
     * send pipeline are ignored too; [SendVoiceMtprotoUseCase.startRecording]
     * would refuse them anyway.
     */
    fun onTap(status: SendStatus, activeTargetId: String?, tappedId: String?): TapAction =
        when (status) {
            SendStatus.Recording ->
                if (activeTargetId == tappedId) TapAction.STOP_AND_SEND else TapAction.IGNORE
            SendStatus.Idle,
            is SendStatus.Sent,
            is SendStatus.Error,
            is SendStatus.Discarded,
            SendStatus.QueuedOffline -> TapAction.START
            SendStatus.Validating,
            SendStatus.Sending,
            is SendStatus.PreviewReady,
            SendStatus.PreviewPlaying -> TapAction.IGNORE
        }

    /**
     * State to draw on the tile for [tileId]. The use case clears
     * activeTargetId once a send finishes, so terminal states are matched
     * against [lastTargetId], the contact the car last started recording
     * for.
     */
    fun tileState(
        status: SendStatus,
        activeTargetId: String?,
        lastTargetId: String?,
        tileId: String?
    ): TileState = when (status) {
        SendStatus.Recording ->
            if (activeTargetId == tileId) TileState.RECORDING else TileState.IDLE
        SendStatus.Validating,
        SendStatus.Sending ->
            if (lastTargetId == tileId) TileState.SENDING else TileState.IDLE
        is SendStatus.Sent,
        SendStatus.QueuedOffline ->
            if (lastTargetId == tileId) TileState.SENT else TileState.IDLE
        is SendStatus.Error,
        is SendStatus.Discarded ->
            if (lastTargetId == tileId) TileState.FAILED else TileState.IDLE
        SendStatus.Idle,
        is SendStatus.PreviewReady,
        SendStatus.PreviewPlaying -> TileState.IDLE
    }
}
