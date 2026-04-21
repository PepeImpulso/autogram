package com.autogram.app.ui.phone

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.autogram.app.infrastructure.platform.LocaleHelper

/**
 * Base class for every phone-UI Activity. Two responsibilities:
 *
 *  1. Override [attachBaseContext] so the user-selected language takes effect
 *     on first draw even after cold start (plain ComponentActivity doesn't
 *     wrap the base context itself).
 *  2. Extend [FragmentActivity] so the biometric prompt — which is shown as
 *     a fragment under the hood — has a host. Compose + Hilt work unchanged.
 */
open class BaseLocaleActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
