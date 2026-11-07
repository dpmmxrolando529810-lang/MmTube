package com.mmtube.app.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.TimeUnit

class QuotaManager(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsStore,
    private val firestore: FirebaseFirestore?
) {

    suspend fun current(): QuotaState {
        val now = System.currentTimeMillis()
        return when (val auth = authRepository.state.value) {
            is AuthState.SignedIn -> {
                val account = accountRepository.ui.value
                val profile = account.profile
                if (profile == null) {
                    return QuotaState(
                        allowed = false,
                        paid = false,
                        guest = false,
                        reason = "loading"
                    )
                }
                if (profile.disabled) {
                    return QuotaState(
                        allowed = false,
                        paid = false,
                        guest = false,
                        disabled = true,
                        reason = "disabled"
                    )
                }
                if (profile.isPaid) {
                    val total = maxOf(
                        account.usage?.totalDownloads ?: 0,
                        settings.localTotalDownloads(profile.uid)
                    )
                    return QuotaState(
                        allowed = true,
                        paid = true,
                        guest = false,
                        totalDownloads = total
                    )
                }
                val usage = account.usage ?: readUsage(profile.uid)
                val localNext = settings.localNextAllowed(profile.uid) ?: 0L
                val resetAt = usage?.quotaResetAt ?: 0L
                // An admin reset invalidates cooldowns recorded before it.
                val effectiveLocal = if (localNext > resetAt) localNext else 0L
                val next = maxOf(usage?.nextAllowedAt ?: 0L, effectiveLocal)
                val total = maxOf(
                    usage?.totalDownloads ?: 0,
                    settings.localTotalDownloads(profile.uid)
                )
                QuotaState(
                    allowed = now >= next,
                    paid = false,
                    guest = false,
                    nextAllowedAt = next.takeIf { it > now },
                    totalDownloads = total
                )
            }

            AuthState.SignedOut -> {
                val snapshot = settings.snapshot()
                val next = snapshot.guestNextAllowedAt
                QuotaState(
                    allowed = now >= next,
                    paid = false,
                    guest = true,
                    nextAllowedAt = next.takeIf { it > now },
                    totalDownloads = snapshot.guestTotalDownloads
                )
            }

            else -> QuotaState(
                allowed = false,
                paid = false,
                guest = false,
                reason = "loading"
            )
        }
    }

    suspend fun consume() {
        val now = System.currentTimeMillis()
        val next = now + TimeUnit.HOURS.toMillis(24)
        val auth = authRepository.state.value
        if (auth !is AuthState.SignedIn) {
            settings.recordGuestDownload(next)
            return
        }
        val profile = accountRepository.ui.value.profile ?: return
        val total = maxOf(
            accountRepository.ui.value.usage?.totalDownloads ?: 0,
            settings.localTotalDownloads(profile.uid)
        ) + 1
        settings.setLocalTotalDownloads(profile.uid, total)
        if (!profile.isPaid) {
            settings.setLocalNextAllowed(profile.uid, next)
        }
        val db = firestore ?: return
        val values = mutableMapOf<String, Any>(
            "lastDownloadAt" to Timestamp(Date(now)),
            "totalDownloads" to FieldValue.increment(1),
            "updatedAt" to Timestamp(Date(now))
        )
        if (!profile.isPaid) {
            values["nextAllowedAt"] = Timestamp(Date(next))
        }
        runCatching {
            db.collection("usage").document(profile.uid)
                .set(values, SetOptions.merge())
                .await()
        }.onFailure {
            Log.w("QuotaManager", "Could not sync usage", it)
        }
    }

    private suspend fun readUsage(uid: String): UsageInfo? {
        val db = firestore ?: return null
        return runCatching {
            val doc = db.collection("usage").document(uid).get().await()
            if (doc.exists()) doc.toUsage() else null
        }.getOrNull()
    }

    private fun DocumentSnapshot.toUsage(): UsageInfo = UsageInfo(
        lastDownloadAt = getTimestamp("lastDownloadAt")?.toDate()?.time,
        nextAllowedAt = getTimestamp("nextAllowedAt")?.toDate()?.time,
        totalDownloads = getLong("totalDownloads")?.toInt() ?: 0,
        quotaResetAt = getTimestamp("quotaResetAt")?.toDate()?.time
    )
}
