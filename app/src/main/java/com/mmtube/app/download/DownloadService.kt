package com.mmtube.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mmtube.app.MMTubeApp
import com.mmtube.app.MainActivity
import com.mmtube.app.R
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.Platform
import com.mmtube.app.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false

    private val controller
        get() = (application as MMTubeApp).container.downloads

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = intent.toDownloadRequest()
                if (request == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                finished = false
                startForegroundNow(request)
                acquireWakeLock()
                observer?.cancel()
                observer = scope.launch {
                    controller.state.collectLatest { state ->
                        if (state is DownloadState.Idle) return@collectLatest
                        updateNotification(state)
                        if (state is DownloadState.Completed ||
                            state is DownloadState.Failed ||
                            state is DownloadState.Cancelled ||
                            state is DownloadState.Paused
                        ) {
                            finishService()
                        }
                    }
                }
                controller.start(request)
            }

            ACTION_CANCEL -> {
                controller.cancel()
                finishService()
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow(request: DownloadRequest) {
        val notification = buildNotification(
            DownloadState.Preparing(request.title, request.mode)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(state: DownloadState) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(state)
            )
        }
    }

    private fun buildNotification(state: DownloadState): android.app.Notification {
        val active = state is DownloadState.Preparing ||
            state is DownloadState.Running ||
            state is DownloadState.Merging ||
            state is DownloadState.Saving
        val paused = state is DownloadState.Paused

        val title = when (state) {
            is DownloadState.Preparing -> getString(R.string.notification_downloading, state.title)
            is DownloadState.Running -> getString(R.string.notification_downloading, state.title)
            is DownloadState.Merging -> getString(R.string.download_status_merging)
            is DownloadState.Saving -> getString(R.string.download_status_saving)
            is DownloadState.Completed -> getString(R.string.notification_complete)
            is DownloadState.Failed -> getString(R.string.notification_failed)
            is DownloadState.Paused -> getString(R.string.download_status_paused)
            is DownloadState.Cancelled -> getString(R.string.download_status_cancelled)
            DownloadState.Idle -> getString(R.string.app_name)
        }

        val text = when (state) {
            is DownloadState.Preparing -> state.title
            is DownloadState.Running -> {
                val percent = Formatters.progressPercent(state.progress)
                buildList {
                    add("$percent%")
                    if (state.etaSeconds >= 0) {
                        add("⏱ " + getString(R.string.notification_eta, Formatters.formatEta(state.etaSeconds)))
                    }
                    if (state.speedBytes > 0) {
                        add("⬇ " + getString(R.string.download_speed, Formatters.formatBytes(state.speedBytes)))
                    }
                }.joinToString("  ·  ")
            }
            is DownloadState.Merging -> state.title
            is DownloadState.Saving -> state.title
            is DownloadState.Completed -> state.item.title
            is DownloadState.Failed -> state.title
            is DownloadState.Paused -> getString(R.string.download_status_paused)
            is DownloadState.Cancelled -> getString(R.string.app_name)
            DownloadState.Idle -> ""
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mmtube)
            .setColor(ContextCompat.getColor(this, R.color.mmtube_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(!active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            is DownloadState.Running -> {
                val percent = Formatters.progressPercent(state.progress)
                builder.setProgress(100, percent, state.progress <= 0f)
            }
            is DownloadState.Paused -> {
                builder.setProgress(100, Formatters.progressPercent(state.progress), false)
            }
            is DownloadState.Preparing, is DownloadState.Merging, is DownloadState.Saving ->
                builder.setProgress(0, 0, true)
            else -> builder.setProgress(0, 0, false)
        }

        if (active) {
            builder.addAction(
                R.drawable.ic_notification_cancel,
                getString(R.string.notification_cancel),
                cancelPendingIntent()
            )
        } else if (paused) {
            builder.addAction(
                R.drawable.ic_notification_open,
                getString(R.string.download_notification_resume),
                openAppPendingIntent()
            )
        } else {
            val completed = state as? DownloadState.Completed
            builder.addAction(
                R.drawable.ic_notification_open,
                if (completed?.item?.mimeType?.startsWith("audio") == true)
                    getString(R.string.notification_open_music)
                else getString(R.string.notification_open_file),
                if (completed != null) openFilePendingIntent(completed.item) else openAppPendingIntent()
            )
        }
        return builder.build()
    }

    private fun openFilePendingIntent(item: com.mmtube.app.data.HistoryItem): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_FILE_URI, item.uri)
            putExtra(MainActivity.EXTRA_OPEN_FILE_MIME, item.mimeType)
        }
        return PendingIntent.getActivity(
            this,
            103,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun finishService() {
        if (finished) return
        finished = true
        observer?.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMTube:download").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun Intent.toDownloadRequest(): DownloadRequest? {
        val url = getStringExtra(EXTRA_URL) ?: return null
        val title = getStringExtra(EXTRA_TITLE) ?: "Video"
        val platform = runCatching {
            Platform.valueOf(getStringExtra(EXTRA_PLATFORM) ?: Platform.OTHER.name)
        }.getOrDefault(Platform.OTHER)
        val mode = DownloadMode.fromKey(getStringExtra(EXTRA_MODE))
        val quality = getStringExtra(EXTRA_QUALITY) ?: Formatters.modeLabel(mode)
        return DownloadRequest(
            url = url,
            title = title,
            platform = platform,
            mode = mode,
            thumbnail = getStringExtra(EXTRA_THUMBNAIL),
            durationSeconds = getIntExtra(EXTRA_DURATION, 0),
            qualityLabel = quality,
            resume = getBooleanExtra(EXTRA_RESUME, false)
        )
    }

    companion object {
        private const val CHANNEL_ID = "mmtube_downloads"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.mmtube.app.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.mmtube.app.action.CANCEL_DOWNLOAD"

        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_THUMBNAIL = "thumbnail"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_RESUME = "resume"

        fun start(context: Context, request: DownloadRequest) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, request.url)
                putExtra(EXTRA_TITLE, request.title)
                putExtra(EXTRA_PLATFORM, request.platform.name)
                putExtra(EXTRA_MODE, request.mode.key)
                putExtra(EXTRA_QUALITY, request.qualityLabel)
                putExtra(EXTRA_THUMBNAIL, request.thumbnail)
                putExtra(EXTRA_DURATION, request.durationSeconds)
                putExtra(EXTRA_RESUME, request.resume)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
