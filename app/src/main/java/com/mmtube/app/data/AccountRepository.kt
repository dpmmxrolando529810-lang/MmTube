package com.mmtube.app.data

import android.app.Activity
import android.util.Log
import com.google.firebase.Timestamp
import java.util.Date
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AccountRepository(
    private val authRepository: AuthRepository,
    private val firestore: FirebaseFirestore?,
    private val scope: CoroutineScope,
    private val settings: SettingsStore? = null
) {

    private val _ui = MutableStateFlow(AccountUi(auth = authRepository.state.value))
    val ui: StateFlow<AccountUi> = _ui.asStateFlow()

    private var userListener: ListenerRegistration? = null
    private var usageListener: ListenerRegistration? = null
    private var currentUid: String? = null

    init {
        scope.launch {
            authRepository.state.collect { state ->
                detach()
                when (state) {
                    is AuthState.SignedIn -> loadProfile(state, anonymous = false)
                    is AuthState.Anonymous -> loadProfile(AuthState.SignedIn(state.uid, null, null, null), anonymous = true)
                    else -> {
                        currentUid = null
                        _ui.value = AccountUi(auth = state)
                    }
                }
            }
        }
    }

    val currentUserCode: String?
        get() = _ui.value.profile?.userCode

    suspend fun signIn(activity: Activity): Result<Unit> =
        authRepository.signIn(activity).map { Unit }

    suspend fun signInWithEmail(
        email: String,
        password: String,
        createAccount: Boolean
    ): Result<Unit> =
        authRepository.signInWithEmail(email, password, createAccount).map { Unit }

    fun signOut() {
        detach()
        authRepository.signOut()
        _ui.value = AccountUi(auth = AuthState.SignedOut)
    }

    suspend fun refresh() {
        val state = authRepository.state.value as? AuthState.SignedIn ?: return
        val db = firestore ?: return
        runCatching {
            val profile = db.collection("users").document(state.uid).get().await()
            val usage = db.collection("usage").document(state.uid).get().await()
            _ui.update {
                it.copy(
                    profile = if (profile.exists()) profile.toProfile() else it.profile,
                    usage = if (usage.exists()) usage.toUsage() else it.usage ?: UsageInfo(),
                    loading = false,
                    error = null
                )
            }
        }.onFailure { error ->
            _ui.update { it.copy(loading = false, error = error.message) }
        }
    }

    suspend fun deleteAccountData(): Result<Unit> {
        val state = authRepository.state.value as? AuthState.SignedIn
            ?: return Result.failure(MmtubeException(ErrorKind.FIREBASE, "Not signed in"))
        val db = firestore
            ?: return Result.failure(MmtubeException(ErrorKind.FIREBASE, "Cloud not configured"))
        return runCatching {
            // Best effort: delete Firebase Auth user too (requires recent login).
            runCatching { authRepository.deleteAuthAccount().getOrNull() }
            db.collection("usage").document(state.uid).delete().await()
            db.collection("users").document(state.uid).delete().await()
            signOut()
            Unit
        }.recoverCatching { error ->
            throw MmtubeException(ErrorKind.FIREBASE, error.message, error)
        }
    }

    private suspend fun loadProfile(state: AuthState.SignedIn, anonymous: Boolean = false) {
        val db = firestore
        if (db == null) {
            _ui.value = AccountUi(
                auth = state,
                loading = false,
                error = "Cloud accounts are not configured"
            )
            return
        }
        currentUid = state.uid
        _ui.update { it.copy(auth = state, loading = true, error = null) }
        try {
            val userRef = db.collection("users").document(state.uid)
            val snapshot = userRef.get().await()
            if (!snapshot.exists()) {
                val profile = mapOf(
                    "uid" to state.uid,
                    "email" to state.email,
                    "displayName" to state.displayName,
                    "photoUrl" to state.photoUrl,
                    "userCode" to UserCode.fromUid(state.uid),
                    "plan" to Plans.FREE,
                    "planExpiresAt" to null,
                    "dailyLimit" to 1,
                    "disabled" to false,
                    "anonymous" to anonymous,
                    "trialEndsAt" to if (anonymous) null else Timestamp(Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000)),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(profile).await()
            }
            userListener = userRef.addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.w("AccountRepository", "User listener failed", error)
                    _ui.update { it.copy(loading = false, error = error.message) }
                    return@addSnapshotListener
                }
                if (doc != null && doc.exists()) {
                    // Admin room: "Sign out user" — if the flag is newer than our last-seen marker, sign out.
                    val forcedAt = doc.getTimestamp("forceSignOutAt")?.toDate()?.time ?: 0L
                    if (settings != null) {
                        scope.launch {
                            val seen = settings.snapshot().lastForceSignOutSeen
                            if (forcedAt > seen) {
                                settings.setLastForceSignOutSeen(forcedAt)
                                detach()
                                authRepository.signOut()
                                _ui.value = AccountUi(auth = AuthState.SignedOut)
                            }
                        }
                        // Update UI optimistically; the sign-out path will wipe it.
                        _ui.update {
                            it.copy(
                                profile = doc.toProfile(),
                                loading = false,
                                error = null
                            )
                        }
                        return@addSnapshotListener
                    }
                    _ui.update {
                        it.copy(
                            profile = doc.toProfile(),
                            loading = false,
                            error = null
                        )
                    }
                }
            }
            usageListener = db.collection("usage").document(state.uid)
                .addSnapshotListener { doc, error ->
                    if (error != null) {
                        Log.w("AccountRepository", "Usage listener failed", error)
                        return@addSnapshotListener
                    }
                    _ui.update {
                        it.copy(usage = if (doc != null && doc.exists()) doc.toUsage() else UsageInfo())
                    }
                }
        } catch (error: Throwable) {
            Log.w("AccountRepository", "Profile load failed", error)
            _ui.update {
                it.copy(
                    loading = false,
                    error = error.message ?: "Could not load account"
                )
            }
        }
    }

    suspend fun purchaseHistory(): List<PurchaseRecord> {
        val state = authRepository.state.value as? AuthState.SignedIn ?: return emptyList()
        val db = firestore ?: return emptyList()
        return runCatching {
            db.collection("activationLog").whereEqualTo("uid", state.uid).get().await()
                .documents.mapNotNull { doc ->
                    val at = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    val plan = doc.getString("plan") ?: return@mapNotNull null
                    val months = doc.getLong("months")?.toInt() ?: 0
                    val source = doc.getString("source") ?: "admin"
                    PurchaseRecord(plan = plan, months = months, amountKs = doc.getLong("amountKs") ?: 0L, createdAt = at, source = source)
                }
                .sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    private fun detach() {
        userListener?.remove()
        usageListener?.remove()
        userListener = null
        usageListener = null
    }

    private fun DocumentSnapshot.toProfile(): UserProfile {
        val uid = getString("uid") ?: id
        return UserProfile(
            uid = uid,
            email = getString("email"),
            displayName = getString("displayName"),
            photoUrl = getString("photoUrl"),
            userCode = getString("userCode") ?: UserCode.fromUid(uid),
            plan = getString("plan") ?: Plans.FREE,
            planExpiresAt = getTimestamp("planExpiresAt")?.toDate()?.time,
            dailyLimit = getLong("dailyLimit")?.toInt() ?: 1,
            disabled = getBoolean("disabled") ?: false,
            createdAt = getTimestamp("createdAt")?.toDate()?.time,
            trialEndsAt = getTimestamp("trialEndsAt")?.toDate()?.time,
            anonymous = getBoolean("anonymous") ?: false,
            forceSignOutAt = getTimestamp("forceSignOutAt")?.toDate()?.time
        )
    }

    private fun DocumentSnapshot.toUsage(): UsageInfo = UsageInfo(
        lastDownloadAt = getTimestamp("lastDownloadAt")?.toDate()?.time,
        nextAllowedAt = getTimestamp("nextAllowedAt")?.toDate()?.time,
        totalDownloads = getLong("totalDownloads")?.toInt() ?: 0,
        quotaResetAt = getTimestamp("quotaResetAt")?.toDate()?.time
    )
}
