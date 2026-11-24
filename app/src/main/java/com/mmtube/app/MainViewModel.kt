package com.mmtube.app

import android.app.Activity
import android.util.Log
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
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val queuedTitle: String? = null,
    val storageFullPrompt: Boolean = false,
    val announcementId: String? = null,
    val announcementText: String? = null,
    val whatsNewVersion: String? = null,
    val queue: List<com.mmtube.app.download.DownloadRequest> = emptyList(),
    val latestPayment: com.mmtube.app.data.PaymentSummary? = null,
    val celebratedPaymentId: String? = null,
    val showTrialBanner: Boolean = false,
    val freeLimitReached: Boolean = false,
    val forceUpdate: Boolean = false,
    val announcementsMuted: Boolean = false,
    val crashReportsEnabled: Boolean = true,
    val inboxAnnouncements: List<com.mmtube.app.data.AnnouncementRecord> = emptyList(),
    val unreadAnnouncements: Int = 0,
    val showInbox: Boolean = false,
    val reduceMotion: Boolean = false,
    val showCelebration: Boolean = false,
    val selectedFormatId: String? = null,
    val duplicatePrompt: com.mmtube.app.data.HistoryItem? = null,
    val tempBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val customTitle: String = "",
    val clipboardLink: String? = null,
    val batchReport: com.mmtube.app.data.BatchReport? = null,
    val queuePaused: Boolean = false,
    val notificationsEnabled: Boolean = true
) {
    val termsAccepted: Boolean get() = settings.acceptedTerms
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class OpenTab(val tab: MainTab) : UiEvent
    data object Exit : UiEvent
    data object OpenUpgrade : UiEvent
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
        viewModelScope.launch {
            container.downloads.queuePaused.collect { paused ->
                _ui.update { it.copy(queuePaused = paused) }
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
            mgr?.createNotificationChannel(
                NotificationChannel(
                    UPDATE_NOTIFICATION_CHANNEL,
                    "App updates",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            mgr?.createNotificationChannel(
                NotificationChannel(
                    PLAN_REMINDER_CHANNEL,
                    "Plan reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        viewModelScope.launch {
            container.engine.state.collect { engine ->
                _ui.update { it.copy(engine = engine) }
            }
        }
        // Weekly recap: post a Sunday-evening summary once per week.
        viewModelScope.launch {
            runCatching {
                val snap = container.settings.snapshot()
                if (snap.lastRecapAt > 0L) {
                    val cal = java.util.Calendar.getInstance()
                    val monday = java.util.Calendar.getInstance().apply {
                        timeInMillis = snap.lastRecapAt
                        set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val now = cal.timeInMillis
                    if (now - monday.timeInMillis >= 7L * 24 * 3600 * 1000L) {
                        postWeeklyRecap()
                    }
                } else {
                    postWeeklyRecap()
                }
            }
        }

        // Populate reduceMotion from settings snapshot.
        viewModelScope.launch {
            val snap = container.settings.snapshot()
            _ui.update { it.copy(reduceMotion = snap.reduceMotion) }
        }
        refreshStorageInfo()
        refreshNotificationState()
        // What's New: show once per version upgrade.
        viewModelScope.launch {
            val snap = container.settings.snapshot()
            val version = BuildConfig.VERSION_NAME
            if (snap.lastSeenVersion != version) {
                _ui.update { it.copy(whatsNewVersion = version) }
            }
        }
        // Crash reporting preference (startup)
        viewModelScope.launch {
            runCatching {
                val enabled = container.settings.snapshot().crashReports
                if (com.mmtube.app.data.FirebaseConfig.isConfigured) {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                        .setCrashlyticsCollectionEnabled(enabled)
                }
                _ui.update { it.copy(crashReportsEnabled = enabled) }
            }
        }
        refreshAnnouncement()
        viewModelScope.launch {
            container.history.items.collect { items ->
                _ui.update { it.copy(history = items) }
            }
        }
        viewModelScope.launch {
            container.account.ui.collect { account ->
                val wasPaid = _ui.value.quota?.paid == true
                runCatching {
                    if (com.mmtube.app.data.FirebaseConfig.isConfigured) {
                        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                            .setUserId(account.profile?.userCode.orEmpty())
                    }
                }
                val trialActive = account.profile?.isTrial == true
                if (trialActive && account.profile?.trialEndsAt != null) {
                    _ui.update { it.copy(account = account, showTrialBanner = true) }
                } else {
                    _ui.update { it.copy(account = account) }
                }
                startPaymentListener()
                refreshQuota()
                // Expiry check: paid -> free transition should notify the user.
                val nowPaid = _ui.value.quota?.paid == true
                if (wasPaid && !nowPaid) {
                    event(UiEvent.Message(context.getString(R.string.expired_plan_message)))
                }
                if (!wasPaid && nowPaid) {
                    playCompletionChime()
                    _ui.update { it.copy(showCelebration = true) }
                }
            }
        }
        viewModelScope.launch {
            container.downloads.queue.collect { queued ->
                _ui.update { it.copy(queue = queued, queuedTitle = queued.firstOrNull()?.title) }
                updateQueueNotification(queued)
            }
        }
        viewModelScope.launch {
            container.downloads.state.collect { state ->
                _ui.update { it.copy(download = state, queuedTitle = container.downloads.queue.value.firstOrNull()?.title) }
                updateQueueNotification(container.downloads.queue.value)
                when (state) {
                    is DownloadState.Completed -> {
                        playCompletionChime()
                        refreshQuota()
                        val message = if (state.item.savedToGallery) {
                            context.getString(R.string.download_saved_gallery)
                        } else {
                            context.getString(R.string.download_complete_message)
                        }
                        event(UiEvent.Message(message))
                        // Show the "this was your free download" nudge for eligible free users.
                        val quota = _ui.value.quota
                        if (quota != null && !quota.paid && !quota.trial && !quota.allowed) {
                            _ui.update { it.copy(freeLimitReached = true) }
                        }
                    }
                    is DownloadState.Failed -> {
                        recordException(MmtubeException(state.kind, state.detail))
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

    private fun isKnownUnsupportedHost(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        val unsupported = listOf(
            "instagram.com", "twitter.com", "x.com", "reddit.com", "pinterest.com",
            "vimeo.com", "dailymotion.com", "soundcloud.com", "twitch.tv", "snapchat.com",
            "linkedin.com", "pin.it", "ixigua.com", "bilibili.com", "vimeo.video"
        )
        return unsupported.any { host == it || host.endsWith(".$it") }
    }

    fun analyze() {
        val raw = _ui.value.link.trim()
        if (UrlUtils.normalize(raw) == null) {
            event(UiEvent.Message(context.getString(R.string.error_invalid_link)))
            return
        }
        if (UrlUtils.detectPlatform(raw) == Platform.OTHER && isKnownUnsupportedHost(raw)) {
            _ui.update { it.copy(probe = ProbeUi.Error(ErrorKind.UNSUPPORTED, "unsupported_site")) }
            event(UiEvent.Message(context.getString(R.string.error_unsupported_site)))
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
                            mode = if (result.audioOnly) DownloadMode.MP3 else preferredMode(),
                            customTitle = "",
                            selectedFormatId = null,
                            detected = result.platform,
                            pendingAutoDownload = false
                        )
                    }
                    if (autoStart) startDownload()
                }
                .onFailure { error ->
                    recordException(error)
                    appendDiagnosticLog("analyze failed: kind=" + (error as? MmtubeException)?.kind + " msg=" + error.message)
                    val kind = (error as? MmtubeException)?.kind ?: ErrorKind.GENERIC
                    _ui.update { it.copy(probe = ProbeUi.Error(kind, error.message)) }
                    event(UiEvent.Message(errorText(kind)))
                }
        }
    }

    private fun isSameDownload(
        item: com.mmtube.app.data.HistoryItem,
        probe: com.mmtube.app.data.ProbeResult,
        source: String
    ): Boolean {
        if (item.sourceUrl.isNotBlank()) {
            return UrlUtils.sameVideo(item.sourceUrl, source)
        }
        // History saved before sourceUrl existed: fall back to title + duration.
        if (!item.title.trim().equals(probe.title.trim(), ignoreCase = true)) return false
        return probe.durationSeconds <= 0 ||
            item.durationSeconds <= 0 ||
            kotlin.math.abs(item.durationSeconds - probe.durationSeconds) <= 2
    }

    fun selectMode(mode: DownloadMode) {
        _ui.update { it.copy(mode = mode) }
        viewModelScope.launch {
            runCatching { container.settings.setLastMode(mode.key) }
        }
    }

    private fun preferredMode(): DownloadMode {
        val saved = _ui.value.settings.lastMode
        return if (saved.isBlank()) DownloadMode.MAX else DownloadMode.fromKey(saved)
    }

    fun setCustomTitle(value: String) {
        _ui.update { it.copy(customTitle = value.take(120)) }
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
                _ui.update { it.copy(storageFullPrompt = true) }
                return@launch
            }
            val source = probe.webpageUrl ?: probe.url
            val historyItems = runCatching { container.history.snapshot() }
                .getOrDefault(emptyList())
            val duplicate = historyItems.firstOrNull { item -> isSameDownload(item, probe, source) }
            if (duplicate != null && duplicateAck != source) {
                _ui.update { it.copy(duplicatePrompt = duplicate) }
                return@launch
            }
            if (duplicate != null) duplicateAck = null
            val quota = runCatching { container.quota.current() }.getOrNull()
            _ui.update { it.copy(quota = quota) }
            if (quota == null || !quota.allowed) {
                val message = when {
                    quota == null -> context.getString(R.string.error_generic)
                    quota.disabled -> context.getString(R.string.account_disabled_desc)
                    else -> context.getString(R.string.error_quota)
                }
                event(UiEvent.Message(message))
                showQuotaBlockNotification()
                return@launch
            }
            val mode = _ui.value.mode
            val selected = probe.formats.firstOrNull { it.id == _ui.value.selectedFormatId }
            val sourceKey = UrlUtils.canonicalKey(probe.webpageUrl ?: probe.url)
            val smart = com.mmtube.app.util.FormatPicker.choose(
                probe,
                mode,
                com.mmtube.app.util.DisplaySupport.canRenderHdr(context),
                _ui.value.settings.preferMultichannel
            )
            val remembered = sourceKey?.let { com.mmtube.app.util.FormatMemory.get(context, it) }
            val formatId = when {
                mode == DownloadMode.MP3 -> null
                _ui.value.selectedFormatId != null -> _ui.value.selectedFormatId
                remembered != null -> remembered
                smart != null -> smart.format
                else -> null
            }
            val usedSmart = formatId != null && _ui.value.selectedFormatId == null &&
                remembered == null && smart != null
            if (formatId != null && _ui.value.selectedFormatId != null && sourceKey != null) {
                com.mmtube.app.util.FormatMemory.put(context, sourceKey, formatId)
            }
            val expectedHeight = if (mode == DownloadMode.MP3 || remembered != null) {
                0
            } else if (usedSmart) {
                smart.height
            } else {
                (selected?.height?.takeIf { it > 0 } ?: probe.maxHeight).let { height ->
                    when (mode) {
                        DownloadMode.P1080 -> kotlin.math.min(height, 1080)
                        DownloadMode.P720 -> kotlin.math.min(height, 720)
                        else -> height
                    }
                }
            }
            val expectedFps = if (mode == DownloadMode.MP3 || remembered != null) 0
                else if (usedSmart) smart.fps
                else selected?.fps?.takeIf { it > 0 } ?: probe.maxFps
            val request = DownloadRequest(
                url = probe.webpageUrl ?: probe.url,
                title = state.customTitle.trim().ifBlank { probe.title },
                platform = probe.platform,
                mode = mode,
                thumbnail = probe.thumbnail,
                durationSeconds = probe.durationSeconds,
                qualityLabel = qualityLabel(probe, mode),
                formatId = formatId,
                expectedHeight = expectedHeight,
                expectedFps = expectedFps,
                formatIsVideo = if (usedSmart && smart?.complete == true) false
                    else if (formatId?.contains("+") == true) true
                    else selected?.isVideo ?: true,
                alternates = if (usedSmart) smart?.alternates.orEmpty() else emptyList(),
                preferMkv = if (usedSmart) smart?.hdr ?: false else selected?.hdr ?: false,
                audioFormat = effectiveAudioFormat(),
                adaptiveProfile = state.settings.adaptiveProfile,
                parallelStreams = state.settings.parallelStreams,
                estimatedBytes = probe.estimatedBytes,
                memoryKey = sourceKey.orEmpty(),
                preserveAudio = state.settings.preserveAudioDynamics,
                autoChunk = state.settings.autoChunk,
                loudnessTarget = state.settings.loudnessTarget,
                verify = state.settings.verifyDownloads,
                throttledRateKb = state.settings.throttledRateKb,
                checkFormats = state.settings.checkFormats,
                bufferSizeKb = state.settings.bufferSizeKb,
                forceIpv4 = state.settings.forceIpv4,
                sortForce = state.settings.sortForce
            ).let(::applyAdvancedOptions)
            DownloadService.start(context, request)
            refreshStorageInfo()
        }
    }

    private var duplicateAck: String? = null

    private fun effectiveAudioFormat(): String {
        val saved = _ui.value.settings.audioFormat
        val acodec = when (saved) {
            "opus" -> "opus"
            "flac" -> "flac"
            else -> return "mp3"
        }
        if (com.mmtube.app.util.FormatSupport.isAudioPlayable(acodec)) return saved
        event(UiEvent.Message(context.getString(R.string.audio_codec_unsupported)))
        return "mp3"
    }

    /** Starts a download without waiting for the preview (fastest start). */
    fun fastStart(raw: String? = null) {
        val link = raw?.takeIf { it.isNotBlank() } ?: _ui.value.link
        val url = UrlUtils.normalize(link)
        if (url == null) {
            event(UiEvent.Message(context.getString(R.string.batch_reason_invalid)))
            return
        }
        val request = DownloadRequest(
            url = url,
            title = context.getString(R.string.fast_start_title),
            platform = UrlUtils.detectPlatform(url) ?: Platform.OTHER,
            mode = preferredMode(),
            thumbnail = null,
            durationSeconds = 0,
            qualityLabel = context.getString(R.string.fast_start_quality),
            audioFormat = effectiveAudioFormat(),
            adaptiveProfile = _ui.value.settings.adaptiveProfile,
            parallelStreams = _ui.value.settings.parallelStreams,
            preserveAudio = _ui.value.settings.preserveAudioDynamics,
            autoChunk = _ui.value.settings.autoChunk,
            loudnessTarget = _ui.value.settings.loudnessTarget,
            verify = _ui.value.settings.verifyDownloads,
            throttledRateKb = _ui.value.settings.throttledRateKb,
            checkFormats = _ui.value.settings.checkFormats,
            bufferSizeKb = _ui.value.settings.bufferSizeKb,
            forceIpv4 = _ui.value.settings.forceIpv4,
            sortForce = _ui.value.settings.sortForce
        ).let(::applyAdvancedOptions)
        DownloadService.start(context, request)
        event(UiEvent.Message(context.getString(R.string.fast_start_started)))
    }

    fun selectFormat(id: String?) {
        _ui.update { it.copy(selectedFormatId = id) }
    }

    fun downloadAnyway() {
        duplicateAck = (_ui.value.probe as? ProbeUi.Success)?.result?.let { it.webpageUrl ?: it.url }
        _ui.update { it.copy(duplicatePrompt = null) }
        startDownload()
    }

    fun dismissDuplicate() {
        duplicateAck = null
        _ui.update { it.copy(duplicatePrompt = null) }
    }

    fun openDuplicate() {
        val item = _ui.value.duplicatePrompt ?: return
        dismissDuplicate()
        openHistory(item)
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            val temp = runCatching { container.downloads.tempBytes() }.getOrDefault(0L)
            val history = runCatching { container.history.snapshot() }.getOrDefault(emptyList())
            val downloaded = history.sumOf { item ->
                val path = item.localPath
                if (!path.isNullOrBlank()) {
                    runCatching { File(path).length() }.getOrDefault(0L).takeIf { it > 0L }
                        ?: item.sizeBytes
                } else {
                    item.sizeBytes
                }
            }
            _ui.update { it.copy(tempBytes = temp, downloadedBytes = downloaded) }
        }
    }

    fun refreshNotificationState() {
        val enabled = runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
        _ui.update { it.copy(notificationsEnabled = enabled) }
    }

    /** Called from the UI on every resume; reads clipboard only while foregrounded. */
    fun onAppForeground() {
        refreshNotificationState()
        refreshStorageInfo()
        checkClipboard()
    }

    private var lastClipboardSuggestion: String? = null

    private fun checkClipboard() {
        val text = runCatching {
            val manager = context.getSystemService(android.content.ClipboardManager::class.java)
            manager?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        val url = UrlUtils.extractUrl(text) ?: return
        if (UrlUtils.detectPlatform(url) == Platform.OTHER) return
        if (url == lastClipboardSuggestion) return
        lastClipboardSuggestion = url
        _ui.update { it.copy(clipboardLink = url) }
    }

    fun useClipboardLink() {
        val link = _ui.value.clipboardLink ?: return
        _ui.update { it.copy(clipboardLink = null) }
        pasteLink(link)
    }

    fun dismissClipboardLink() {
        _ui.update { it.copy(clipboardLink = null) }
    }

    fun dismissBatchReport() {
        _ui.update { it.copy(batchReport = null) }
    }

    fun clearTempFiles() {
        viewModelScope.launch {
            val removed = runCatching { container.downloads.clearTempFiles() }.getOrDefault(0)
            refreshStorageInfo()
            event(UiEvent.Message(context.getString(R.string.storage_temp_cleared, removed)))
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
            val failures = mutableListOf<com.mmtube.app.data.BatchFailure>()
            val seen = mutableSetOf<String>()
            val candidates = mutableListOf<Pair<String, String>>()
            val history = runCatching { container.history.snapshot() }.getOrDefault(emptyList())
            for (rawUrl in urls) {
                val normalized = UrlUtils.normalize(rawUrl)
                if (normalized == null) {
                    failures += com.mmtube.app.data.BatchFailure(
                        rawUrl,
                        context.getString(R.string.batch_reason_invalid)
                    )
                    continue
                }
                val key = UrlUtils.canonicalKey(normalized) ?: normalized
                if (!seen.add(key)) {
                    failures += com.mmtube.app.data.BatchFailure(
                        rawUrl,
                        context.getString(R.string.batch_reason_same_link)
                    )
                    continue
                }
                if (history.any { item ->
                        item.sourceUrl.isNotBlank() && UrlUtils.sameVideo(item.sourceUrl, normalized)
                    }
                ) {
                    failures += com.mmtube.app.data.BatchFailure(
                        rawUrl,
                        context.getString(R.string.batch_reason_duplicate)
                    )
                    continue
                }
                val host = runCatching { java.net.URL(normalized).host }.getOrNull().orEmpty()
                if (com.mmtube.app.util.CircuitBreaker.isOpen(normalized)) {
                    failures += com.mmtube.app.data.BatchFailure(
                        rawUrl,
                        context.getString(R.string.batch_reason_host_blocked, host)
                    )
                    continue
                }
                candidates += normalized to rawUrl
            }
            // Warm connections to the hosts we are about to use.
            runCatching { com.mmtube.app.util.HostPrefetch.warm(candidates.map { it.first }) }
            // One yt-dlp process analyses the whole batch instead of one per link.
            val analysed = runCatching {
                container.media.probeMany(candidates.map { it.first })
            }.getOrDefault(emptyMap())
            val batchRequests = mutableListOf<com.mmtube.app.download.DownloadRequest>()
            for ((normalized, rawUrl) in candidates) {
                val probe = analysed[normalized]
                val result = probe?.getOrNull()
                if (result == null) {
                    val kind = (probe?.exceptionOrNull() as? MmtubeException)?.kind
                        ?: ErrorKind.GENERIC
                    com.mmtube.app.util.CircuitBreaker.recordFailure(normalized)
                    failures += com.mmtube.app.data.BatchFailure(rawUrl, errorText(kind))
                    continue
                }
                com.mmtube.app.util.CircuitBreaker.recordSuccess(normalized)
                val mode = if (result.audioOnly) DownloadMode.MP3 else preferredMode()
                val qualityLabel = if (mode == DownloadMode.MP3) "MP3"
                    else com.mmtube.app.util.Formatters.qualityLabel(result.maxHeight, result.maxFps)
                val request = com.mmtube.app.download.DownloadRequest(
                    url = result.webpageUrl ?: result.url,
                    title = result.title,
                    platform = result.platform,
                    mode = mode,
                    thumbnail = result.thumbnail,
                    durationSeconds = result.durationSeconds,
                    qualityLabel = qualityLabel,
                    expectedHeight = if (mode == DownloadMode.MP3) 0 else result.maxHeight,
                    expectedFps = if (mode == DownloadMode.MP3) 0 else result.maxFps,
                    estimatedBytes = result.estimatedBytes,
                    audioFormat = effectiveAudioFormat(),
                    preserveAudio = _ui.value.settings.preserveAudioDynamics,
                    autoChunk = _ui.value.settings.autoChunk,
                    memoryKey = UrlUtils.canonicalKey(normalized).orEmpty(),
                    loudnessTarget = _ui.value.settings.loudnessTarget,
                    verify = _ui.value.settings.verifyDownloads,
                    throttledRateKb = _ui.value.settings.throttledRateKb,
                    checkFormats = _ui.value.settings.checkFormats,
                    bufferSizeKb = _ui.value.settings.bufferSizeKb,
                    forceIpv4 = _ui.value.settings.forceIpv4,
                    sortForce = _ui.value.settings.sortForce
                ).let(::applyAdvancedOptions)
                batchRequests += request
                queued += 1
            }
            if (batchRequests.size == 1) {
                DownloadService.start(context, batchRequests.first())
            } else if (_ui.value.settings.singleProcessBatch) {
                DownloadService.startBatchSingleProcess(context, batchRequests)
            } else {
                DownloadService.startBatch(context, batchRequests)
            }
            _ui.update {
                it.copy(batchReport = com.mmtube.app.data.BatchReport(queued, failures))
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
                maybeRemindPlanExpiry()
            }
        }
    }

    fun refreshAnnouncement() {
        viewModelScope.launch {
            val db = container.firestore ?: return@launch
            val snap = container.settings.snapshot()
            val doc = runCatching { db.collection("announcements").document("latest").get().await() }.getOrNull() ?: return@launch
            if (!doc.exists()) return@launch
            val text = doc.getString("text").orEmpty().trim()
            val id = doc.getString("id") ?: "latest"
            val minVersion = doc.getLong("minVersionCode") ?: 0L
            val dryRun = doc.getBoolean("dryRun") ?: false
            val targetEmail = doc.getString("targetEmail")
            if (minVersion > BuildConfig.VERSION_CODE) {
                _ui.update { it.copy(forceUpdate = true) }
            }
            if (snap.announcementsMuted) return@launch
            val muted = snap.dismissedAnnouncementId == "__all__"
            if (dryRun) {
                val myEmail = (authRepositoryEmailOrNull()) ?: return@launch
                if (targetEmail != null && targetEmail != myEmail) return@launch
            }
            if (text.isNotBlank() && id != snap.dismissedAnnouncementId && !muted) {
                _ui.update { it.copy(announcementId = id, announcementText = text, announcementsMuted = muted) }
            }
        }
    }

    private fun authRepositoryEmailOrNull(): String? {
        val profile = _ui.value.account.profile ?: return null
        return profile.email
    }

    fun dismissAnnouncement() {
        val id = _ui.value.announcementId ?: return
        viewModelScope.launch { container.settings.setDismissedAnnouncementId(id) }
        _ui.update { it.copy(announcementId = null, announcementText = null) }
    }

    fun dismissWhatsNew() {
        viewModelScope.launch { container.settings.setLastSeenVersion(BuildConfig.VERSION_NAME) }
        _ui.update { it.copy(whatsNewVersion = null) }
    }

    private suspend fun maybeRemindPlanExpiry() {
        val profile = _ui.value.account.profile ?: return
        if (!profile.isPaid) return
        val expiresAt = profile.planExpiresAt ?: return
        val now = System.currentTimeMillis()
        val remaining = expiresAt - now
        if (remaining <= 0 || remaining > 3L * 24 * 3600 * 1000) return
        val last = container.settings.snapshot().lastPlanReminderAt
        if (now - last < 24L * 3600 * 1000) return
        container.settings.setLastPlanReminderAt(now)
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true)
            }
            val tap = android.app.PendingIntent.getActivity(
                context, 7702, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, PLAN_REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mmtube)
                .setContentTitle(context.getString(R.string.plan_reminder_title))
                .setContentText(context.getString(R.string.plan_reminder_body, Formatters.formatDate(expiresAt)))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            mgr.notify(PLAN_REMINDER_NOTIFICATION_ID, notif)
        }
    }

    private fun playCompletionChime() {
        if (!_ui.value.settings.chimeEnabled) return
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, uri)?.play()
        }
    }

    fun setChimeEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setChimeEnabled(enabled) }
    }

    private var paymentListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var paymentListenerUid: String? = null
    private var prevPaymentStatus: String? = null

    private fun startPaymentListener() {
        val uid = _ui.value.account.profile?.uid ?: return
        if (paymentListenerUid == uid) return
        val db = container.firestore ?: return
        paymentListener?.remove()
        paymentListenerUid = uid
        paymentListener = db.collection("payments")
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val latest = snapshot.documents.maxByOrNull { it.getTimestamp("createdAt")?.seconds ?: 0L }
                val summary = latest?.let {
                    com.mmtube.app.data.PaymentSummary(
                        id = it.id,
                        plan = it.getString("plan") ?: "",
                        amountKs = it.getLong("amountKs") ?: 0L,
                        status = it.getString("status") ?: "pending",
                        adminNote = it.getString("adminNote"),
                        createdAt = it.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }
                val newStatus = summary?.status
                val wasPending = prevPaymentStatus == "pending"
                _ui.update { it.copy(latestPayment = summary) }
                if (wasPending && newStatus == "approved" && summary != null) {
                    playCompletionChime()
                    _ui.update { it.copy(showCelebration = true) }
                    event(UiEvent.Message(context.getString(R.string.payment_approved_chime)))
                    refreshQuota()
                } else if (wasPending && newStatus == "rejected") {
                    event(UiEvent.Message(context.getString(R.string.payment_rejected_notice)))
                }
                prevPaymentStatus = newStatus
            }
    }

    fun cancelMyPendingPayment() {
        val summary = _ui.value.latestPayment ?: return
        val db = container.firestore ?: return
        if (summary.status != "pending") return
        viewModelScope.launch {
            runCatching {
                db.collection("payments").document(summary.id)
                    .set(
                        mapOf(
                            "status" to "cancelled",
                            "reviewedAt" to com.google.firebase.Timestamp.now()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .await()
            }.onFailure {
                Log.w("MainViewModel", "Cancel pending payment failed", it)
            }
        }
    }

    fun fetchMyLatestPayment(onDone: (com.mmtube.app.data.PaymentSummary?) -> Unit) {
        onDone(_ui.value.latestPayment)
    }

    fun sendDiagnosticToTelegram() {
        viewModelScope.launch {
            val file = java.io.File(context.filesDir, "diagnostic.log")
            val tail = if (file.exists()) file.readText().takeLast(1500) else "diagnostic log not created yet"
            val message = "MMTube diagnostic log:\n\n" + tail
            val encoded = android.net.Uri.encode(message)
            val telegram = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("tg://resolve?domain=TKA_MTC&text=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(telegram) }.recoverCatching {
                val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/TKA_MTC")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(web)
            }
        }
    }

    private fun postWeeklyRecap() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val history = container.history.snapshot()
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                val recent = history.filter { it.createdAt >= weekAgo }
                if (recent.isNotEmpty()) {
                    val bytes = recent.sumOf { it.sizeBytes }
                    val mgr = context.getSystemService(NotificationManager::class.java)
                    val channel = NotificationChannel(
                        "mmtube_recap",
                        "Weekly recap",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                    mgr?.createNotificationChannel(channel)
                    val text = context.getString(R.string.recap_notification_body, recent.size, Formatters.formatBytes(bytes))
                    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
                    val tap = android.app.PendingIntent.getActivity(
                        context, 7710, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val notif = NotificationCompat.Builder(context, "mmtube_recap")
                        .setSmallIcon(R.drawable.ic_stat_mmtube)
                        .setContentTitle(context.getString(R.string.recap_notification_title))
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setContentIntent(tap)
                        .build()
                    mgr?.notify(7710, notif)
                    container.settings.setLastRecapAt(System.currentTimeMillis())
                }
            }
        }
    }

    fun markInboxShown() {
        viewModelScope.launch { container.settings.setDismissedAnnouncementId(_ui.value.inboxAnnouncements.firstOrNull()?.id ?: "") }
        _ui.update { it.copy(unreadAnnouncements = 0, showInbox = false) }
    }

    fun dismissInbox() {
        _ui.update { it.copy(showInbox = false) }
    }

    fun openInbox() {
        viewModelScope.launch {
            val db = container.firestore ?: run { _ui.update { it.copy(showInbox = true) }; return@launch }
            val result = runCatching {
                db.collection("announcements").get().await().documents.mapNotNull {
                    val id = it.getString("id") ?: it.id
                    val text = it.getString("text") ?: return@mapNotNull null
                    val at = it.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    com.mmtube.app.data.AnnouncementRecord(id, text, at)
                }.sortedByDescending { it.createdAt }.take(15)
            }.getOrDefault(emptyList())
            val lastSeen = container.settings.snapshot().dismissedAnnouncementId
            val unseen = if (lastSeen.isBlank()) result.size else result.count { it.id != lastSeen }
            _ui.update { it.copy(inboxAnnouncements = result, unreadAnnouncements = unseen, showInbox = true) }
        }
    }

    fun fetchRecentAnnouncements(onDone: (List<com.mmtube.app.data.AnnouncementRecord>) -> Unit) {
        viewModelScope.launch {
            val db = container.firestore ?: run { onDone(emptyList()); return@launch }
            val result = runCatching {
                db.collection("announcements").get().await().documents.mapNotNull {
                    val id = it.getString("id") ?: it.id
                    val text = it.getString("text") ?: return@mapNotNull null
                    val at = it.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    com.mmtube.app.data.AnnouncementRecord(id, text, at)
                }.sortedByDescending { it.createdAt }.take(15)
            }.getOrDefault(emptyList())
            onDone(result)
        }
    }

    fun setCrashReportsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setCrashReports(enabled)
            if (com.mmtube.app.data.FirebaseConfig.isConfigured) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .setCrashlyticsCollectionEnabled(enabled)
            }
            _ui.update { it.copy(crashReportsEnabled = enabled) }
        }
    }

    fun setAnnouncementsMuted(muted: Boolean) {
        viewModelScope.launch {
            container.settings.setAnnouncementsMuted(muted)
            _ui.update { it.copy(announcementsMuted = muted) }
        }
    }

    fun resetAppData() {
        viewModelScope.launch {
            container.settings.clearAll()
            container.history.clear()
            event(UiEvent.Message(context.getString(R.string.reset_app_data_done)))
        }
    }

    /**
     * Uploads payment proof. [onDone] gets (ok, error, claimCode); the claim
     * code is the Firestore document id so the admin can find the exact row
     * without any schema change.
     */
    fun submitPayment(
        plan: String,
        buyerPhone: String,
        note: String,
        imageBase64: String,
        onDone: (Boolean, String?, String?) -> Unit
    ) {
        val profile = _ui.value.account.profile
        val db = container.firestore
        if (profile == null || db == null) {
            onDone(false, "Sign in လုပ်ပါ", null)
            return
        }
        val months = Plans.months(plan)
        val amount = when (plan) {
            Plans.M1 -> 1000
            Plans.M3 -> 2000
            Plans.M6 -> 4000
            Plans.M12 -> 7000
            else -> 0
        }
        if (months <= 0 || imageBase64.isBlank()) {
            onDone(false, "Plan နဲ့ ငွေပြေစာပုံ ဖြည့်ပါ", null)
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                val ref = db.collection("payments").add(
                    mapOf(
                        "uid" to profile.uid,
                        "userCode" to profile.userCode,
                        "email" to profile.email,
                        "plan" to plan,
                        "planMonths" to months,
                        "amountKs" to amount,
                        "buyerPhone" to buyerPhone.trim().ifBlank { null },
                        "note" to note.trim().ifBlank { null },
                        "imageBase64" to imageBase64,
                        "status" to "pending",
                        "createdAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()
                ref.id
            }
            result
                .onSuccess { id ->
                    com.mmtube.app.data.PaymentStatusWorker.enqueue(context, id)
                    onDone(true, null, id)
                }
                .onFailure { onDone(false, it.message, null) }
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

    private fun showUpdateReadyNotification() {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)
            }
            val tap = android.app.PendingIntent.getActivity(
                context, 7701, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val text = context.getString(R.string.update_ready_notification)
            val notif = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mmtube)
                .setContentTitle(context.getString(R.string.settings_updates))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .build()
            mgr.notify(UPDATE_NOTIFICATION_ID, notif)
        }
    }

    fun dismissStorageFullPrompt() {
        _ui.update { it.copy(storageFullPrompt = false) }
    }

    fun clearOldFilesFromStoragePrompt() {
        clearOldHistory()
        dismissStorageFullPrompt()
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

    private fun updateQueueNotification(queued: List<com.mmtube.app.download.DownloadRequest>) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val head = queued.firstOrNull()
        if (head == null) {
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
        val count = queued.size
        val text = if (count > 1) {
            context.getString(R.string.queue_notification_count, count, head.title.take(48))
        } else {
            head.title.take(64)
        }
        val notif = NotificationCompat.Builder(context, QUEUE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mmtube)
            .setContentTitle(context.getString(R.string.queue_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
        mgr.notify(QUEUE_NOTIFICATION_ID, notif)
    }

    private fun showQuotaBlockNotification() {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true)
            }
            val tap = android.app.PendingIntent.getActivity(
                context, 7703, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, PLAN_REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mmtube)
                .setContentTitle(context.getString(R.string.quota_block_notification_title))
                .setContentText(context.getString(R.string.quota_block_notification_body))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .addAction(
                    android.R.drawable.ic_menu_upload,
                    context.getString(R.string.quota_block_notification_action),
                    tap
                )
                .build()
            mgr.notify(QUOTA_BLOCK_NOTIFICATION_ID, notif)
        }
    }

    private fun recordException(error: Throwable) {
        runCatching {
            if (com.mmtube.app.data.FirebaseConfig.isConfigured) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(error)
            }
        }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { container.settings.setReduceMotion(enabled) }
        _ui.update { it.copy(reduceMotion = enabled) }
    }

    fun exportHistoryCsv() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val history = container.history.snapshot()
                val sb = StringBuilder()
                sb.appendLine("title,platform,quality,size_bytes,mode,date")
                for (item in history) {
                    val title = item.title.replace("\"", "").replace(',', ';')
                    sb.appendLine("\"$title\",${item.platform},${item.quality},${item.sizeBytes},${item.mode},${item.createdAt}")
                }
                val file = java.io.File(context.cacheDir, "mmtube_history.csv")
                file.writeText(sb.toString())
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(Intent.createChooser(share, "Export history").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun dismissCelebration() {
        _ui.update { it.copy(showCelebration = false) }
    }

    fun dismissFreeLimitReached() {
        _ui.update { it.copy(freeLimitReached = false) }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        container.downloads.moveQueueItem(fromIndex, toIndex)
    }

    fun removeFromQueue(index: Int) {
        container.downloads.removeFromQueue(index)
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

    fun loadPurchaseHistory(onResult: (List<com.mmtube.app.data.PurchaseRecord>) -> Unit) {
        viewModelScope.launch { onResult(container.account.purchaseHistory()) }
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
        if (item.mimeType.startsWith("video")) {
            runCatching {
                PlayerActivity.start(context, Uri.parse(item.uri), item.title, item.id, item.isHdr)
            }.onSuccess { return }.onFailure {
                Log.w("MMTube", "Player launch failed", it)
            }
        }
        val opened = context.openFile(Uri.parse(item.uri), item.mimeType)
        if (!opened) {
            event(UiEvent.Message(context.getString(R.string.download_open_failed)))
        }
    }

    fun copyHistoryLink(item: HistoryItem) {
        val link = item.sourceUrl
        if (link.isBlank()) {
            event(UiEvent.Message(context.getString(R.string.history_no_link)))
            return
        }
        runCatching { context.copyText("MMTube", link) }
        event(UiEvent.Message(context.getString(R.string.link_copied)))
    }

    fun shareHistoryLink(item: HistoryItem) {
        if (item.sourceUrl.isBlank()) {
            event(UiEvent.Message(context.getString(R.string.history_no_link)))
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.sourceUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, item.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun renameHistory(item: HistoryItem, newName: String) {
        val name = newName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val renamed = runCatching { container.exporter.rename(item, name) }.getOrNull()
            if (renamed == null) {
                event(UiEvent.Message(context.getString(R.string.history_rename_failed)))
            } else {
                container.history.replace(
                    item.copy(
                        title = renamed.displayName.substringBeforeLast('.'),
                        uri = renamed.uri.toString(),
                        localPath = renamed.localPath
                    )
                )
                refreshStorageInfo()
                event(UiEvent.Message(context.getString(R.string.history_renamed)))
            }
        }
    }

    fun pauseQueue() = container.downloads.pauseQueue()

    fun resumeQueue() = container.downloads.resumeQueue()

    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val history = container.history.snapshot()
                val settings = _ui.value.settings
                val root = org.json.JSONObject()
                root.put("app", "MMTube")
                root.put("version", 1)
                val s = org.json.JSONObject()
                s.put("theme", settings.theme.name)
                s.put("language", settings.language)
                s.put("turbo", settings.turbo)
                s.put("dynamicColor", settings.dynamicColor)
                s.put("colorblindProfile", settings.colorblindProfile)
                s.put("reduceMotion", settings.reduceMotion)
                s.put("downloadFolder", settings.downloadFolder)
                s.put("lastMode", settings.lastMode)
                root.put("settings", s)
                val array = org.json.JSONArray()
                history.forEach { item ->
                    array.put(org.json.JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("sourceUrl", item.sourceUrl)
                        put("platform", item.platform)
                        put("quality", item.quality)
                        put("sizeBytes", item.sizeBytes)
                        put("mimeType", item.mimeType)
                        put("uri", item.uri)
                        put("localPath", item.localPath ?: "")
                        put("thumbnail", item.thumbnail ?: "")
                        put("durationSeconds", item.durationSeconds)
                        put("createdAt", item.createdAt)
                        put("mode", item.mode)
                        put("savedToGallery", item.savedToGallery)
                    })
                }
                root.put("history", array)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(root.toString(2).toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open backup file")
            }
            withContext(Dispatchers.Main) {
                event(UiEvent.Message(
                    context.getString(if (ok.isSuccess) R.string.backup_exported else R.string.backup_failed)
                ))
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: error("Cannot read backup")
                val root = org.json.JSONObject(text)
                val array = root.optJSONArray("history") ?: org.json.JSONArray()
                var imported = 0
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val item = HistoryItem(
                        id = o.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                        title = title,
                        sourceUrl = o.optString("sourceUrl"),
                        platform = o.optString("platform", Platform.OTHER.name),
                        quality = o.optString("quality"),
                        sizeBytes = o.optLong("sizeBytes"),
                        mimeType = o.optString("mimeType", "video/mp4"),
                        uri = o.optString("uri"),
                        localPath = o.optString("localPath").takeIf { it.isNotBlank() },
                        thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                        durationSeconds = o.optInt("durationSeconds"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        mode = o.optString("mode", "max"),
                        savedToGallery = o.optBoolean("savedToGallery")
                    )
                    container.history.add(item)
                    imported++
                }
                root.optJSONObject("settings")?.let { saved ->
                    if (saved.has("theme")) {
                        runCatching {
                            container.settings.setTheme(ThemeMode.valueOf(saved.optString("theme")))
                        }
                    }
                    if (saved.has("language")) {
                        val tag = saved.optString("language")
                        container.settings.setLanguage(tag)
                        val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(tag)
                        withContext(Dispatchers.Main) {
                            AppCompatDelegate.setApplicationLocales(locales)
                        }
                    }
                    if (saved.has("turbo")) container.settings.setTurbo(saved.optBoolean("turbo"))
                    if (saved.has("dynamicColor")) container.settings.setDynamicColor(saved.optBoolean("dynamicColor"))
                    if (saved.has("colorblindProfile")) {
                        container.settings.setColorblindProfile(saved.optString("colorblindProfile"))
                    }
                    if (saved.has("reduceMotion")) container.settings.setReduceMotion(saved.optBoolean("reduceMotion"))
                    if (saved.has("downloadFolder")) {
                        container.settings.setDownloadFolder(saved.optString("downloadFolder"))
                    }
                    if (saved.has("lastMode")) container.settings.setLastMode(saved.optString("lastMode"))
                }
                imported
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { count ->
                    refreshStorageInfo()
                    event(UiEvent.Message(context.getString(R.string.backup_imported, count)))
                }.onFailure {
                    event(UiEvent.Message(context.getString(R.string.backup_failed)))
                }
            }
        }
    }

    fun shareHistory(item: HistoryItem) {
        context.shareFile(Uri.parse(item.uri), item.mimeType, item.title)
    }

    fun deleteHistory(item: HistoryItem) {
        viewModelScope.launch {
            runCatching { container.exporter.delete(item) }
            container.history.remove(item.id)
            refreshStorageInfo()
            event(UiEvent.Message(context.getString(R.string.action_delete)))
        }
    }

    fun deleteHistoryItems(items: List<HistoryItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            items.forEach { item ->
                runCatching { container.exporter.delete(item) }
                container.history.remove(item.id)
            }
            refreshStorageInfo()
            event(UiEvent.Message(context.getString(R.string.downloads_deleted_count, items.size)))
        }
    }

    fun setDownloadFolder(relativePath: String) {
        viewModelScope.launch {
            container.settings.setDownloadFolder(relativePath)
            event(UiEvent.Message(context.getString(R.string.settings_download_folder_saved)))
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
    fun setColorblindProfile(profile: String) { viewModelScope.launch { container.settings.setColorblindProfile(profile) } }
    fun setDynamicColor(v: Boolean) { viewModelScope.launch { container.settings.setDynamicColor(v) } }

    fun setTurbo(enabled: Boolean) {
        viewModelScope.launch { container.settings.setTurbo(enabled) }
    }

    fun setAutoEngineUpdate(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoEngineUpdate(enabled) }
    }

    fun setTurboProfile(profile: String) {
        viewModelScope.launch { container.settings.setTurboProfile(profile) }
    }

    fun setChunkMb(mb: Int) {
        viewModelScope.launch { container.settings.setChunkMb(mb) }
    }

    fun setSpeedMode(enabled: Boolean) {
        viewModelScope.launch { container.settings.setSpeedMode(enabled) }
    }

    fun importCookies(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = com.mmtube.app.util.CookieStore.import(context, uri)
            withContext(Dispatchers.Main) {
                event(
                    UiEvent.Message(
                        context.getString(
                            if (ok) R.string.cookies_imported else R.string.cookies_invalid
                        )
                    )
                )
            }
        }
    }

    fun clearCookies() {
        com.mmtube.app.util.CookieStore.clear(context)
        event(UiEvent.Message(context.getString(R.string.cookies_cleared)))
    }

    fun setLoudnessTarget(value: Int) {
        viewModelScope.launch { container.settings.setLoudnessTarget(value) }
    }

    private fun applyAdvancedOptions(
        request: com.mmtube.app.download.DownloadRequest
    ): com.mmtube.app.download.DownloadRequest {
        val settings = _ui.value.settings
        return request.copy(
            embedChapters = settings.embedChapters,
            multiVideoStreams = settings.multiVideoStreams,
            multiAudioStreams = settings.multiAudioStreams,
            keepFragments = settings.keepFragments,
            hlsUseMpegts = settings.hlsUseMpegts,
            proxyUrl = settings.proxyUrl,
            sourceAddress = settings.sourceAddress
        )
    }

    fun setEmbedChapters(enabled: Boolean) {
        viewModelScope.launch { container.settings.setEmbedChapters(enabled) }
    }

    fun setMultiVideoStreams(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMultiVideoStreams(enabled) }
    }

    fun setMultiAudioStreams(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMultiAudioStreams(enabled) }
    }

    fun setKeepFragments(enabled: Boolean) {
        viewModelScope.launch { container.settings.setKeepFragments(enabled) }
    }

    fun setHlsMpegts(enabled: Boolean) {
        viewModelScope.launch { container.settings.setHlsMpegts(enabled) }
    }

    fun setProxyUrl(value: String) {
        viewModelScope.launch { container.settings.setProxyUrl(value) }
    }

    fun setSingleProcessBatch(enabled: Boolean) {
        viewModelScope.launch { container.settings.setSingleProcessBatch(enabled) }
    }

    fun setSourceAddress(value: String) {
        viewModelScope.launch { container.settings.setSourceAddress(value) }
    }

    fun setThrottledRate(kb: Int) {
        viewModelScope.launch { container.settings.setThrottledRate(kb) }
    }

    fun setCheckFormats(enabled: Boolean) {
        viewModelScope.launch { container.settings.setCheckFormats(enabled) }
    }

    fun setBufferSize(kb: Int) {
        viewModelScope.launch { container.settings.setBufferSize(kb) }
    }

    fun setForceIpv4(enabled: Boolean) {
        viewModelScope.launch { container.settings.setForceIpv4(enabled) }
    }

    fun setSortForce(enabled: Boolean) {
        viewModelScope.launch { container.settings.setSortForce(enabled) }
    }

    fun setVerifyDownloads(enabled: Boolean) {
        viewModelScope.launch { container.settings.setVerifyDownloads(enabled) }
    }

    fun setPreferMultichannel(enabled: Boolean) {
        viewModelScope.launch { container.settings.setPreferMultichannel(enabled) }
    }

    fun setAutoStorage(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoStorage(enabled) }
    }

    fun setPreserveAudioDynamics(enabled: Boolean) {
        viewModelScope.launch { container.settings.setPreserveAudioDynamics(enabled) }
    }

    fun setAutoChunk(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoChunk(enabled) }
    }

    fun setAudioFormat(value: String) {
        viewModelScope.launch { container.settings.setAudioFormat(value) }
    }

    fun setAdaptiveProfile(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAdaptiveProfile(enabled) }
    }

    fun setParallelStreams(enabled: Boolean) {
        viewModelScope.launch { container.settings.setParallelStreams(enabled) }
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
                        val dismissedVersion = runCatching {
                            container.settings.snapshot().dismissedUpdateVersion
                        }.getOrDefault("")
                        _ui.update {
                            it.copy(
                                update = it.update.copy(
                                    info = info,
                                    // "Later" suppresses the automatic dialog only;
                                    // a manual check should always show the update.
                                    dismissed = silent && info.version == dismissedVersion
                                )
                            )
                        }
                        // Auto-update: silently download the update in the background.
                        // Android only allows install after the user confirms in the
                        // system installer, but this removes the need to manually
                        // trigger the download — or even the installer launch.
                        if (silent && container.settings.snapshot().autoAppUpdate) {
                            val downloaded = container.updater.findDownloaded(info)
                            if (downloaded != null) {
                                _ui.update { it.copy(update = it.update.copy(info = info, file = downloaded, progress = 100)) }
                                showUpdateReadyNotification()
                                if (!_ui.value.update.dismissed) {
                                    maybeLaunchInstallerOnce(info.version)
                                }
                            } else {
                                downloadUpdate(autoInstall = true)
                            }
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
            if (!container.settings.snapshot().autoAppUpdate) return@launch
            // Check on every app entry (silent) — the user asked for updates to
            // be found and applied automatically as soon as the app is opened.
            checkForUpdates(silent = silent)
        }
    }

    fun downloadUpdate(autoInstall: Boolean = false) {
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
                showUpdateReadyNotification()
                event(UiEvent.Message(context.getString(R.string.update_ready)))
                if (autoInstall && !_ui.value.update.dismissed) {
                    // Launch the system installer once per version. If the user
                    // backs out, the notification stays — no repeated popups on
                    // every later app entry.
                    maybeLaunchInstallerOnce(info.version)
                }
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
        val version = _ui.value.update.info?.version
        _ui.update { it.copy(update = it.update.copy(dismissed = true)) }
        if (!version.isNullOrBlank()) {
            viewModelScope.launch {
                container.settings.setUpdateDismissedVersion(version)
            }
        }
    }

    /** True the first time this version becomes installable; after that the
     *  installer is only opened from the dialog or the notification. */
    private fun maybeLaunchInstallerOnce(version: String) {
        viewModelScope.launch {
            val prompted = runCatching { container.settings.snapshot().installPromptedVersion }
                .getOrDefault("")
            if (prompted == version) return@launch
            container.settings.setInstallPromptedVersion(version)
            installUpdate()
        }
    }

    fun requestInstallUpdate() {
        installUpdate()
    }

    fun requestOpenUpgrade() {
        event(UiEvent.OpenUpgrade)
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

    fun errorText(kind: ErrorKind): String {
        val base = when (kind) {
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
        val hintRes = when (kind) {
            ErrorKind.PRIVATE -> R.string.error_hint_private
            ErrorKind.AGE -> R.string.error_hint_age
            ErrorKind.UNAVAILABLE -> R.string.error_hint_unavailable
            ErrorKind.NETWORK -> R.string.error_hint_network
            ErrorKind.NO_SPACE -> R.string.error_hint_no_space
            ErrorKind.DOWNLOAD -> R.string.error_hint_download
            ErrorKind.UNSUPPORTED -> R.string.error_hint_unsupported
            ErrorKind.QUOTA -> R.string.error_hint_quota
            else -> 0
        }
        val hint = if (hintRes == 0) "" else context.getString(hintRes)
        return if (hint.isBlank()) base else base + "\n" + hint
    }

    private fun event(event: UiEvent) {
        _events.trySend(event)
    }

    companion object {
        private const val QUEUE_NOTIFICATION_CHANNEL = "mmtube_queue"
        private const val QUEUE_NOTIFICATION_ID = 7700
        private const val UPDATE_NOTIFICATION_CHANNEL = "mmtube_update"
        private const val UPDATE_NOTIFICATION_ID = 7701
        private const val PLAN_REMINDER_CHANNEL = "mmtube_plan_reminder"
        private const val PLAN_REMINDER_NOTIFICATION_ID = 7702
        private const val QUOTA_BLOCK_NOTIFICATION_ID = 7703

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
