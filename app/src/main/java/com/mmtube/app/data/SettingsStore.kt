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
        val COLORBLIND_PROFILE = stringPreferencesKey("colorblind_profile")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val CHIME = booleanPreferencesKey("chime_enabled")
        val LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
        val DISMISSED_ANNOUNCEMENT = stringPreferencesKey("dismissed_announcement")
        val LAST_PLAN_REMINDER = longPreferencesKey("last_plan_reminder")
        val LAST_FORCE_SIGNOUT_SEEN = longPreferencesKey("last_force_signout_seen")
        val CRASH_REPORTS = booleanPreferencesKey("crash_reports")
        val ANNOUNCEMENTS_MUTED = booleanPreferencesKey("announcements_muted")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val LAST_RECAP_AT = longPreferencesKey("last_recap_at")
        val ONBOARDING_SPLASH = booleanPreferencesKey("onboarding_splash_v3")
        val INSTALL_PROMPTED = stringPreferencesKey("install_prompted_version")
        val UPDATE_DISMISSED = stringPreferencesKey("update_dismissed_version")
        val DOWNLOAD_FOLDER = stringPreferencesKey("download_folder")
        val DOWNLOAD_FOLDER_VOLUME = stringPreferencesKey("download_folder_volume")
        val LAST_MODE = stringPreferencesKey("last_mode")
        val TURBO_PROFILE = stringPreferencesKey("turbo_profile")
        val CHUNK_MB = intPreferencesKey("chunk_mb")
        val SPEED_MODE = booleanPreferencesKey("speed_mode")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val ADAPTIVE_PROFILE = booleanPreferencesKey("adaptive_profile")
        val PARALLEL_STREAMS = booleanPreferencesKey("parallel_streams")
        val PRESERVE_AUDIO = booleanPreferencesKey("preserve_audio_dynamics")
        val AUTO_CHUNK = booleanPreferencesKey("auto_chunk")
        val PREFER_MULTICHANNEL = booleanPreferencesKey("prefer_multichannel")
        val AUTO_STORAGE = booleanPreferencesKey("auto_storage")
        val LOUDNESS = intPreferencesKey("loudness_target")
        val VERIFY = booleanPreferencesKey("verify_downloads")
        val THROTTLED_RATE = intPreferencesKey("throttled_rate_kb")
        val CHECK_FORMATS = booleanPreferencesKey("check_formats")
        val BUFFER_KB = intPreferencesKey("buffer_size_kb")
        val FORCE_IPV4 = booleanPreferencesKey("force_ipv4")
        val SORT_FORCE = booleanPreferencesKey("sort_force")
        val EMBED_CHAPTERS = booleanPreferencesKey("embed_chapters")
        val MULTI_VIDEO = booleanPreferencesKey("multi_video")
        val MULTI_AUDIO = booleanPreferencesKey("multi_audio")
        val KEEP_FRAGMENTS = booleanPreferencesKey("keep_fragments")
        val HLS_MPEGTS = booleanPreferencesKey("hls_mpegts")
        val PROXY_URL = stringPreferencesKey("proxy_url")
        val SOURCE_ADDRESS = stringPreferencesKey("source_address")
        val SINGLE_PROCESS_BATCH = booleanPreferencesKey("single_process_batch")
        val NO_WATERMARK = booleanPreferencesKey("no_watermark")
    }

    private val data: Flow<Preferences> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val settings: Flow<SettingsSnapshot> = data.map { prefs -> prefs.toSnapshot() }

    suspend fun snapshot(): SettingsSnapshot = settings.first()

    private fun Preferences.toSnapshot(): SettingsSnapshot {
        // Legacy boolean stays in sync; the string profile is authoritative
        // once the user has picked one of the two new palettes.
        val storedProfile = this[Keys.COLORBLIND_PROFILE]
        val profile = when (storedProfile) {
            "red_green", "blue_yellow", "off" -> storedProfile
            else -> if (this[Keys.COLORBLIND] == true) "red_green" else "off"
        }
        return SettingsSnapshot(
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
        colorblindMode = profile != "off",
        colorblindProfile = profile,
        dynamicColor = this[Keys.DYNAMIC] ?: false,
        chimeEnabled = this[Keys.CHIME] ?: true,
        lastSeenVersion = this[Keys.LAST_SEEN_VERSION] ?: "",
        dismissedAnnouncementId = this[Keys.DISMISSED_ANNOUNCEMENT] ?: "",
        lastPlanReminderAt = this[Keys.LAST_PLAN_REMINDER] ?: 0L,
        lastForceSignOutSeen = this[Keys.LAST_FORCE_SIGNOUT_SEEN] ?: 0L,
        crashReports = this[Keys.CRASH_REPORTS] ?: true,
        announcementsMuted = this[Keys.ANNOUNCEMENTS_MUTED] ?: false,
        reduceMotion = this[Keys.REDUCE_MOTION] ?: false,
        lastRecapAt = this[Keys.LAST_RECAP_AT] ?: 0L,
        onboardingSplashSeen = this[Keys.ONBOARDING_SPLASH] ?: false,
            installPromptedVersion = this[Keys.INSTALL_PROMPTED] ?: "",
            dismissedUpdateVersion = this[Keys.UPDATE_DISMISSED] ?: "",
            downloadFolder = this[Keys.DOWNLOAD_FOLDER] ?: "",
            downloadFolderVolume = this[Keys.DOWNLOAD_FOLDER_VOLUME] ?: "external_primary",
            lastMode = this[Keys.LAST_MODE] ?: "",
            turboProfile = this[Keys.TURBO_PROFILE]?.takeIf {
                it in listOf("standard", "balanced", "aggressive")
            } ?: "balanced",
            chunkMb = this[Keys.CHUNK_MB]?.takeIf { it in listOf(1, 2, 4) } ?: 1,
            speedMode = this[Keys.SPEED_MODE] ?: false,
            audioFormat = this[Keys.AUDIO_FORMAT]?.takeIf {
                it in listOf("mp3", "opus", "flac")
            } ?: "mp3",
            adaptiveProfile = this[Keys.ADAPTIVE_PROFILE] ?: true,
            parallelStreams = this[Keys.PARALLEL_STREAMS] ?: false,
            preserveAudioDynamics = this[Keys.PRESERVE_AUDIO] ?: false,
            autoChunk = this[Keys.AUTO_CHUNK] ?: true,
            preferMultichannel = this[Keys.PREFER_MULTICHANNEL] ?: false,
            autoStorage = this[Keys.AUTO_STORAGE] ?: true,
            loudnessTarget = this[Keys.LOUDNESS]?.takeIf { it in listOf(14, 16, 18) } ?: 16,
            verifyDownloads = this[Keys.VERIFY] ?: true,
            throttledRateKb = this[Keys.THROTTLED_RATE]?.takeIf {
                it in listOf(0, 100, 200, 500)
            } ?: 0,
            checkFormats = this[Keys.CHECK_FORMATS] ?: false,
            bufferSizeKb = this[Keys.BUFFER_KB]?.takeIf {
                it in listOf(0, 16, 64, 256)
            } ?: 0,
            forceIpv4 = this[Keys.FORCE_IPV4] ?: false,
            sortForce = this[Keys.SORT_FORCE] ?: false,
            embedChapters = this[Keys.EMBED_CHAPTERS] ?: false,
            multiVideoStreams = this[Keys.MULTI_VIDEO] ?: false,
            multiAudioStreams = this[Keys.MULTI_AUDIO] ?: false,
            keepFragments = this[Keys.KEEP_FRAGMENTS] ?: false,
            hlsUseMpegts = this[Keys.HLS_MPEGTS] ?: false,
            proxyUrl = this[Keys.PROXY_URL] ?: "",
            sourceAddress = this[Keys.SOURCE_ADDRESS] ?: "",
            singleProcessBatch = this[Keys.SINGLE_PROCESS_BATCH] ?: false,
            noWatermark = this[Keys.NO_WATERMARK] ?: true
        )
    }

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

    suspend fun setColorblindMode(v: Boolean) =
        setColorblindProfile(if (v) "red_green" else "off")

    suspend fun setColorblindProfile(profile: String) {
        val safe = if (profile == "red_green" || profile == "blue_yellow") profile else "off"
        context.settingsDataStore.edit {
            it[Keys.COLORBLIND_PROFILE] = safe
            it[Keys.COLORBLIND] = safe != "off"
        }
    }
    suspend fun setDynamicColor(v: Boolean) { context.settingsDataStore.edit { it[Keys.DYNAMIC] = v } }

    suspend fun setChimeEnabled(v: Boolean) { context.settingsDataStore.edit { it[Keys.CHIME] = v } }

    suspend fun setLastSeenVersion(v: String) { context.settingsDataStore.edit { it[Keys.LAST_SEEN_VERSION] = v } }

    suspend fun setDismissedAnnouncementId(v: String) { context.settingsDataStore.edit { it[Keys.DISMISSED_ANNOUNCEMENT] = v } }

    suspend fun setLastPlanReminderAt(v: Long) { context.settingsDataStore.edit { it[Keys.LAST_PLAN_REMINDER] = v } }

    suspend fun setLastForceSignOutSeen(v: Long) { context.settingsDataStore.edit { it[Keys.LAST_FORCE_SIGNOUT_SEEN] = v } }

    suspend fun setCrashReports(v: Boolean) { context.settingsDataStore.edit { it[Keys.CRASH_REPORTS] = v } }

    suspend fun setAnnouncementsMuted(v: Boolean) { context.settingsDataStore.edit { it[Keys.ANNOUNCEMENTS_MUTED] = v } }

    suspend fun setReduceMotion(v: Boolean) { context.settingsDataStore.edit { it[Keys.REDUCE_MOTION] = v } }

    suspend fun setLastRecapAt(v: Long) { context.settingsDataStore.edit { it[Keys.LAST_RECAP_AT] = v } }

    suspend fun setInstallPromptedVersion(v: String) {
        context.settingsDataStore.edit { it[Keys.INSTALL_PROMPTED] = v }
    }

    suspend fun setUpdateDismissedVersion(v: String) {
        context.settingsDataStore.edit { it[Keys.UPDATE_DISMISSED] = v }
    }

    suspend fun setEmbedChapters(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EMBED_CHAPTERS] = enabled }
    }

    suspend fun setMultiVideoStreams(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MULTI_VIDEO] = enabled }
    }

    suspend fun setMultiAudioStreams(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MULTI_AUDIO] = enabled }
    }

    suspend fun setKeepFragments(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_FRAGMENTS] = enabled }
    }

    suspend fun setHlsMpegts(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HLS_MPEGTS] = enabled }
    }

    suspend fun setProxyUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.PROXY_URL] = value.trim() }
    }

    suspend fun setNoWatermark(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NO_WATERMARK] = enabled }
    }

    suspend fun setSingleProcessBatch(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SINGLE_PROCESS_BATCH] = enabled }
    }

    suspend fun setSourceAddress(value: String) {
        context.settingsDataStore.edit { it[Keys.SOURCE_ADDRESS] = value.trim() }
    }

    suspend fun setSortForce(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SORT_FORCE] = enabled }
    }

    suspend fun setThrottledRate(kb: Int) {
        context.settingsDataStore.edit { it[Keys.THROTTLED_RATE] = kb }
    }

    suspend fun setCheckFormats(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CHECK_FORMATS] = enabled }
    }

    suspend fun setBufferSize(kb: Int) {
        context.settingsDataStore.edit { it[Keys.BUFFER_KB] = kb }
    }

    suspend fun setForceIpv4(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FORCE_IPV4] = enabled }
    }

    suspend fun setLoudnessTarget(value: Int) {
        context.settingsDataStore.edit { it[Keys.LOUDNESS] = value.coerceIn(14, 18) }
    }

    suspend fun setVerifyDownloads(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.VERIFY] = enabled }
    }

    suspend fun setPreferMultichannel(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PREFER_MULTICHANNEL] = enabled }
    }

    suspend fun setAutoStorage(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_STORAGE] = enabled }
    }

    suspend fun setPreserveAudioDynamics(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PRESERVE_AUDIO] = enabled }
    }

    suspend fun setAutoChunk(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CHUNK] = enabled }
    }

    suspend fun setAudioFormat(value: String) {
        context.settingsDataStore.edit { it[Keys.AUDIO_FORMAT] = value }
    }

    suspend fun setAdaptiveProfile(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ADAPTIVE_PROFILE] = enabled }
    }

    suspend fun setParallelStreams(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PARALLEL_STREAMS] = enabled }
    }

    suspend fun setTurboProfile(profile: String) {
        context.settingsDataStore.edit { it[Keys.TURBO_PROFILE] = profile }
    }

    suspend fun setChunkMb(mb: Int) {
        context.settingsDataStore.edit { it[Keys.CHUNK_MB] = mb.coerceIn(1, 4) }
    }

    suspend fun setSpeedMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SPEED_MODE] = enabled }
    }

    suspend fun setLastMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.LAST_MODE] = mode }
    }

    suspend fun setDownloadFolder(uri: String, volume: String = "external_primary") {
        context.settingsDataStore.edit {
            it[Keys.DOWNLOAD_FOLDER] = uri
            it[Keys.DOWNLOAD_FOLDER_VOLUME] = volume
        }
    }

    suspend fun clearAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    suspend fun setOnboardingSeen() {
        context.settingsDataStore.edit { it[Keys.ONBOARDING] = true }
    }

    suspend fun localTotalDownloads(uid: String): Int =
        data.first()[intPreferencesKey("total_$uid")] ?: 0

    suspend fun setLocalTotalDownloads(uid: String, value: Int) {
        context.settingsDataStore.edit { it[intPreferencesKey("total_$uid")] = value }
    }
}
