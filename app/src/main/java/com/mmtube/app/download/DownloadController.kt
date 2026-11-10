package com.mmtube.app.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.EngineManager
import com.mmtube.app.data.ErrorKind
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.data.HistoryStore
import com.mmtube.app.data.MediaExporter
import com.mmtube.app.data.MmtubeException
import com.mmtube.app.data.Platform
import com.mmtube.app.data.QuotaManager
import com.mmtube.app.data.SettingsStore
import com.mmtube.app.util.Formatters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DownloadRequest(
    val url: String,
    val title: String,
    val platform: Platform,
    val mode: DownloadMode,
    val thumbnail: String?,
    val durationSeconds: Int,
    val qualityLabel: String
)

class DownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: EngineManager,
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val quotaManager: QuotaManager,
    private val exporter: MediaExporter
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null
    private var processId: String? = null
    private var currentRequest: DownloadRequest? = null
    private var lastProgressUpdate = 0L

    fun start(request: DownloadRequest) {
        if (job?.isActive == true) return
        currentRequest = request
        _state.value = DownloadState.Preparing(request.title, request.mode)
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                if (!engine.ensureReady()) {
                    throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
                }
                val directory = downloadDirectory().apply { mkdirs() }
                val turbo = settingsStore.snapshot().turbo
                val file = downloadWithFallback(request, directory, turbo, startedAt)
                _state.value = DownloadState.Saving(request.title, request.mode)
                val exported = exporter.export(file, request.mode)
                val item = HistoryItem(
                    id = UUID.randomUUID().toString(),
                    title = request.title,
                    platform = request.platform.name,
                    quality = request.qualityLabel,
                    sizeBytes = exported.sizeBytes,
                    mimeType = exported.mimeType,
                    uri = exported.uri.toString(),
                    localPath = exported.localPath,
                    thumbnail = request.thumbnail,
                    durationSeconds = request.durationSeconds,
                    createdAt = System.currentTimeMillis(),
                    mode = request.mode.key,
                    savedToGallery = exported.savedToGallery
                )
                runCatching { historyStore.add(item) }
                    .onFailure { Log.w(TAG, "Could not save history", it) }
                runCatching { quotaManager.consume() }
                    .onFailure { Log.w(TAG, "Could not consume quota", it) }
                _state.value = DownloadState.Completed(item)
            } catch (cancelled: CancellationException) {
                if (_state.value !is DownloadState.Cancelled) {
                    _state.value = DownloadState.Cancelled
                }
            } catch (cancelled: YoutubeDL.CanceledException) {
                _state.value = DownloadState.Cancelled
            } catch (error: Throwable) {
                if (_state.value is DownloadState.Cancelled) return@launch
                Log.w(TAG, "Download failed", error)
                val kind = (error as? MmtubeException)?.kind ?: ErrorKind.DOWNLOAD
                _state.value = DownloadState.Failed(
                    title = request.title,
                    kind = kind,
                    detail = error.message
                )
            } finally {
                processId = null
                currentRequest = null
            }
        }
    }

    fun cancel() {
        val id = processId
        if (id != null) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
        job?.cancel()
        _state.value = DownloadState.Cancelled
    }

    fun reset() {
        if (_state.value !is DownloadState.Running &&
            _state.value !is DownloadState.Preparing &&
            _state.value !is DownloadState.Merging &&
            _state.value !is DownloadState.Saving
        ) {
            _state.value = DownloadState.Idle
        }
    }

    fun isActive(): Boolean = job?.isActive == true

    private suspend fun downloadWithFallback(
        request: DownloadRequest,
        directory: File,
        turbo: Boolean,
        startedAt: Long
    ): File {
        val firstId = "download-${System.nanoTime()}"
        processId = firstId
        try {
            execute(request, directory, firstId, turbo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!turbo) throw error
            Log.w(TAG, "Turbo download failed; retrying with the native downloader", error)
            _state.value = DownloadState.Running(
                title = request.title,
                mode = request.mode,
                progress = 0f,
                etaSeconds = -1L,
                speedBytes = 0L,
                totalBytes = 0L,
                line = "Turbo failed — retrying with the native downloader…"
            )
            val retryId = "download-${System.nanoTime()}"
            processId = retryId
            execute(request, directory, retryId, false)
        }
        return findOutput(directory, request.mode, startedAt)
            ?: throw MmtubeException(ErrorKind.DOWNLOAD, "Downloaded file was not found")
    }

    private suspend fun execute(
        request: DownloadRequest,
        directory: File,
        processId: String,
        turbo: Boolean
    ) {
        val ytRequest = buildRequest(request, directory, turbo)
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(
                ytRequest,
                processId = processId,
                redirectErrorStream = false
            ) { progress, eta, rawLine ->
                val line = rawLine
                    .replace(Regex("\u001B\\[[;\\d]*m"), "")
                    .trim()
                val current = _state.value
                if (current !is DownloadState.Cancelled) {
                    if (line.contains("[Merger]", true) ||
                        line.contains("Merging formats", true) ||
                        line.contains("Merging", true)
                    ) {
                        _state.value = DownloadState.Merging(request.title, request.mode)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 140L || progress >= 100f) {
                            lastProgressUpdate = now
                            val previous = current as? DownloadState.Running
                            val speed = Formatters.parseSpeed(line)
                            val total = Formatters.parseTotal(line).takeIf { it > 0 }
                                ?: previous?.totalBytes ?: 0L
                            _state.value = DownloadState.Running(
                                title = request.title,
                                mode = request.mode,
                                progress = progress.coerceIn(0f, 100f),
                                etaSeconds = eta,
                                speedBytes = speed,
                                totalBytes = total,
                                line = line.take(180)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildRequest(
        request: DownloadRequest,
        directory: File,
        turbo: Boolean
    ): YoutubeDLRequest {
        val output = File(directory, "%(title).90B [%(id)s].%(ext)s").absolutePath
        return YoutubeDLRequest(request.url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-update")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--force-overwrites")
            addOption("--ignore-config")
            addOption("--socket-timeout", "25")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--extractor-retries", "3")
            addOption("--concurrent-fragments", "24")
            addOption("--embed-metadata")
            addOption("-o", output)
            when (request.mode) {
                DownloadMode.MAX -> {
                    addOption("-f", "bestvideo+bestaudio/best")
                    // True maximum quality: highest resolution, then highest
                    // bitrate — not codec preference. Picks the 18.5 Mbps VP9
                    // 2160p stream instead of the smaller AV1 one.
                    addOption("-S", "res:desc,fps:desc,tbr:desc")
                    addOption("--merge-output-format", "mp4")
                }

                DownloadMode.P1080 -> {
                    addOption("-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best")
                    addOption("-S", "res:desc,fps:desc,tbr:desc")
                    addOption("--merge-output-format", "mp4")
                }

                DownloadMode.P720 -> {
                    addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]/best")
                    addOption("-S", "res:desc,fps:desc,tbr:desc")
                    addOption("--merge-output-format", "mp4")
                }

                DownloadMode.MP3 -> {
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
            }
            if (turbo) {
                addOption("--downloader", "libaria2c.so")
                addOption("--external-downloader-args", "aria2c:-x24 -s24 -k1M")
            }
        }
    }

    private fun findOutput(directory: File, mode: DownloadMode, startedAt: Long): File? {
        val extensions = if (mode == DownloadMode.MP3) {
            setOf("mp3", "m4a", "opus", "aac", "wav")
        } else {
            setOf("mp4", "mkv", "webm", "mov")
        }
        val files = directory.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.filter { it.extension.lowercase() in extensions }
            ?.filterNot {
                it.name.endsWith(".part") ||
                    it.name.endsWith(".ytdl") ||
                    it.name.endsWith(".aria2") ||
                    it.name.endsWith(".temp")
            }
            .orEmpty()
        if (files.isEmpty()) return null
        val fresh = files.filter { it.lastModified() >= startedAt - 10_000L }
        return (fresh.ifEmpty { files }).maxWithOrNull(
            compareBy<File> { it.lastModified() }.thenBy { it.length() }
        )
    }

    private fun downloadDirectory(): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(external, "MMTube")
    }

    companion object {
        private const val TAG = "DownloadController"
    }
}
