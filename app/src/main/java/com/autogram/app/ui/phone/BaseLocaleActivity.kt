package com.autogram.app.ui.phone

import android.content.Context
import androidx.activity.ComponentActivity
import com.autogram.app.infrastructure.platform.LocaleHelper

/**
 * Base class for every phone-UI Activity. Overrides [attachBaseContext] so the
 * user-selected language takes effect on first draw even after cold start —
 * something plain ComponentActivity doesn't do on its own.
 *
 * Kept open so Hilt's `@AndroidEntryPoint` subclasses can plug in normally.
 */
open class BaseLocaleActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
