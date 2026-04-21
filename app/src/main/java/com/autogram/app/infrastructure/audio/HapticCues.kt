package com.autogram.app.infrastructure.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.autogram.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vibration counterpart of [RecordingSounds]. Respects
 * `AppSettings.hapticEnabled` — when off we skip the vibration entirely.
 *
 * Two distinguishable patterns so the driver can tell start from stop without
 * looking at the screen:
 *   - start: single short tick
 *   - end:   longer pulse + short tick double-tap
 */
@Singleton
class HapticCues @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    suspend fun playStart() = vibrate(VibrationEffect.EFFECT_TICK, 40L)
    suspend fun playEnd() = vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK, 100L)

    private suspend fun vibrate(predefinedEffect: Int, fallbackMs: Long) {
        if (!settingsRepository.get().hapticEnabled) return
        try {
            val vibrator = vibrator() ?: return
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(predefinedEffect)
            } else {
                VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Timber.w(e, "HapticCues.vibrate failed")
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
