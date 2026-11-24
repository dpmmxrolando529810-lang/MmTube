package com.mmtube.app.util

import android.content.Context

/** Remembers the format a user picked for a particular video. */
object FormatMemory {

    private const val PREFS = "mmtube_format_memory"
    private const val MAX_ENTRIES = 120

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): String? = prefs(context).getString(key, null)

    fun put(context: Context, key: String, format: String) {
        if (key.isBlank() || format.isBlank()) return
        prefs(context).edit().putString(key, format).apply()
    }
}
