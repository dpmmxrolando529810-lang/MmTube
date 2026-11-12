package com.mmtube.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "mmtube_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val TURBO = booleanPreferencesKey("turbo")
        val AUTO_ENGINE = booleanPreferencesKey("auto_engine")
        val AUTO_APP = booleanPreferencesKey("auto_app")
        val TERMS = booleanPreferencesKey("terms_accepted")
        val GUEST_NEXT = longPreferencesKey("guest_next_allowed")
        val GUEST_TOTAL = intPreferencesKey("guest_total")
        val LAST_ENGINE_UPDATE = longPreferencesKey("last_engine_update")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val LANGUAGE = stringPreferencesKey("language")
        val ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val COLORBLIND = booleanPreferencesKey("colorblind_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val CHIME = booleanPreferencesKey("chime_enabled")
        val LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
        val DISMISSED_ANNOUNCEMENT = stringPreferencesKey("dismissed_announcement")
        val LAST_PLAN_REMINDER = longPreferencesKey("last_plan_reminder")
    }

    private val data: Flow<Preferences> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val settings: Flow<SettingsSnapshot> = data.map { prefs -> prefs.toSnapshot() }

    suspend fun snapshot(): SettingsSnapshot = settings.first()

    private fun Preferences.toSnapshot(): SettingsSnapshot = SettingsSnapshot(
        theme = ThemeMode.fromKey(this[Keys.THEME]),
        turbo = this[Keys.TURBO] ?: true,
        autoEngineUpdate = this[Keys.AUTO_ENGINE] ?: true,
        autoAppUpdate = this[Keys.AUTO_APP] ?: true,
        acceptedTerms = this[Keys.TERMS] ?: false,
        guestNextAllowedAt = this[Keys.GUEST_NEXT] ?: 0L,
        guestTotalDownloads = this[Keys.GUEST_TOTAL] ?: 0,
        lastEngineUpdate = this[Keys.LAST_ENGINE_UPDATE] ?: 0L,
        lastUpdateCheck = this[Keys.LAST_UPDATE_CHECK] ?: 0L,
        language = this[Keys.LANGUAGE] ?: "",
        onboardingSeen = this[Keys.ONBOARDING] ?: false,
        colorblindMode = this[Keys.COLORBLIND] ?: false,
        dynamicColor = this[Keys.DYNAMIC] ?: false,
        chimeEnabled = this[Keys.CHIME] ?: true,
        lastSeenVersion = this[Keys.LAST_SEEN_VERSION] ?: "",
        dismissedAnnouncementId = this[Keys.DISMISSED_ANNOUNCEMENT] ?: "",
        lastPlanReminderAt = this[Keys.LAST_PLAN_REMINDER] ?: 0L
    )

    suspend fun setTheme(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setTurbo(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.TURBO] = enabled }
    }

    suspend fun setAutoEngineUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_ENGINE] = enabled }
    }

    suspend fun setAutoAppUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_APP] = enabled }
    }

    suspend fun acceptTerms() {
        context.settingsDataStore.edit { it[Keys.TERMS] = true }
    }

    suspend fun recordGuestDownload(nextAllowedAt: Long) {
        context.settingsDataStore.edit {
            it[Keys.GUEST_NEXT] = nextAllowedAt
            it[Keys.GUEST_TOTAL] = (it[Keys.GUEST_TOTAL] ?: 0) + 1
        }
    }

    suspend fun setLastEngineUpdate(value: Long) {
        context.settingsDataStore.edit { it[Keys.LAST_ENGINE_UPDATE] = value }
    }

    suspend fun setLastUpdateCheck(value: Long) {
        context.settingsDataStore.edit { it[Keys.LAST_UPDATE_CHECK] = value }
    }

    suspend fun setLanguage(tag: String) {
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    suspend fun localNextAllowed(uid: String): Long? =
        data.first()[longPreferencesKey("next_allowed_$uid")]

    suspend fun setLocalNextAllowed(uid: String, value: Long) {
        context.settingsDataStore.edit { it[longPreferencesKey("next_allowed_$uid")] = value }
    }

    suspend fun setColorblindMode(v: Boolean) { context.settingsDataStore.edit { it[Keys.COLORBLIND] = v } }
    suspend fun setDynamicColor(v: Boolean) { context.settingsDataStore.edit { it[Keys.DYNAMIC] = v } }

    suspend fun setChimeEnabled(v: Boolean) { context.settingsDataStore.edit { it[Keys.CHIME] = v } }

    suspend fun setLastSeenVersion(v: String) { context.settingsDataStore.edit { it[Keys.LAST_SEEN_VERSION] = v } }

    suspend fun setDismissedAnnouncementId(v: String) { context.settingsDataStore.edit { it[Keys.DISMISSED_ANNOUNCEMENT] = v } }

    suspend fun setLastPlanReminderAt(v: Long) { context.settingsDataStore.edit { it[Keys.LAST_PLAN_REMINDER] = v } }

    suspend fun setOnboardingSeen() {
        context.settingsDataStore.edit { it[Keys.ONBOARDING] = true }
    }

    suspend fun localTotalDownloads(uid: String): Int =
        data.first()[intPreferencesKey("total_$uid")] ?: 0

    suspend fun setLocalTotalDownloads(uid: String, value: Int) {
        context.settingsDataStore.edit { it[intPreferencesKey("total_$uid")] = value }
    }
}
