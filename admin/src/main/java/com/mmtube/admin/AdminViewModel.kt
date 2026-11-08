package com.mmtube.admin

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date

class AdminViewModel(private val container: AdminContainer) : ViewModel() {

    private val context: Context = container.appContext
    private val auth: FirebaseAuth? = container.auth
    private val firestore: FirebaseFirestore? = container.firestore

    private val _ui = MutableStateFlow(
        AdminUiState(auth = if (auth == null) AdminAuthState.NotConfigured else AdminAuthState.Loading)
    )
    val ui: StateFlow<AdminUiState> = _ui.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user == null) {
            _ui.update { it.copy(auth = AdminAuthState.SignedOut, user = null, searched = false, query = "") }
        } else {
            checkAdmin(user.uid, user.email, user.photoUrl?.toString())
        }
    }

    init {
        auth?.addAuthStateListener(authListener)
    }

    override fun onCleared() {
        auth?.removeAuthStateListener(authListener)
        super.onCleared()
    }

    private fun checkAdmin(uid: String, email: String?, photo: String?) {
        val db = firestore
        if (db == null) {
            _ui.update { it.copy(auth = AdminAuthState.NotConfigured) }
            return
        }
        _ui.update { it.copy(auth = AdminAuthState.Checking, adminPhoto = photo) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("admins").document(uid).get().await()
            }
            result
                .onSuccess { doc ->
                    _ui.update {
                        it.copy(
                            auth = if (doc.exists()) {
                                AdminAuthState.Ready(uid, email)
                            } else {
                                AdminAuthState.Denied(uid, email)
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            auth = AdminAuthState.Denied(uid, email),
                            message = "Admin check failed: ${error.message}"
                        )
                    }
                }
        }
    }

    fun retryAdminCheck() {
        val current = _ui.value.auth
        val (uid, email) = when (current) {
            is AdminAuthState.Denied -> current.uid to current.email
            is AdminAuthState.Ready -> current.uid to current.email
            else -> auth?.currentUser?.let { it.uid to it.email } ?: return
        }
        checkAdmin(uid, email, _ui.value.adminPhoto)
    }

    fun signIn(activity: Activity) {
        val firebaseAuth = auth ?: return
        if (_ui.value.busy) return
        if (!AdminFirebaseConfig.canSignIn) {
            _ui.update { it.copy(message = context.getString(R.string.admin_not_configured)) }
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(AdminFirebaseConfig.webClientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
                firebaseAuth.signInWithCredential(firebaseCredential).await()
            } catch (cancelled: GetCredentialCancellationException) {
                // User dismissed the account picker; stay signed out.
            } catch (error: Exception) {
                _ui.update { it.copy(message = "Sign-in failed: ${error.message}") }
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun signOut() {
        auth?.signOut()
    }

    fun updateQuery(value: String) {
        _ui.update { it.copy(query = value.uppercase().take(10), searched = false, message = null) }
    }

    fun search() {
        val db = firestore ?: return
        val code = UserCode.normalize(_ui.value.query)
        if (code.length != 9) {
            _ui.update { it.copy(message = context.getString(R.string.admin_need_code)) }
            return
        }
        _ui.update { it.copy(searching = true, searched = true, user = null, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users")
                    .whereEqualTo("userCode", code)
                    .limit(1)
                    .get()
                    .await()
            }
            result
                .onSuccess { snapshot ->
                    val doc = snapshot.documents.firstOrNull()
                    if (doc == null) {
                        _ui.update { it.copy(searching = false, user = null) }
                    } else {
                        val usage = runCatching {
                            db.collection("usage").document(doc.id).get().await()
                        }.getOrNull()
                        _ui.update {
                            it.copy(
                                searching = false,
                                user = doc.toAdminUser(usage)
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            searching = false,
                            message = context.getString(R.string.admin_error) + " (${error.message})"
                        )
                    }
                }
        }
    }

    fun refreshUser() {
        val uid = _ui.value.user?.uid ?: return
        viewModelScope.launch { reloadUser(uid) }
    }

    fun activatePlan(plan: String) {
        val user = _ui.value.user ?: return
        if (!Plans.isPaid(plan)) return
        val db = firestore ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                val now = System.currentTimeMillis()
                val months = Plans.months(plan)
                val base = if (Plans.isPaid(user.plan) && (user.planExpiresAt ?: 0L) > now) {
                    user.planExpiresAt!!
                } else {
                    now
                }
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = base
                    add(Calendar.MONTH, months)
                }
                val expires = calendar.timeInMillis
                db.collection("users").document(user.uid).set(
                    mapOf(
                        "plan" to plan,
                        "planExpiresAt" to Timestamp(Date(expires)),
                        "dailyLimit" to 1,
                        "disabled" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                expires
            }
            result
                .onSuccess { expires ->
                    logAction("✓ ${Plans.label(plan)} → ${user.userCode}")
                    _ui.update { it.copy(message = context.getString(R.string.admin_success)) }
                    reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun setFree() {
        val user = _ui.value.user ?: return
        val db = firestore ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users").document(user.uid).set(
                    mapOf(
                        "plan" to Plans.FREE,
                        "planExpiresAt" to null,
                        "dailyLimit" to 1,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }
            result
                .onSuccess {
                    logAction("✓ Free → ${user.userCode}")
                    _ui.update { it.copy(message = context.getString(R.string.admin_success)) }
                    reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun resetCooldown() {
        val user = _ui.value.user ?: return
        val db = firestore ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("usage").document(user.uid).set(
                    mapOf(
                        "nextAllowedAt" to FieldValue.delete(),
                        "quotaResetAt" to Timestamp(Date()),
                        "updatedAt" to Timestamp(Date())
                    ),
                    SetOptions.merge()
                ).await()
            }
            result
                .onSuccess {
                    logAction("✓ 24h reset → ${user.userCode}")
                    _ui.update { it.copy(message = context.getString(R.string.admin_success)) }
                    reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun setDisabled(disabled: Boolean) {
        val user = _ui.value.user ?: return
        val db = firestore ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users").document(user.uid).set(
                    mapOf(
                        "disabled" to disabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }
            result
                .onSuccess {
                    logAction("✓ ${if (disabled) "Disabled" else "Enabled"} → ${user.userCode}")
                    _ui.update { it.copy(message = context.getString(R.string.admin_success)) }
                    reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun copyText(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        _ui.update { it.copy(message = context.getString(R.string.admin_copied)) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    private suspend fun reloadUser(uid: String) {
        val db = firestore ?: return
        val userDoc = runCatching { db.collection("users").document(uid).get().await() }.getOrNull() ?: return
        val usageDoc = runCatching { db.collection("usage").document(uid).get().await() }.getOrNull()
        _ui.update {
            it.copy(user = userDoc.toAdminUser(usageDoc))
        }
    }

    private fun logAction(text: String) {
        _ui.update { state ->
            state.copy(actions = (listOf(AdminAction(text, System.currentTimeMillis())) + state.actions).take(8))
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toAdminUser(
        usage: com.google.firebase.firestore.DocumentSnapshot?
    ): AdminUser = AdminUser(
        uid = getString("uid") ?: id,
        email = getString("email"),
        displayName = getString("displayName"),
        photoUrl = getString("photoUrl"),
        userCode = getString("userCode") ?: UserCode.fromUid(getString("uid") ?: id),
        plan = getString("plan") ?: Plans.FREE,
        planExpiresAt = getTimestamp("planExpiresAt")?.toDate()?.time,
        disabled = getBoolean("disabled") ?: false,
        createdAt = getTimestamp("createdAt")?.toDate()?.time,
        totalDownloads = usage?.getLong("totalDownloads")?.toInt() ?: 0,
        nextAllowedAt = usage?.getTimestamp("nextAllowedAt")?.toDate()?.time,
        lastDownloadAt = usage?.getTimestamp("lastDownloadAt")?.toDate()?.time
    )

    companion object {
        fun factory(container: AdminContainer) = viewModelFactory {
            initializer { AdminViewModel(container) }
        }
    }
}
