package com.autogram.app.infrastructure.review

import android.content.Context
import com.autogram.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the local-only Google Play review session across process recreation. */
@Singleton
class PlayReviewModeStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isActive(): Boolean = BuildConfig.ENABLE_PLAY_REVIEW_DEMO &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    fun activate() {
        if (!BuildConfig.ENABLE_PLAY_REVIEW_DEMO) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    fun deactivate() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE)
            .apply()
    }

    private companion object {
        const val PREFS = "play_review_mode"
        const val KEY_ACTIVE = "active"
    }
}
