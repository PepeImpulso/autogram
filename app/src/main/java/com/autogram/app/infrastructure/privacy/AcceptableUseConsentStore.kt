package com.autogram.app.infrastructure.privacy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stores acceptance of the current messaging rules on this device. */
@Singleton
class AcceptableUseConsentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isAccepted(): Boolean = prefs().getInt(KEY_VERSION, 0) >= CURRENT_VERSION

    fun accept() {
        prefs().edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
    }

    private fun prefs() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        const val CURRENT_VERSION: Int = 1
        private const val FILE_NAME = "acceptable_use_consent"
        private const val KEY_VERSION = "accepted_version"
    }
}
