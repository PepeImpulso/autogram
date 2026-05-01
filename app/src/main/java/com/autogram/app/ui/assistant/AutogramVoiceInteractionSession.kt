package com.autogram.app.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import timber.log.Timber

/**
 * The visible side of the OS-triggered assistant session. We don't
 * draw any custom overlay UI — we just immediately launch
 * [AssistantActivity] (the same UI the user already knows) and hide
 * the session. This keeps the OS flow consistent: gesture → session →
 * Autogram's own activity, with no parallel overlay surface to
 * maintain.
 *
 * We intentionally don't use the inherited content-view machinery —
 * it's heavy and useful mainly for "always-listening" assistants
 * (Google) that overlay UI on top of whatever the user is doing.
 * Autogram's flow is task-oriented (open → record → close), so an
 * Activity is the right surface.
 */
class AutogramVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Timber.i("AutogramVoiceInteractionSession onShow — launching AssistantActivity")
        val intent = Intent(context, AssistantActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startAssistantActivity(intent)
        // Dismiss the session immediately — the activity owns the UX
        // from here on. Without this the OS would keep the session in
        // a "showing" state, blocking re-invocations.
        hide()
    }
}
