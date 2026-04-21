package com.autogram.app.infrastructure.platform

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language that survives cold starts even though our activities extend
 * [androidx.activity.ComponentActivity] (not AppCompatActivity).
 *
 * `AppCompatDelegate.setApplicationLocales` only auto-applies the locale to
 * AppCompatActivity subclasses; on a plain ComponentActivity the saved tag is
 * persisted but never read back into the activity's resources. This helper
 * fills that gap:
 *
 *   1. [saveTag]  — write the chosen tag to a dedicated SharedPreferences file
 *                   (mirrors the DB row so Application and Activity don't need
 *                   to hit Room synchronously before first draw).
 *   2. [wrap]     — wrap an incoming Context with a Configuration whose
 *                   locale = the saved tag. Call from `attachBaseContext`.
 *   3. [savedTag] — read the tag (or "" = follow system).
 */
object LocaleHelper {

    private const val PREFS = "autogram_locale"
    private const val KEY_TAG = "ui_locale_tag"

    fun savedTag(context: Context): String =
        prefs(context).getString(KEY_TAG, "").orEmpty()

    fun saveTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_TAG, tag).apply()
    }

    /** Wrap the base Context for an Activity with the user's chosen locale. */
    fun wrap(context: Context): Context {
        val tag = savedTag(context)
        if (tag.isBlank()) return context   // follow system
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
