package com.mmtube.app.util

import android.content.Context

/** Small synchronous mirror of engine-related settings for native probe calls. */
object EngineOptions {

    private const val PREFS = "mmtube_engine_options"
    private const val KEY_TIKTOK_PROFILE = "tiktok_profile"

    fun tiktokProfile(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TIKTOK_PROFILE, 0)

    fun rememberTiktokProfile(context: Context, profile: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TIKTOK_PROFILE, profile).apply()
    }
}
