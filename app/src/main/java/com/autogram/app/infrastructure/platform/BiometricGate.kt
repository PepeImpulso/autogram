package com.autogram.app.infrastructure.platform

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import timber.log.Timber

/**
 * Thin wrapper around [BiometricPrompt]. Not a Hilt singleton — stateless and
 * cheap to construct on demand.
 *
 * Grace window (per-process) stops the prompt re-appearing every time the user
 * bounces between Activities inside Autogram: once `markUnlocked()` is called
 * we accept any `ensureUnlocked()` for [GRACE_MS] without prompting again.
 */
object BiometricGate {

    private const val GRACE_MS = 30_000L
    @Volatile private var lastUnlockedAt = 0L

    fun isSupported(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun recentlyUnlocked(): Boolean =
        System.currentTimeMillis() - lastUnlockedAt < GRACE_MS

    fun markUnlocked() {
        lastUnlockedAt = System.currentTimeMillis()
    }

    fun ensureUnlocked(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit,
        onFailed: () -> Unit
    ) {
        if (recentlyUnlocked()) {
            onAuthenticated()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    markUnlocked()
                    onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or system error — don't grant access.
                    Timber.w("BiometricPrompt error $errorCode: $errString")
                    onFailed()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}
