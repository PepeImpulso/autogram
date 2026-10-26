package com.autogram.app.service

import android.content.Context

/**
 * Remembers how many custom browse actions (in-grid buttons) Android Auto
 * said it can draw on its last connection, so Settings can tell the user
 * whether their car supports the experimental grid buttons at all.
 */
object CarBrowseActionSupport {
    const val UNKNOWN = -1

    fun record(context: Context, limit: Int) {
        prefs(context).edit().putInt(KEY_LIMIT, limit).apply()
    }

    fun lastLimit(context: Context): Int = prefs(context).getInt(KEY_LIMIT, UNKNOWN)

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private const val FILE_NAME = "car_browse_actions"
    private const val KEY_LIMIT = "last_limit"
}
