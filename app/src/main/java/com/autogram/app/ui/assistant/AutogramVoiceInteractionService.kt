package com.autogram.app.ui.assistant

import android.service.voice.VoiceInteractionService
import timber.log.Timber

/**
 * v1.23.0-rc.4 / issue #63 Phase 1b — `VoiceInteractionService`
 * implementation so Samsung's long-press-home / side-key assistant
 * gesture actually routes to Autogram.
 *
 * Background: declaring `<intent-filter android:name=
 * "android.intent.action.ASSIST" />` on AssistantActivity (rc.1) made
 * Autogram appear in Settings → Default digital assistant app, but the
 * actual gesture trigger goes through this lower-level
 * VoiceInteractionService binding. Apps registered only via the
 * ACTION_ASSIST intent-filter get listed in the picker but never
 * invoked when the user triggers the gesture — the OS routes the
 * gesture to whichever app has a working VoiceInteractionService.
 *
 * This service is a thin shell — it doesn't implement on-device
 * speech recognition or any heavy logic. The actual session work
 * happens in [AutogramVoiceInteractionSessionService] +
 * [AutogramVoiceInteractionSession], which simply launches
 * [AssistantActivity] when the gesture fires. AssistantActivity then
 * runs the existing Phase 2 voice loop / Phase 5 command parser.
 */
class AutogramVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Timber.i("AutogramVoiceInteractionService onReady — Autogram is the active assistant")
    }
}
