package com.mmtube.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.google.firebase.firestore.FirebaseFirestore
import com.mmtube.app.MainActivity
import com.mmtube.app.R
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Checks a submitted payment while the app is closed and notifies the user
 * once the admin approves or rejects it. No server/FCM key is needed: the
 * Firestore payment document is the single source of truth.
 */
class PaymentStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val paymentId = inputData.getString(KEY_PAYMENT_ID).orEmpty()
        if (paymentId.isBlank()) return Result.success()

        val prefs = applicationContext.getSharedPreferences("mmtube_payment_watch", Context.MODE_PRIVATE)
        val startedAt = prefs.getLong("$paymentId:started", System.currentTimeMillis())
        if (startedAt == 0L) {
            prefs.edit().putLong("$paymentId:started", System.currentTimeMillis()).apply()
        } else if (System.currentTimeMillis() - startedAt > 7L * 24 * 3600 * 1000) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(paymentId))
            return Result.success()
        }
        if (prefs.getBoolean("$paymentId:notified", false)) return Result.success()

        val app = FirebaseBootstrap.init(applicationContext) ?: return Result.success()
        val db = runCatching { FirebaseFirestore.getInstance(app) }.getOrNull() ?: return Result.success()
        val snapshot = runCatching {
            db.collection("payments").document(paymentId).get().await()
        }.getOrNull() ?: return Result.retry()

        val status = snapshot.getString("status")?.lowercase().orEmpty()
        if (status == "pending" || status.isBlank()) return Result.success()

        val approved = status == "approved" || status == "active"
        val note = snapshot.getString("adminNote").orEmpty()
        prefs.edit().putBoolean("$paymentId:notified", true).apply()
        showNotification(approved, note)
        WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(paymentId))
        return Result.success()
    }

    private fun showNotification(approved: Boolean, note: String) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.payment_notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val title = applicationContext.getString(
            if (approved) R.string.payment_notif_approved_title else R.string.payment_notif_rejected_title
        )
        val base = applicationContext.getString(
            if (approved) R.string.payment_notif_approved_body else R.string.payment_notif_rejected_body
        )
        val text = if (note.isBlank()) base else "$base\n$note"
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(applicationContext, 7801, intent, flags)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mmtube)
            .setColor(ContextCompat.getColor(applicationContext, R.color.mmtube_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager?.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "mmtube_payment_status"
        private const val NOTIFICATION_ID = 7802
        private const val KEY_PAYMENT_ID = "payment_id"

        fun uniqueName(paymentId: String) = "payment_status_$paymentId"

        fun enqueue(context: Context, paymentId: String) {
            val data = androidx.work.Data.Builder().putString(KEY_PAYMENT_ID, paymentId).build()
            val request = PeriodicWorkRequestBuilder<PaymentStatusWorker>(30, TimeUnit.MINUTES)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    uniqueName(paymentId),
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
