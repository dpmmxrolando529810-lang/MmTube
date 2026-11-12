package com.mmtube.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mmtube.app.data.AccountUi
import com.mmtube.app.data.AuthState
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.EngineState
import com.mmtube.app.data.ErrorKind
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.data.MmtubeException
import com.mmtube.app.data.Platform
import com.mmtube.app.data.ProbeResult
import com.mmtube.app.data.ProbeUi
import com.mmtube.app.data.QuotaState
import com.mmtube.app.data.SettingsSnapshot
import com.mmtube.app.data.ThemeMode
import com.mmtube.app.data.UpdateUi
import com.mmtube.app.data.Plans
import com.mmtube.app.download.DownloadRequest
import com.mmtube.app.download.DownloadService
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.UrlUtils
import com.mmtube.app.util.copyText
import com.mmtube.app.util.openFile
import com.mmtube.app.util.openUrl
import com.mmtube.app.util.sha1Fingerprint
import com.mmtube.app.util.shareFile
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab { HOME, DOWNLOADS, ACCOUNT }

data class MainUiState(
    val link: String = "",
    val detected: Platform? = null,
    val probe: ProbeUi = ProbeUi.Idle,
    val mode: DownloadMode = DownloadMode.MAX,
    val download: DownloadState = DownloadState.Idle,
    val engine: EngineState = EngineState.Idle,
    val account: AccountUi = AccountUi(),
    val quota: QuotaState? = null,
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val history: List<HistoryItem> = emptyList(),
    val update: UpdateUi = UpdateUi(),
    val engineUpdating: Boolean = false,
    val accountBusy: Boolean = false,
    val sha1: String = "",
    val engineDiagnostics: String? = null,
    val pendingAutoDownload: Boolean = false,
    val queuedTitle: String? = null
) {
    val termsAccepted: Boolean get() = settings.acceptedTerms
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class OpenTab(val tab: MainTab) : UiEvent
    data object Exit : UiEvent
}

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val context = container.appContext

    private val _ui = MutableStateFlow(MainUiState(sha1 = context.sha1Fingerprint()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            container.settings.settings.collect { snapshot ->
                _ui.update { it.copy(settings = snapshot) }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr?.createNotificationChannel(
                NotificationChannel(
                    QUEUE_NOTIFICATION_CHANNEL,
                    "MMTube queue",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        viewModelScope.launch {
            container.engine.state.collect { engine ->
                _ui.update { it.copy(engine = engine) }
            }
        }
        viewModelScope.launch {
            container.history.items.collect { items ->
                _ui.update { it.copy(history = items) }
            }
        }
        viewModelScope.launch {
            container.account.ui.collect { account ->
                _ui.update { it.copy(account = account) }
                refreshQuota()
            }
        }
        viewModelScope.launch {
            container.downloads.state.collect { state ->
                _ui.update { it.copy(download = state, queuedTitle = container.downloads.queuedRequest?.title) }
                updateQueueNotification(container.downloads.queuedRequest?.title)
                when (state) {
                    is DownloadState.Completed -> {
                        refreshQuota()
                        val message = if (state.item.savedToGallery) {
                            context.getString(R.string.download_saved_gallery)
                        } else {
                            context.getString(R.string.download_complete_message)
                        }
                        event(UiEvent.Message(message))
                    }
                    is DownloadState.Failed -> {
                        appendDiagnosticLog("download failed: kind=" + state.kind + " detail=" + state.detail)
                        event(UiEvent.Message(errorText(state.kind)))
                    }
                    DownloadState.Cancelled -> {
                        event(UiEvent.Message(context.getString(R.string.download_cancelled_message)))
                    }
                    else -> Unit
                }
            }
        }
        maybeCheckForUpdate(silent = true)
    }

    // ---------- Links & probing ----------

    fun onLinkChange(value: String) {
        val detected = UrlUtils.detectPlatform(value)
        _ui.update { current ->
            val success = current.probe as? ProbeUi.Success
            val reset = success == null || success.result.url.isBlank() || value.trim() != success.result.url
            current.copy(
                link = value,
                detected = detected,
                probe = if (reset) ProbeUi.Idle else current.probe
            )
        }
    }

    fun pasteLink(text: String) {
        pasteLink(text, autoStart = false)
    }

    /** Used by the YouTube/Facebook share-sheet: analyze then auto-start the download. */
    fun pasteLink(text: String, autoStart: Boolean) {
        val url = UrlUtils.extractUrl(text) ?: return
        val detected = UrlUtils.detectPlatform(url)
        _ui.update { it.copy(link = url, detected = detected, probe = ProbeUi.Idle, pendingAutoDownload = autoStart) }
        event(UiEvent.Message(context.getString(R.string.link_pasted)))
        event(UiEvent.OpenTab(MainTab.HOME))
        analyze()
    }

    fun requestOpenDownloads() {
        event(UiEvent.OpenTab(MainTab.DOWNLOADS))
    }

    fun clearLink() {
        probeJob?.cancel()
        _ui.update { it.copy(link = "", detected = null, probe = ProbeUi.Idle, mode = DownloadMode.MAX) }
    }

    fun analyze() {
        val raw = _ui.value.link.trim()
        if (UrlUtils.normalize(raw) == null) {
            event(UiEvent.Message(context.getString(R.string.error_invalid_link)))
            return
        }
        probeJob?.cancel()
        _ui.update { it.copy(probe = ProbeUi.Loading(raw)) }
        probeJob = viewModelScope.launch {
            container.media.probe(raw)
                .onSuccess { result ->
                    val autoStart = _ui.value.pendingAutoDownload
                    _ui.update {
                        it.copy(
                            link = result.url,
                            probe = ProbeUi.Success(result),
                            mode = if (result.audioOnly) DownloadMode.MP3 else DownloadMode.MAX,
                            detected = result.platform,
                            pendingAutoDownload = false
                        )
                    }
                    if (autoStart) startDownload()
                }
                .onFailure { error ->
                    appendDiagnosticLog("analyze failed: kind=" + (error as? MmtubeException)?.kind + " msg=" + error.message)
                    val kind = (error as? MmtubeException)?.kind ?: ErrorKind.GENERIC
                    _ui.update { it.copy(probe = ProbeUi.Error(kind, error.message)) }
                    event(UiEvent.Message(errorText(kind)))
                }
        }
    }

    fun selectMode(mode: DownloadMode) {
        _ui.update { it.copy(mode = mode) }
    }

    fun startDownload() {
        val state = _ui.value
        val probe = (state.probe as? ProbeUi.Success)?.result ?: return
        if (container.downloads.isActive()) {
            // Controller queues the request and starts it automatically.
            event(UiEvent.Message(context.getString(R.string.download_queued)))
        }
        viewModelScope.launch {
            // Space precheck: warn before burning CPU/bandwidth if the disk is full.
            val estimated = probe.estimatedBytes
            if (estimated > 0 && appContextFreeBytes() < estimated / 2 + 100L * 1024 * 1024) {
                event(UiEvent.Message(context.getString(R.string.error_no_space)))
                return@launch
            }
            val quota = runCatching { container.quota.current() }.getOrNull()
            _ui.update { it.copy(quota = quota) }
            if (quota == null || !quota.allowed) {
                val message = when {
                    quota == null -> context.getString(R.string.error_generic)
                    quota.disabled -> context.getString(R.string.account_disabled_desc)
                    else -> context.getString(R.string.error_quota)
                }
                event(UiEvent.Message(message))
                return@launch
            }
            val mode = _ui.value.mode
            val request = DownloadRequest(
                url = probe.webpageUrl ?: probe.url,
                title = probe.title,
                platform = probe.platform,
                mode = mode,
                thumbnail = probe.thumbnail,
                durationSeconds = probe.durationSeconds,
                qualityLabel = qualityLabel(probe, mode)
            )
            DownloadService.start(context, request)
        }
    }

    /** Add a list of links, one per line, and queue them for sequential download. */
    fun batchStart(text: String) {
        val urls = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (urls.isEmpty()) {
            event(UiEvent.Message(context.getString(R.string.batch_empty)))
            return
        }
        viewModelScope.launch {
            var queued = 0
            for (rawUrl in urls) {
                val normalized = UrlUtils.normalize(rawUrl) ?: continue
                container.media.probe(normalized).getOrNull()?.let { result ->
                    val mode = if (result.audioOnly) DownloadMode.MP3 else DownloadMode.MAX
                    val qualityLabel = if (mode == DownloadMode.MP3) "MP3"
                        else com.mmtube.app.util.Formatters.qualityLabel(result.maxHeight, result.maxFps)
                    val request = com.mmtube.app.download.DownloadRequest(
                        url = result.webpageUrl ?: result.url,
                        title = result.title,
                        platform = result.platform,
                        mode = mode,
                        thumbnail = result.thumbnail,
                        durationSeconds = result.durationSeconds,
                        qualityLabel = qualityLabel
                    )
                    DownloadService.start(context, request)
                    queued += 1
                }
            }
            event(UiEvent.Message(context.getString(R.string.batch_queued, queued)))
        }
    }

    fun cancelDownload() {
        container.downloads.cancel()
    }

    fun pauseDownload() {
        container.downloads.pause()
    }

    fun resumeDownload() {
        // Consume the paused snapshot and restart through the foreground
        // service so the download gets the same wakelock + progress wiring.
        val request = container.downloads.consumePausedRequest() ?: return
        DownloadService.start(context, request.copy(resume = true))
    }

    fun resetDownload() {
        container.downloads.reset()
    }

    fun retryDownload() {
        val current = _ui.value.download
        if (current is DownloadState.Failed) {
            clearProbeIfFailed()
        }
        analyze()
    }

    fun dismissCompleted() {
        if (_ui.value.download is DownloadState.Completed) {
            clearLink()
            container.downloads.reset()
        }
    }

    private fun clearProbeIfFailed() {
        if (_ui.value.probe is ProbeUi.Error) {
            _ui.update { it.copy(probe = ProbeUi.Idle) }
        }
    }

    fun qualityLabel(probe: ProbeResult, mode: DownloadMode): String = when (mode) {
        DownloadMode.MAX -> if (probe.audioOnly) {
            context.getString(R.string.quality_mp3)
        } else {
            Formatters.qualityLabel(probe.maxHeight, probe.maxFps)
        }
        DownloadMode.P1080 -> "1080p"
        DownloadMode.P720 -> "720p"
        DownloadMode.MP3 -> context.getString(R.string.quality_mp3)
    }

    // ---------- Quota ----------

    fun refreshQuota() {
        viewModelScope.launch {
            val quota = runCatching { container.quota.current() }.getOrNull()
            if (quota != null) {
                _ui.update { it.copy(quota = quota) }
            }
        }
    }

    private fun appContextFreeBytes(): Long {
        return runCatching {
            val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val stat = android.os.StatFs(directory.absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun appendDiagnosticLog(line: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, "diagnostic.log")
                if (!file.exists()) file.createNewFile()
                val stamp = System.currentTimeMillis()
                file.appendText("[$stamp] $line\n")
                if (file.length() > 64 * 1024L) {
                    val text = file.readText()
                    file.writeText(text.takeLast(48 * 1024))
                }
            }
        }
    }

    fun shareDiagnosticLog() {
        val file = File(context.filesDir, "diagnostic.log")
        val content = runCatching { if (file.exists()) file.readText() else "No diagnostic log yet." }.getOrDefault("No diagnostic log yet.")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content.takeLast(4096))
            putExtra(Intent.EXTRA_SUBJECT, "MMTube diagnostic log")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.logs_share)))
    }

    fun deletePartialFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val external = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val directory = java.io.File(external, "MMTube")
            val targets = directory.listFiles()?.filter {
                it.name.endsWith(".part") || it.name.endsWith(".ytdl") ||
                    it.name.endsWith(".aria2") || it.name.endsWith(".temp")
            }.orEmpty()
            var deleted = 0
            targets.forEach {
                if (runCatching { it.delete() }.getOrDefault(false)) deleted += 1
            }
            event(UiEvent.Message(context.getString(R.string.partial_deleted, deleted)))
        }
    }

    private fun updateQueueNotification(queuedTitle: String?) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (queuedTitle.isNullOrBlank()) {
            mgr.cancel(QUEUE_NOTIFICATION_ID)
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = androidx.core.app.PendingIntentCompat.getActivity(
            context, 7700, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT, false
        )
        val notif = NotificationCompat.Builder(context, QUEUE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mmtube)
            .setContentTitle(context.getString(R.string.queue_notification_title))
            .setContentText(queuedTitle.take(64))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
        mgr.notify(QUEUE_NOTIFICATION_ID, notif)
    }

    // ---------- Account ----------

    fun signIn(activity: Activity) {
        if (_ui.value.accountBusy) return
        _ui.update { it.copy(accountBusy = true) }
        viewModelScope.launch {
            container.account.signIn(activity)
                .onSuccess {
                    event(UiEvent.Message(context.getString(R.string.signed_in)))
                    refreshQuota()
                }
                .onFailure { error ->
                    val kind = (error as? MmtubeException)?.kind ?: ErrorKind.GENERIC
                    if (kind != ErrorKind.SIGN_IN_CANCELLED) {
                        event(UiEvent.Message(errorText(kind)))
                    }
                }
            _ui.update { it.copy(accountBusy = false) }
        }
    }

    fun signInWithEmail(email: String, password: String, createAccount: Boolean) {
        if (_ui.value.accountBusy) return
        if (email.isBlank() || password.isBlank()) {
            event(UiEvent.Message(context.getString(R.string.account_email_missing)))
            return
        }
        _ui.update { it.copy(accountBusy = true) }
        viewModelScope.launch {
            container.account.signInWithEmail(email, password, createAccount)
                .onSuccess {
                    event(UiEvent.Message(context.getString(R.string.signed_in)))
                    refreshQuota()
                }
                .onFailure { error ->
                    val message = error.message?.takeIf { it.isNotBlank() }
                        ?: errorText((error as? MmtubeException)?.kind ?: ErrorKind.GENERIC)
                    event(UiEvent.Message(message))
                }
            _ui.update { it.copy(accountBusy = false) }
        }
    }

    fun markOnboardingSeen() {
        viewModelScope.launch { container.settings.setOnboardingSeen() }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            val items = container.history.snapshot()
            items.forEach { runCatching { container.exporter.delete(it) } }
            container.history.clear()
            event(UiEvent.Message(context.getString(R.string.action_history_cleared)))
        }
    }

    /** Delete only entries older than 30 days (files + history rows). */
    fun clearOldHistory() {
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
            val all = container.history.snapshot()
            all.filter { it.createdAt < cutoff }.forEach {
                runCatching { container.exporter.delete(it) }
                container.history.remove(it.id)
            }
            event(UiEvent.Message(context.getString(R.string.history_cleanup_old_done)))
        }
    }

    fun signOut() {
        container.account.signOut()
        refreshQuota()
        event(UiEvent.Message(context.getString(R.string.signed_out)))
    }

    fun refreshAccount() {
        viewModelScope.launch {
            container.account.refresh()
            refreshQuota()
            event(UiEvent.Message(context.getString(R.string.action_refresh)))
        }
    }

    fun deleteAccountData() {
        viewModelScope.launch {
            _ui.update { it.copy(accountBusy = true) }
            container.account.deleteAccountData()
                .onSuccess {
                    refreshQuota()
                    event(UiEvent.Message(context.getString(R.string.account_deleted)))
                }
                .onFailure { error ->
                    event(UiEvent.Message(error.message ?: context.getString(R.string.error_firebase)))
                }
            _ui.update { it.copy(accountBusy = false) }
        }
    }

    fun copyUserCode() {
        val code = _ui.value.account.profile?.userCode ?: return
        context.copyText(context.getString(R.string.account_user_id), code)
        event(UiEvent.Message(context.getString(R.string.action_copied)))
    }

    // ---------- History ----------

    fun openHistory(item: HistoryItem) {
        val opened = context.openFile(Uri.parse(item.uri), item.mimeType)
        if (!opened) {
            event(UiEvent.Message(context.getString(R.string.download_open_failed)))
        }
    }

    fun shareHistory(item: HistoryItem) {
        context.shareFile(Uri.parse(item.uri), item.mimeType, item.title)
    }

    fun deleteHistory(item: HistoryItem) {
        viewModelScope.launch {
            runCatching { container.exporter.delete(item) }
            container.history.remove(item.id)
            event(UiEvent.Message(context.getString(R.string.action_delete)))
        }
    }

    // ---------- Settings ----------

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setTheme(mode) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            container.settings.setLanguage(tag)
            val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun setColorblindMode(v: Boolean) { viewModelScope.launch { container.settings.setColorblindMode(v) } }
    fun setDynamicColor(v: Boolean) { viewModelScope.launch { container.settings.setDynamicColor(v) } }

    fun setTurbo(enabled: Boolean) {
        viewModelScope.launch { container.settings.setTurbo(enabled) }
    }

    fun setAutoEngineUpdate(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoEngineUpdate(enabled) }
    }

    fun setAutoAppUpdate(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoAppUpdate(enabled) }
    }

    fun updateEngine() {
        if (_ui.value.engineUpdating) return
        _ui.update { it.copy(engineUpdating = true) }
        viewModelScope.launch {
            container.engine.updateEngine()
                .onSuccess { version ->
                    container.settings.setLastEngineUpdate(System.currentTimeMillis())
                    event(UiEvent.Message(context.getString(R.string.settings_engine_updated)))
                }
                .onFailure { error ->
                    val detail = (error.cause?.message ?: error.message).orEmpty()
                    val base = context.getString(R.string.settings_engine_failed)
                    event(UiEvent.Message(if (detail.isBlank()) base else "$base: ${detail.take(180)}"))
                }
            _ui.update { it.copy(engineUpdating = false) }
        }
    }

    fun retryEngine() {
        viewModelScope.launch {
            container.engine.retry()
        }
    }

    fun runEngineDiagnostics() {
        if (_ui.value.engineUpdating) return
        _ui.update { it.copy(engineDiagnostics = null, engineUpdating = true) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sb = StringBuilder()
            val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
            sb.appendLine("nativeLibraryDir: $nativeDir")
            sb.appendLine("dir exists: ${nativeDir.exists()} canRead: ${nativeDir.canRead()}")
            for (name in listOf("libffmpeg.so", "libffprobe.so", "libffmpeg.zip.so")) {
                val file = java.io.File(nativeDir, name)
                sb.appendLine("$name: exists=${file.exists()} size=${file.length()} exec=${file.canExecute()}")
            }

            // Same layout the youtubedl-android library expects for its
            // LD_LIBRARY_PATH: <noBackup>/youtubedl-android/packages/<pkg>/usr/lib
            val baseDir = java.io.File(context.noBackupFilesDir, "youtubedl-android")
            val packagesDir = java.io.File(baseDir, "packages")
            sb.appendLine("packages dir: $packagesDir exists=${packagesDir.exists()}")
            sb.appendLine("packages children: ${packagesDir.list()?.joinToString() ?: "-"}")
            val ffmpegLibDir = java.io.File(packagesDir, "ffmpeg/usr/lib")
            val libs = ffmpegLibDir.listFiles()
            sb.appendLine("ffmpeg libs: $ffmpegLibDir exists=${ffmpegLibDir.exists()} count=${libs?.size ?: 0}")
            sb.appendLine("sample: ${libs?.take(6)?.joinToString { it.name } ?: "-"}")

            val ldPath = listOf("python", "ffmpeg", "aria2c")
                .joinToString(":") { java.io.File(packagesDir, "$it/usr/lib").absolutePath }

            sb.appendLine("python liblzma.so: ${java.io.File(packagesDir, "python/usr/lib/liblzma.so").exists()}")
            sb.appendLine("python libz.so: ${java.io.File(packagesDir, "python/usr/lib/libz.so").exists()}")

            for (name in listOf("libffmpeg.so", "libffprobe.so")) {
                val exe = java.io.File(nativeDir, name)
                for ((label, path) in listOf("library" to ldPath, "ffmpeg-only" to ffmpegLibDir.absolutePath)) {
                    sb.appendLine("---- $name -version ($label LD_LIBRARY_PATH) ----")
                    runCatching {
                        val builder = ProcessBuilder(exe.absolutePath, "-version")
                            .redirectErrorStream(true)
                        builder.environment()["LD_LIBRARY_PATH"] = path
                        val process = builder.start()
                        val output = process.inputStream.bufferedReader().readText().trim()
                        val exit = process.waitFor()
                        sb.appendLine("exit code: $exit")
                        sb.appendLine(output.lineSequence().take(3).joinToString("\n").take(400))
                    }.onFailure { error ->
                        sb.appendLine("FAILED to start: ${error.message}")
                    }
                }
            }
            _ui.update { it.copy(engineUpdating = false, engineDiagnostics = sb.toString()) }
        }
    }

    fun clearEngineDiagnostics() {
        _ui.update { it.copy(engineDiagnostics = null) }
    }

    // ---------- Updates ----------

    fun checkForUpdates(silent: Boolean = false) {
        if (_ui.value.update.checking) return
        if (!silent) {
            _ui.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        }
        viewModelScope.launch {
            val result = container.updater.checkForUpdate()
            val now = System.currentTimeMillis()
            result
                .onSuccess { info ->
                    container.settings.setLastUpdateCheck(now)
                    if (info == null) {
                        if (!silent) {
                            event(UiEvent.Message(context.getString(R.string.settings_up_to_date)))
                        }
                        _ui.update { it.copy(update = it.update.copy(checking = false)) }
                    } else {
                        _ui.update { it.copy(update = UpdateUi(info = info)) }
                        // Auto-update: silently download the update in the background.
                        // Android only allows install after the user taps Install,
                        // but this removes the need to manually trigger the download.
                        if (silent && container.settings.snapshot().autoAppUpdate) {
                            downloadUpdate()
                        }
                    }
                }
                .onFailure { error ->
                    if (!silent) {
                        event(UiEvent.Message(context.getString(R.string.settings_update_error)))
                    }
                    _ui.update { it.copy(update = it.update.copy(checking = false, error = if (silent) null else error.message)) }
                }
        }
    }

    fun maybeCheckForUpdate(silent: Boolean = true) {
        viewModelScope.launch {
            val snapshot = container.settings.snapshot()
            if (!snapshot.autoAppUpdate) return@launch
            val stale = System.currentTimeMillis() - snapshot.lastUpdateCheck > 6 * 60 * 60 * 1000L
            if (stale) {
                checkForUpdates(silent = silent)
            }
        }
    }

    fun downloadUpdate() {
        val info = _ui.value.update.info ?: return
        if (_ui.value.update.downloading) return
        _ui.update { it.copy(update = it.update.copy(downloading = true, progress = 0, error = null)) }
        viewModelScope.launch {
            container.updater.download(info) { progress ->
                _ui.update { current ->
                    if (current.update.downloading) {
                        current.copy(update = current.update.copy(progress = progress))
                    } else {
                        current
                    }
                }
            }.onSuccess { file ->
                _ui.update { it.copy(update = it.update.copy(downloading = false, file = file, progress = 100)) }
                event(UiEvent.Message(context.getString(R.string.update_ready)))
            }.onFailure { error ->
                _ui.update { it.copy(update = it.update.copy(downloading = false, error = error.message)) }
                event(UiEvent.Message(context.getString(R.string.update_failed_title)))
            }
        }
    }

    fun installUpdate() {
        val file = _ui.value.update.file ?: return
        container.updater.install(file)
            .onFailure { error ->
                val kind = (error as? MmtubeException)?.kind
                if (kind == ErrorKind.INSTALL_PERMISSION) {
                    event(UiEvent.Message(context.getString(R.string.error_install_permission)))
                    container.updater.openInstallSettings()
                } else {
                    event(UiEvent.Message(error.message ?: context.getString(R.string.update_failed_title)))
                }
            }
    }

    fun dismissUpdate() {
        _ui.update { it.copy(update = it.update.copy(dismissed = true)) }
    }

    fun openUpdatePage() {
        val url = _ui.value.update.info?.htmlUrl ?: return
        context.openUrl(url)
    }

    // ---------- Terms ----------

    fun acceptTerms() {
        viewModelScope.launch {
            container.settings.acceptTerms()
            maybeCheckForUpdate(silent = true)
        }
    }

    fun declineTerms() {
        event(UiEvent.Exit)
    }

    // ---------- Support & links ----------

    fun openSupport() {
        val target = com.mmtube.app.BuildConfig.SUPPORT_CONTACT.trim()
        if (target.isBlank()) {
            event(UiEvent.Message(context.getString(R.string.settings_source_missing)))
        } else {
            context.openUrl(target)
        }
    }

    fun openSource() {
        val repo = com.mmtube.app.BuildConfig.UPDATE_REPO.trim()
        if (repo.isBlank()) {
            event(UiEvent.Message(context.getString(R.string.settings_source_missing)))
        } else {
            context.openUrl("https://github.com/$repo")
        }
    }

    fun openUrl(url: String) {
        context.openUrl(url)
    }

    // ---------- Errors ----------

    fun errorText(kind: ErrorKind): String = when (kind) {
        ErrorKind.INVALID_LINK -> context.getString(R.string.error_invalid_link)
        ErrorKind.UNSUPPORTED -> context.getString(R.string.error_unsupported)
        ErrorKind.PRIVATE -> context.getString(R.string.error_private)
        ErrorKind.AGE -> context.getString(R.string.error_age)
        ErrorKind.UNAVAILABLE -> context.getString(R.string.error_unavailable)
        ErrorKind.NETWORK -> context.getString(R.string.error_network)
        ErrorKind.ENGINE -> context.getString(R.string.error_engine)
        ErrorKind.DOWNLOAD -> context.getString(R.string.error_download)
        ErrorKind.QUOTA -> context.getString(R.string.error_quota)
        ErrorKind.NO_SPACE -> context.getString(R.string.error_no_space)
        ErrorKind.FIREBASE -> context.getString(R.string.error_firebase)
        ErrorKind.SIGN_IN_CANCELLED -> context.getString(R.string.error_sign_in_cancelled)
        ErrorKind.SIGN_IN_UNAVAILABLE -> context.getString(R.string.error_sign_in_unavailable)
        ErrorKind.NOT_ADMIN -> context.getString(R.string.error_not_admin)
        ErrorKind.INSTALL_PERMISSION -> context.getString(R.string.error_install_permission)
        ErrorKind.GENERIC -> context.getString(R.string.error_generic)
    }

    private fun event(event: UiEvent) {
        _events.trySend(event)
    }

    companion object {
        private const val QUEUE_NOTIFICATION_CHANNEL = "mmtube_queue"
        private const val QUEUE_NOTIFICATION_ID = 7700

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
