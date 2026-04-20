package com.autogram.app.ui.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autogram.app.service.AutogramPollingService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stop the polling service while the wizard runs — otherwise its
        // long-poll on getUpdates would consume the user's "send a test
        // message" before detectChat() can see it (HTTP 409 / empty queue).
        // Idempotent: noop if the service isn't running.
        try {
            startService(Intent(this, AutogramPollingService::class.java).apply {
                action = AutogramPollingService.ACTION_STOP
            })
        } catch (e: Exception) {
            Timber.w(e, "Onboarding: could not stop polling service (may already be stopped)")
        }

        setContent {
            AutogramTheme {
                OnboardingScreen(onFinished = {
                    // Re-launch MainActivity so the user lands on the app's
                    // home screen instead of the Android launcher. MainActivity
                    // was finished (FLAG_ACTIVITY_NO_HISTORY) when it routed
                    // here, so there's nothing in the back stack to return to.
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                })
            }
        }
    }
}
