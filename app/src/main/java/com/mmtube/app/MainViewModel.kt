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
    val showCelebration: Boolean = false
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
                            mode = if (result.audioOnly) DownloadMode.MP3 else DownloadMode.MAX,
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
                _ui.update { it.copy(storageFullPrompt = true) }
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
                showQuotaBlockNotification()
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

    fun submitPayment(plan: String, buyerPhone: String, note: String, imageBase64: String, onDone: (Boolean, String?) -> Unit) {
        val profile = _ui.value.account.profile
        val db = container.firestore
        if (profile == null || db == null) {
            onDone(false, "Sign in လုပ်ပါ")
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
            onDone(false, "Plan နဲ့ ငွေပြေစာပုံ ဖြည့်ပါ")
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("payments").add(
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
            }
            result
                .onSuccess { onDone(true, null) }
                .onFailure { onDone(false, it.message) }
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
                showUpdateReadyNotification()
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
