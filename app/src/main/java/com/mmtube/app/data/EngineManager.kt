package com.mmtube.app.data

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class EngineManager(private val context: Context) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch { ensureReady() }
    }

    suspend fun ensureReady(): Boolean {
        if (_state.value is EngineState.Ready) return true
        return mutex.withLock {
            if (_state.value is EngineState.Ready) return@withLock true
            _state.value = EngineState.Initializing
            try {
                // If the pinned yt-dlp just changed (app update), drop the previously
                // extracted binary so init() re-extracts the new one from res/raw.
                refreshPinnedEngineIfNeeded()
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                    runCatching { Aria2c.getInstance().init(context) }
                        .onFailure { Log.w(TAG, "aria2c init failed; turbo will fall back", it) }
                }
                val version = readVersion()
                _state.value = EngineState.Ready(version)
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Engine init failed", error)
                _state.value = EngineState.Failed(error.message ?: "Engine initialization failed")
                false
            }
        }
    }

    suspend fun retry(): Boolean {
        if (_state.value is EngineState.Failed) _state.value = EngineState.Idle
        return ensureReady()
    }

    suspend fun updateEngine(): Result<String> {
        if (!ensureReady()) {
            return Result.failure(MmtubeException(ErrorKind.ENGINE, "Engine not ready"))
        }
        return mutex.withLock {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Latest stable from GitHub (currently 2026.10.26).
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                }
                val version = readVersion() ?: "updated"
                _state.value = EngineState.Ready(version)
                version
            }.recoverCatching { error ->
                Log.w(TAG, "Engine update failed", error)
                throw MmtubeException(ErrorKind.ENGINE, error.message, error)
            }
        }
    }

    suspend fun autoUpdateIfDue(settings: SettingsSnapshot, settingsStore: SettingsStore) {
        if (!settings.autoEngineUpdate) return
        val now = System.currentTimeMillis()
        val due = now - settings.lastEngineUpdate > TimeUnit.DAYS.toMillis(3)
        if (!due) return
        val result = updateEngine()
        if (result.isSuccess) {
            settingsStore.setLastEngineUpdate(now)
        }
    }

    fun currentVersion(): String? = (state.value as? EngineState.Ready)?.version

    private fun refreshPinnedEngineIfNeeded() {
        val prefs = context.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_YTDLP_PINNED, null) == PINNED_YTDLP) return
        runCatching {
            val ytdlpDir = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")
            if (ytdlpDir.exists()) {
                Log.i(TAG, "Pinned yt-dlp changed to $PINNED_YTDLP; re-extracting")
                ytdlpDir.deleteRecursively()
            }
            prefs.edit().putString(KEY_YTDLP_PINNED, PINNED_YTDLP).apply()
        }.onFailure {
            Log.w(TAG, "Could not refresh pinned yt-dlp", it)
        }
    }

    private fun readVersion(): String? {
        return runCatching {
            YoutubeDL.versionName(context) ?: YoutubeDL.version(context)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "EngineManager"

        // yt-dlp 2026.10.26 bundled in the APK; bump together with res/raw/ytdlp.
        const val PINNED_YTDLP = "2026.10.26"

        private const val ENGINE_PREFS = "mmtube_engine"
        private const val KEY_YTDLP_PINNED = "mmtube_ytdlp_pinned"
    }
}
