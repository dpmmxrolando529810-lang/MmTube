package com.mmtube.admin

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentSnapshot
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

    /** Sign in with the existing admin email + password. */
    fun signInWithEmail(email: String, password: String) {
        val firebaseAuth = auth ?: return
        if (_ui.value.busy) return
        if (!AdminFirebaseConfig.isConfigured) {
            _ui.update { it.copy(message = context.getString(R.string.admin_not_configured)) }
            return
        }
        if (email.isBlank() || password.isBlank()) {
            _ui.update { it.copy(message = "Email နဲ့ password ဖြည့်ပါ") }
            return
        }
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                firebaseAuth.signInWithEmailAndPassword(email.trim(), password).await()
                // auth listener will run checkAdmin once the user is signed in.
            } catch (error: FirebaseAuthInvalidUserException) {
                _ui.update { it.copy(message = "ဒီ email နဲ့ account မရှိပါဘူး — Main app မှာ အကောင့်ဆောက်ပါ") }
            } catch (error: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(message = "Email သို့မဟုတ် password မှားနေတယ်") }
            } catch (error: Exception) {
                _ui.update { it.copy(message = "Sign in မအောင်မြင်ဘူး (${error.message})") }
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

    fun setTab(tab: AdminTab) {
        _ui.update { it.copy(tab = tab, message = null) }
        when (tab) {
            AdminTab.PAYMENTS -> loadPendingPayments()
            AdminTab.EXPIRING -> loadExpiringUsers()
            AdminTab.LOGS -> loadLogs()
            else -> Unit
        }
    }

    fun setAdminNote(value: String) {
        _ui.update { it.copy(adminNote = value) }
    }

    fun setAnnouncementText(value: String) {
        _ui.update { it.copy(announcementText = value) }
    }

    fun clearApprovalTemplate() {
        _ui.update { it.copy(approvalTemplate = null) }
    }

    fun publishAnnouncement() {
        val db = firestore ?: return
        val text = _ui.value.announcementText.trim()
        if (text.isBlank()) {
            _ui.update { it.copy(message = "ကြေညာချက်စာသား ရေးပါ") }
            return
        }
        _ui.update { it.copy(announcementSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                val id = "ann_" + System.currentTimeMillis()
                db.collection("announcements").document("latest").set(
                    mapOf(
                        "text" to text,
                        "id" to id,
                        "createdAt" to Timestamp(Date())
                    )
                ).await()
                id
            }
            result
                .onSuccess {
                    logAction("✦ ကြေညာချက် ပို့ပြီးပါပြီ")
                    _ui.update { it.copy(announcementSaving = false, announcementText = "", message = "ကြေညာချက် ပို့ပြီးပါပြီ") }
                }
                .onFailure { error ->
                    _ui.update { it.copy(announcementSaving = false, message = "Error (${error.message})") }
                }
        }
    }

    fun loadPendingPayments() {
        val db = firestore ?: return
        _ui.update { it.copy(payingLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("payments").whereEqualTo("status", "pending").get().await()
            }
            result
                .onSuccess { snap ->
                    val list = snap.documents.mapNotNull { it.toPaymentRequest() }.sortedByDescending { it.createdAt }
                    _ui.update { it.copy(payingLoading = false, pendingPayments = list) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(payingLoading = false, message = "Error (${error.message})") }
                }
        }
    }

    fun approvePayment(payment: PaymentRequest) {
        val db = firestore ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        val note = _ui.value.adminNote.trim()
        viewModelScope.launch {
            val result = runCatching {
                val paymentRef = db.collection("payments").document(payment.id)
                val paymentDoc = paymentRef.get().await()
                val prevPlan = paymentDoc.getString("plan") ?: Plans.FREE
                val prevExpires = paymentDoc.getTimestamp("planExpiresAt")?.toDate()?.time
                val now = System.currentTimeMillis()
                val base = if (Plans.isPaid(prevPlan) && (prevExpires ?: 0L) > now) prevExpires!! else now
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = base
                    add(Calendar.MONTH, payment.planMonths)
                }
                val expires = calendar.timeInMillis
                db.collection("users").document(payment.uid).set(
                    mapOf(
                        "plan" to payment.plan,
                        "planExpiresAt" to Timestamp(Date(expires)),
                        "dailyLimit" to 1,
                        "disabled" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                paymentRef.set(
                    mapOf(
                        "status" to "approved",
                        "reviewedAt" to FieldValue.serverTimestamp(),
                        "adminNote" to note.ifBlank { null }
                    ),
                    SetOptions.merge()
                ).await()
                writeActivationLog(
                    db = db,
                    uid = payment.uid,
                    userCode = payment.userCode,
                    plan = payment.plan,
                    months = payment.planMonths,
                    prevPlan = prevPlan,
                    prevExpiresAt = prevExpires,
                    newExpiresAt = expires,
                    source = "payment",
                    paymentId = payment.id,
                    amountKs = payment.amountKs,
                    buyerPhone = payment.buyerPhone,
                    note = note.ifBlank { payment.note }
                )
                expires
            }
            result
                .onSuccess { expires ->
                    logAction("✓ ${Plans.label(payment.plan)} → ${payment.userCode}")
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = context.getString(R.string.admin_success),
                            adminNote = "",
                            approvalTemplate = buildApprovalMessage(payment.userCode, payment.plan, expires)
                        )
                    }
                    loadPendingPayments()
                    loadLogs()
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
        }
    }

    fun rejectPayment(payment: PaymentRequest) {
        val db = firestore ?: return
        if (_ui.value.busy) return
        val note = _ui.value.adminNote.trim()
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("payments").document(payment.id).set(
                    mapOf(
                        "status" to "rejected",
                        "reviewedAt" to FieldValue.serverTimestamp(),
                        "adminNote" to note.ifBlank { "Rejected by admin" }
                    ),
                    SetOptions.merge()
                ).await()
            }
            result
                .onSuccess {
                    logAction("✗ ${payment.userCode} — ငြင်းပယ်")
                    _ui.update { it.copy(busy = false, message = "${payment.userCode} — ငြင်းပယ်ပြီးပါပြီ", adminNote = "") }
                    loadPendingPayments()
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = "Error (${error.message})") }
                }
        }
    }

    fun loadExpiringUsers() {
        val db = firestore ?: return
        _ui.update { it.copy(expiringLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users").whereIn("plan", Plans.paid).get().await()
            }
            result
                .onSuccess { snap ->
                    val list = snap.documents.mapNotNull { it.toAdminUser(null) }
                        .sortedBy { it.planExpiresAt ?: Long.MAX_VALUE }
                    _ui.update { it.copy(expiringLoading = false, expiringUsers = list) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(expiringLoading = false, message = "Error (${error.message})") }
                }
        }
    }

    fun quickExtend(user: AdminUser) {
        if (_ui.value.busy) return
        activatePlanFor(user, "1m", "+1 လ အမြန်တိုး")
    }

    private fun activatePlanFor(user: AdminUser, plan: String, note: String) {
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
                writeActivationLog(
                    db = db,
                    uid = user.uid,
                    userCode = user.userCode,
                    plan = plan,
                    months = months,
                    prevPlan = user.plan,
                    prevExpiresAt = user.planExpiresAt,
                    newExpiresAt = expires,
                    source = "direct",
                    paymentId = null,
                    amountKs = null,
                    buyerPhone = null,
                    note = note
                )
                expires
            }
            result
                .onSuccess { expires ->
                    logAction("✓ ${Plans.label(plan)} → ${user.userCode}")
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = context.getString(R.string.admin_success),
                            approvalTemplate = buildApprovalMessage(user.userCode, plan, expires)
                        )
                    }
                    loadExpiringUsers()
                    loadLogs()
                    if (_ui.value.user?.uid == user.uid) reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
        }
    }

    fun loadLogs() {
        val db = firestore ?: return
        _ui.update { it.copy(logsLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("activationLog")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(50)
                    .get()
                    .await()
            }
            result
                .onSuccess { snap ->
                    val list = snap.documents.mapNotNull { it.toActivationLog() }
                    _ui.update { it.copy(logsLoading = false, logs = list) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(logsLoading = false, message = "Error (${error.message})") }
                }
        }
    }

    fun undoActivation(log: ActivationLog) {
        val db = firestore ?: return
        if (log.undoneAt != null || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        val adminUid = currentAdminUid()
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users").document(log.uid).set(
                    mapOf(
                        "plan" to (log.prevPlan ?: Plans.FREE),
                        "planExpiresAt" to log.prevExpiresAt?.let { Timestamp(Date(it)) },
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                db.collection("activationLog").document(log.id).set(
                    mapOf(
                        "undoneAt" to FieldValue.serverTimestamp(),
                        "undoneBy" to adminUid
                    ),
                    SetOptions.merge()
                ).await()
            }
            result
                .onSuccess {
                    logAction("↩ ${log.userCode} — ပြန်ပြောင်း")
                    _ui.update { it.copy(busy = false, message = "ပြန်ပြောင်းပြီးပါပြီ") }
                    loadLogs()
                    loadExpiringUsers()
                    if (_ui.value.user?.uid == log.uid) reloadUser(log.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = "Error (${error.message})") }
                }
        }
    }

    fun currentAdminUid(): String = (_ui.value.auth as? AdminAuthState.Ready)?.uid ?: "unknown"

    private fun buildApprovalMessage(userCode: String, plan: String, expiresAt: Long): String {
        val months = Plans.months(plan)
        val date = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(expiresAt))
        }.getOrDefault("?")
        return "MMTube: $userCode — ${Plans.label(plan)} ဖွင့်ပေးပြီးပါပြီ ($date အထိ) ကျေးဇူးတင်ပါတယ်။"
    }

    private fun writeActivationLog(
        db: FirebaseFirestore,
        uid: String,
        userCode: String,
        plan: String,
        months: Int,
        prevPlan: String?,
        prevExpiresAt: Long?,
        newExpiresAt: Long?,
        source: String,
        paymentId: String?,
        amountKs: Long?,
        buyerPhone: String?,
        note: String?
    ) {
        viewModelScope.launch {
            runCatching {
                db.collection("activationLog").add(
                    mapOf(
                        "uid" to uid,
                        "userCode" to userCode,
                        "plan" to plan,
                        "months" to months,
                        "prevPlan" to prevPlan,
                        "prevExpiresAt" to (prevExpiresAt?.let { Timestamp(Date(it)) }),
                        "newExpiresAt" to (newExpiresAt?.let { Timestamp(Date(it)) }),
                        "source" to source,
                        "paymentId" to paymentId,
                        "amountKs" to amountKs,
                        "buyerPhone" to buyerPhone,
                        "note" to note,
                        "byAdminUid" to currentAdminUid(),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }.onFailure { error ->
                Log.w("AdminViewModel", "activationLog write failed", error)
            }
        }
    }

    private fun DocumentSnapshot.toPaymentRequest(): PaymentRequest? {
        val uid = getString("uid") ?: return null
        return PaymentRequest(
            id = id,
            uid = uid,
            userCode = getString("userCode") ?: "?",
            email = getString("email"),
            plan = getString("plan") ?: "?",
            planMonths = getLong("planMonths")?.toInt() ?: 1,
            amountKs = getLong("amountKs") ?: 0L,
            buyerPhone = getString("buyerPhone"),
            note = getString("note"),
            imageBase64 = getString("imageBase64") ?: "",
            status = getString("status") ?: "pending",
            createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L
        )
    }

    private fun DocumentSnapshot.toActivationLog(): ActivationLog? {
        val uid = getString("uid") ?: return null
        return ActivationLog(
            id = id,
            uid = uid,
            userCode = getString("userCode") ?: "?",
            plan = getString("plan") ?: Plans.FREE,
            months = getLong("months")?.toInt() ?: 1,
            newExpiresAt = getTimestamp("newExpiresAt")?.toDate()?.time,
            prevPlan = getString("prevPlan"),
            prevExpiresAt = getTimestamp("prevExpiresAt")?.toDate()?.time,
            source = getString("source") ?: "direct",
            amountKs = getLong("amountKs"),
            buyerPhone = getString("buyerPhone"),
            note = getString("note"),
            createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            undoneAt = getTimestamp("undoneAt")?.toDate()?.time
        )
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
