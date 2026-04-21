package com.autogram.app.domain.statemachine

import com.autogram.app.domain.model.MessageState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageStateMachine @Inject constructor() {

    private val transitions = mapOf(
        MessageState.IDLE to setOf(MessageState.RECORDING, MessageState.RECEIVED),
        MessageState.RECORDING to setOf(MessageState.RECORDED),
        MessageState.RECORDED to setOf(MessageState.VALIDATING),
        MessageState.VALIDATING to setOf(MessageState.DISCARDED, MessageState.QUEUED, MessageState.QUEUED_OFFLINE),
        MessageState.DISCARDED to setOf(MessageState.IDLE),
        MessageState.QUEUED to setOf(MessageState.SENDING, MessageState.QUEUED_OFFLINE),
        MessageState.QUEUED_OFFLINE to setOf(MessageState.SENDING, MessageState.PERMANENTLY_FAILED),
        MessageState.SENDING to setOf(MessageState.SENT, MessageState.FAILED),
        MessageState.FAILED to setOf(MessageState.QUEUED, MessageState.QUEUED_OFFLINE, MessageState.PERMANENTLY_FAILED),
        MessageState.PERMANENTLY_FAILED to emptySet(),
        MessageState.SENT to emptySet(),
        MessageState.RECEIVED to setOf(MessageState.PLAYING, MessageState.PENDING_PLAYBACK, MessageState.ARCHIVED),
        MessageState.PENDING_PLAYBACK to setOf(MessageState.PLAYING),
        MessageState.PLAYING to setOf(MessageState.PLAYED, MessageState.PAUSED),
        MessageState.PAUSED to setOf(MessageState.PLAYING),
        MessageState.PLAYED to setOf(MessageState.ARCHIVED),
        MessageState.ARCHIVED to emptySet()
    )

    fun canTransition(from: MessageState, to: MessageState): Boolean {
        return transitions[from]?.contains(to) == true
    }

    fun transition(current: MessageState, target: MessageState): Result<MessageState> {
        return if (canTransition(current, target)) {
            Result.success(target)
        } else {
            Result.failure(IllegalStateException("Invalid transition: $current → $target"))
        }
    }

    fun validNextStates(current: MessageState): Set<MessageState> {
        return transitions[current] ?: emptySet()
    }
}
