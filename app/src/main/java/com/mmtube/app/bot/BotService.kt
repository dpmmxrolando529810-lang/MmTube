package com.mmtube.app.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mmtube.app.MainActivity
import com.mmtube.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the owner bot polling while the phone is on.
 *
 * Uses the `specialUse` foreground type because the bot has no time limit
 * there (dataSync is capped at 6 h / 24 h on Android 15+).
 */
class BotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runnerJob: Job? = null
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground("Bot စတင်နေတယ်…")
        observerJob = scope.launch {
            BotRuntime.status.collect { status -> updateNotification(status) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BotSecrets.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startBot()
        return START_STICKY
    }

    override fun onDestroy() {
        BotRuntime.update { it.copy(running = false, jobTitle = null, jobLabel = null) }
        observerJob?.cancel()
        runnerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ bot

    private fun startBot() {
        if (runnerJob?.isActive == true) return
        if (BotSecrets.token(this).isNullOrBlank()) {
            Log.w(TAG, "No token — stopping service")
            stopSelf()
            return
        }
        BotRuntime.update {
            it.copy(
                running = true,
                username = BotSecrets.botUsername(this),
                ownerChatId = BotSecrets.ownerChatId(this),
                lastError = null
            )
        }
        runnerJob = scope.launch {
            try {
                BotRunner(this@BotService).run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Bot stopped", error)
                BotRuntime.update {
                    it.copy(running = false, lastError = error.message)
                }
                BotSecrets.setEnabled(this@BotService, false)
                stopSelf()
            }
        }
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MMTube Bot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Owner bot လည်နေကြောင်း အသိပေးချက်"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "Could not start foreground", it)
            stopSelf()
        }
    }

    private fun updateNotification(status: BotStatus) {
        val text = when {
            status.jobTitle != null -> {
                val percent = (status.jobProgress.toInt()).coerceIn(0, 100)
                "${status.jobLabel ?: "ဒေါင်းနေတယ်"} · $percent%"
            }

            status.username != null -> "လည်နေတယ် · @${status.username}"
            else -> "လည်နေတယ်"
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mmtube)
            .setContentTitle("MMTube Bot")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(0, "ရပ်", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "BotService"
        private const val CHANNEL_ID = "mmtube_bot"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.mmtube.app.bot.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            BotSecrets.setEnabled(context, false)
            context.stopService(Intent(context, BotService::class.java))
        }
    }
}
