package com.autogram.app.ui.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Spawns a new [AutogramVoiceInteractionSession] when the OS asks for
 * one — i.e., when the user triggers the assistant gesture. The
 * session itself just launches [AssistantActivity] so the existing
 * Phase 2 / Phase 5 flow takes over.
 */
class AutogramVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AutogramVoiceInteractionSession(this)
    }
}
