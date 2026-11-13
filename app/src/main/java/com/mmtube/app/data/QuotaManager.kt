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

/**
 * Server-enforced download quota. The cooldown clock must never trust the
 * device clock (users can change it) — a <sys/now> round-trip gives a
 * trustworthy wall-clock reading.
 */
class QuotaManager(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsStore,
    private val firestore: FirebaseFirestore?
) {

    private var cachedNow: Long = 0L
    private var cachedNowAt: Long = 0L

    private suspend fun serverNowMs(): Long {
        val ttlMs = 30_000L
        if (System.currentTimeMillis() - cachedNowAt < ttlMs && cachedNow > 0L) return cachedNow
        val db = firestore ?: return System.currentTimeMillis()
        return runCatching {
            db.collection("sys").document("now")
                .set(mapOf("now" to FieldValue.serverTimestamp()), SetOptions.merge())
                .await()
            val doc = db.collection("sys").document("now").get().await()
            cachedNow = doc.getTimestamp("now")?.toDate()?.time ?: System.currentTimeMillis()
            cachedNowAt = System.currentTimeMillis()
            cachedNow
        }.getOrDefault(System.currentTimeMillis())
    }

    suspend fun current(): QuotaState {
        if (authRepository.state.value is AuthState.Loading) {
            return QuotaState(allowed = false, paid = false, guest = false, reason = "loading")
        }
        val now = serverNowMs()
        return when (val auth = authRepository.state.value) {
            is AuthState.SignedIn, is AuthState.Anonymous -> signedInQuota(now, auth)
            else -> guestQuota(now)
        }
    }

    private suspend fun signedInQuota(now: Long, auth: AuthState): QuotaState {
        val account = accountRepository.ui.value
        val profile = account.profile
        if (profile == null) {
            return QuotaState(allowed = false, paid = false, guest = false, reason = "loading")
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
        val paid = profile.isPaid
        val trial = profile.isTrial
        val grace = profile.inGracePeriod
        val fullAccess = paid || trial || grace
        val total = maxOf(
            account.usage?.totalDownloads ?: 0,
            settings.localTotalDownloads(profile.uid)
        )
        if (fullAccess) {
            return QuotaState(
                allowed = true,
                paid = paid,
                trial = trial,
                guest = false,
                totalDownloads = total
            )
        }
        val usage = account.usage ?: readUsage(profile.uid)
        val localNext = settings.localNextAllowed(profile.uid) ?: 0L
        val resetAt = usage?.quotaResetAt ?: 0L
        val effectiveLocal = if (localNext > resetAt) localNext else 0L
        val next = maxOf(usage?.nextAllowedAt ?: 0L, effectiveLocal)
        return QuotaState(
            allowed = now >= next,
            paid = false,
            guest = false,
            nextAllowedAt = next.takeIf { it > now },
            totalDownloads = total
        )
    }

    private suspend fun guestQuota(now: Long): QuotaState {
        // Last-resort fallback: fully offline + never signed in.
        val snapshot = settings.snapshot()
        val next = snapshot.guestNextAllowedAt
        return QuotaState(
            allowed = now >= next,
            paid = false,
            guest = true,
            nextAllowedAt = next.takeIf { it > now },
            totalDownloads = snapshot.guestTotalDownloads
        )
    }

    suspend fun consume() {
        val now = System.currentTimeMillis()
        val next = now + TimeUnit.HOURS.toMillis(24)
        val auth = authRepository.state.value
        if (auth !is AuthState.SignedIn && auth !is AuthState.Anonymous) {
            settings.recordGuestDownload(next)
            return
        }
        val uid = when (auth) {
            is AuthState.SignedIn -> auth.uid
            is AuthState.Anonymous -> auth.uid
            else -> return
        }
        val profile = accountRepository.ui.value.profile ?: return
        val total = maxOf(
            accountRepository.ui.value.usage?.totalDownloads ?: 0,
            settings.localTotalDownloads(uid)
        ) + 1
        settings.setLocalTotalDownloads(uid, total)
        val fullAccess = profile.hasFullAccess
        if (!fullAccess) {
            settings.setLocalNextAllowed(uid, next)
        }
        val db = firestore ?: return
        val values = mutableMapOf<String, Any>(
            "lastDownloadAt" to Timestamp(Date(now)),
            "totalDownloads" to FieldValue.increment(1),
            "updatedAt" to Timestamp(Date(now))
        )
        if (!fullAccess) {
            values["nextAllowedAt"] = Timestamp(Date(next))
        }
        runCatching {
            db.collection("usage").document(uid)
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
