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
    private val updater = UpdateRepository(container.appContext)

    private var autoRejectRan = false

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
        // Auto-check for an app update on entry — and download it silently.
        maybeCheckForUpdate()
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

    private fun addMonthsClamped(base: Long, months: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = base
            val day = get(Calendar.DAY_OF_MONTH)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, months)
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return calendar.timeInMillis
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
                val expires = addMonthsClamped(base, months)
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
            AdminTab.PAYMENTS -> { loadPendingPayments(); loadTodayAndMonthlyRevenue(); autoPurgeOldRejected() }
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
        val dryRun = _ui.value.announcementDryRun
        val adminEmail = auth?.currentUser?.email ?: "unknown@admin"
        _ui.update { it.copy(announcementSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                val id = "ann_" + System.currentTimeMillis()
                val payload = mutableMapOf<String, Any>(
                    "text" to text,
                    "id" to id,
                    "createdAt" to Timestamp(Date())
                )
                if (dryRun) {
                    payload["dryRun"] = true
                    payload["targetEmail"] = adminEmail
                }
                db.collection("announcements").document("latest").set(payload).await()
                id
            }
            result
                .onSuccess {
                    logAction(if (dryRun) "✦ Dry-run ကြေညာချက် ပို့ပြီး" else "✦ ကြေညာချက် ပို့ပြီးပါပြီ")
                    _ui.update { it.copy(announcementSaving = false, announcementText = "", message = if (dryRun) "Admin သာမြင်မည် (dry-run)" else "ကြေညာချက် ပို့ပြီးပါပြီ") }
                }
                .onFailure { error ->
                    _ui.update { it.copy(announcementSaving = false, message = "Error (${error.message})") }
                }
        }
    }

    fun refundFromLog(log: ActivationLog) {
        val db = firestore ?: return
        val paymentId = log.paymentId ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                val paymentSnap = db.collection("payments").document(paymentId).get().await()
                val payment = if (paymentSnap.exists()) paymentSnap.toPaymentRequest() else null
                if (payment != null) {
                    refundOneNow(payment, "ငွေပြန်အမ်း (မှတ်တမ်းမှ)")
                } else {
                    // Fallback: at least flip the user to free
                    db.collection("users").document(log.uid).set(
                        mapOf(
                            "plan" to Plans.FREE,
                            "planExpiresAt" to null,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }
            result
                .onSuccess {
                    logAction("↩ ${log.userCode} — refund")
                    _ui.update { it.copy(busy = false, message = "${log.userCode} — ငွေပြန်အမ်းပြီး") }
                    loadLogs()
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = "Error (${error.message})") }
                }
        }
    }

    private suspend fun refundOneNow(payment: PaymentRequest, note: String) {
        val db = firestore ?: error("Firestore not configured")
        db.collection("users").document(payment.uid).set(
            mapOf(
                "plan" to Plans.FREE,
                "planExpiresAt" to null,
                "disabled" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        db.collection("payments").document(payment.id).set(
            mapOf(
                "status" to "refunded",
                "reviewedAt" to FieldValue.serverTimestamp(),
                "adminNote" to note,
                "imageBase64" to ""
            ),
            SetOptions.merge()
        ).await()
        writeActivationLog(
            db = db,
            uid = payment.uid,
            userCode = payment.userCode,
            plan = Plans.FREE,
            months = 0,
            prevPlan = payment.plan,
            prevExpiresAt = null,
            newExpiresAt = null,
            source = "refund",
            paymentId = payment.id,
            amountKs = payment.amountKs,
            buyerPhone = payment.buyerPhone,
            note = note
        )
    }

    fun autoPurgeOldRejected() {
        val db = firestore ?: return
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            runCatching {
                db.collection("payments")
                    .whereEqualTo("status", "rejected")
                    .get()
                    .await()
                    .documents
                    .filter { (it.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE) < cutoff }
                    .forEach { doc ->
                        runCatching { doc.reference.delete().await() }
                    }
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
                    updateLauncherBadge(list.size)
                    _ui.update { it.copy(payingLoading = false, pendingPayments = list) }
                    if (!autoRejectRan) {
                        autoRejectRan = true
                        autoRejectStale()
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(payingLoading = false, message = "Error (${error.message})") }
                }
        }
    }

    private suspend fun approveOneNow(payment: PaymentRequest, note: String): Long {
        val db = firestore ?: error("Firestore not configured")
        val paymentRef = db.collection("payments").document(payment.id)
        val userDoc = db.collection("users").document(payment.uid).get().await()
        val prevPlan = userDoc.getString("plan") ?: Plans.FREE
        val prevExpires = userDoc.getTimestamp("planExpiresAt")?.toDate()?.time
        val now = System.currentTimeMillis()
        val base = if (Plans.isPaid(prevPlan) && (prevExpires ?: 0L) > now) prevExpires!! else now
        val expires = addMonthsClamped(base, payment.planMonths)
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
                "adminNote" to note.ifBlank { null },
                "imageBase64" to ""
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
        return expires
    }

    fun approvePayment(payment: PaymentRequest) {
        val db = firestore ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        val note = _ui.value.adminNote.trim()
        viewModelScope.launch {
            val result = runCatching {
                approveOneNow(payment, note)
            }
            result
                .onSuccess { expires ->
                    logAction("✓ ${Plans.label(payment.plan)} → ${payment.userCode}")
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = context.getString(R.string.admin_success),
                            adminNote = "",
                            approvalTemplate = buildApprovalMessage(payment.userCode, payment.plan, expires),
                            selectedPaymentIds = emptySet()
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

    fun approveSelectedPayments() {
        val db = firestore ?: return
        if (_ui.value.busy) return
        val selected = _ui.value.selectedPaymentIds
        if (selected.isEmpty()) return
        val payments = _ui.value.pendingPayments.filter { it.id in selected }
        if (payments.isEmpty()) return
        _ui.update { it.copy(busy = true) }
        val note = _ui.value.adminNote.trim()
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            for (payment in payments) {
                runCatching { approveOneNow(payment, note) }
                    .onSuccess { ok++ }
                    .onFailure { fail++ }
            }
            logAction("✓ ${ok} ခု အတည်ပြု (${fail} fail)")
            _ui.update {
                it.copy(
                    busy = false,
                    message = if (fail == 0) "ငွေပေးချေမှု ${ok} ခု အတည်ပြုပြီး" else "${ok} အောင်မြင်, ${fail} ပျက်",
                    adminNote = "",
                    selectedPaymentIds = emptySet()
                )
            }
            loadPendingPayments()
            loadLogs()
        }
    }

    fun togglePaymentSelection(paymentId: String) {
        _ui.update {
            val next = it.selectedPaymentIds.toMutableSet()
            if (next.contains(paymentId)) next.remove(paymentId) else next.add(paymentId)
            it.copy(selectedPaymentIds = next)
        }
    }

    fun updatePaymentSearch(value: String) {
        _ui.update { it.copy(paymentSearchQuery = value) }
    }

    private fun autoRejectStale() {
        val db = firestore ?: return
        val cutoff = System.currentTimeMillis() - 5L * 24 * 3600 * 1000
        val stale = _ui.value.pendingPayments.filter { it.createdAt in 1 until cutoff }
        if (stale.isEmpty()) return
        viewModelScope.launch {
            stale.forEach { payment ->
                runCatching {
                    db.collection("payments").document(payment.id).set(
                        mapOf(
                            "status" to "rejected",
                            "reviewedAt" to FieldValue.serverTimestamp(),
                            "adminNote" to "၅ ရက်ကျော်သွား — auto ငြင်းပယ်",
                            "imageBase64" to ""
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }
            loadPendingPayments()
        }
    }

    fun loadTodayAndMonthlyRevenue() {
        val db = firestore ?: return
        viewModelScope.launch {
            val result = runCatching {
                db.collection("payments")
                    .whereEqualTo("status", "approved")
                    .get()
                    .await()
            }
            result.onSuccess { snap ->
                val calendar = Calendar.getInstance()
                val today = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
                var todayCount = 0
                var todayKs = 0L
                val monthly = mutableMapOf<String, Long>()
                for (doc in snap.documents) {
                    val time = doc.getTimestamp("reviewedAt")?.toDate()?.time
                        ?: doc.getTimestamp("createdAt")?.toDate()?.time ?: continue
                    val amount = doc.getLong("amountKs") ?: 0L
                    val c = Calendar.getInstance().apply { this.timeInMillis = time }
                    if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                    ) {
                        todayCount += 1
                        todayKs += amount
                    }
                    val key = String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
                    monthly[key] = (monthly[key] ?: 0L) + amount
                }
                val sorted = monthly.entries.sortedBy { it.key }.takeLast(6).map { it.key to it.value }
                _ui.update { it.copy(paymentsTodayCount = todayCount, revenueTodayKs = todayKs, monthlyRevenue = sorted) }
            }
        }
    }

    fun refundPayment(payment: PaymentRequest) {
        if (_ui.value.busy) return
        val note = _ui.value.adminNote.takeIf { it.isNotBlank() } ?: "Refunded by admin"
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { refundOneNow(payment, note) }
            result
                .onSuccess {
                    logAction("↩ ${payment.userCode} — refund")
                    _ui.update { it.copy(busy = false, message = "${payment.userCode} — ငွေပြန်အမ်းပြီး", adminNote = "") }
                    loadPendingPayments()
                    loadLogs()
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = context.getString(R.string.admin_error) + " (${error.message})") }
                }
        }
    }

    fun confirmExtendWithWarning() {
        val user = _ui.value.pendingExtendConfirmation ?: return
        _ui.update { it.copy(pendingExtendConfirmation = null) }
        activatePlanFor(user, "1m", "+1 လ အမြန်တိုး")
    }

    fun cancelExtendWarning() {
        _ui.update { it.copy(pendingExtendConfirmation = null) }
    }

    // ---------- Auto update ----------

    private val updateChannel = "mmtube_admin_update"
    private val updateNotificationId = 7801

    /** Silent entry-time check — downloads and then launches the system installer automatically. */
    fun maybeCheckForUpdate() {
        viewModelScope.launch {
            checkForUpdates(silent = true)
        }
    }

    fun checkForUpdates(silent: Boolean = true) {
        if (_ui.value.update.checking) return
        _ui.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        viewModelScope.launch {
            updater.checkForUpdate()
                .onSuccess { info ->
                    if (info == null) {
                        _ui.update { it.copy(update = it.update.copy(checking = false)) }
                        if (!silent) {
                            _ui.update { state -> state.copy(message = "You are on the latest version") }
                        }
                    } else {
                        _ui.update { it.copy(update = it.update.copy(checking = false, info = info)) }
                        val downloaded = updater.findDownloaded(info)
                        if (downloaded != null) {
                            _ui.update { state ->
                                state.copy(update = state.update.copy(file = downloaded, progress = 100))
                            }
                            showUpdateReadyNotification(info.version)
                            if (silent && !_ui.value.update.dismissed) installUpdate()
                        } else {
                            downloadUpdate(autoInstall = silent)
                        }
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(update = it.update.copy(checking = false, error = error.message)) }
                    if (!silent) {
                        _ui.update { state -> state.copy(message = error.message ?: "Update check failed") }
                    }
                }
        }
    }

    fun downloadUpdate(autoInstall: Boolean = false) {
        val info = _ui.value.update.info ?: return
        if (_ui.value.update.downloading) return
        _ui.update { it.copy(update = it.update.copy(downloading = true, progress = 0, error = null)) }
        viewModelScope.launch {
            updater.download(info) { progress ->
                _ui.update { current ->
                    if (current.update.downloading) {
                        current.copy(update = current.update.copy(progress = progress))
                    } else {
                        current
                    }
                }
            }.onSuccess { file ->
                _ui.update { it.copy(update = it.update.copy(downloading = false, file = file, progress = 100)) }
                showUpdateReadyNotification(info.version)
                // The user asked for hands-free updates: launch the system
                // installer right away. Android still needs one Install tap.
                if (autoInstall && !_ui.value.update.dismissed) {
                    installUpdate()
                }
            }.onFailure { error ->
                _ui.update { it.copy(update = it.update.copy(downloading = false, error = error.message)) }
                _ui.update { state -> state.copy(message = error.message ?: "Update download failed") }
            }
        }
    }

    fun installUpdate() {
        val file = _ui.value.update.file ?: return
        updater.install(file)
            .onFailure { error ->
                val text = error.message ?: context.getString(R.string.admin_error)
                _ui.update { state -> state.copy(message = text) }
                // No unknown-sources permission yet — send the admin straight to the toggle.
                if (text.contains("permission", true)) {
                    updater.openInstallSettings()
                }
            }
    }

    fun dismissUpdate() {
        _ui.update { it.copy(update = it.update.copy(dismissed = true)) }
    }

    private fun showUpdateReadyNotification(version: String) {
        runCatching {
            val mgr = context.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        updateChannel,
                        "MMTube Admin updates",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val tap = android.app.PendingIntent.getActivity(
                context, updateNotificationId,
                android.content.Intent(context, AdminActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, updateChannel)
                .setSmallIcon(R.drawable.ic_stat_mmtube)
                .setContentTitle("Update ready — MMTube Admin v$version")
                .setContentText("Tap Install to update MMTube Admin.")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(tap)
                .build()
            mgr.notify(updateNotificationId, notif)
        }
    }

    private val badgeChannel = "mmtube_admin_badge"
    private val badgeNotificationId = 6901

    private fun updateLauncherBadge(count: Int) {
        runCatching {
            val mgr = context.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        badgeChannel,
                        "Payment review badge",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(true) }
                )
            }
            if (count <= 0) {
                mgr.cancel(badgeNotificationId)
                return@runCatching
            }
            val tap = android.app.PendingIntent.getActivity(
                context, 6901,
                android.content.Intent(context, AdminActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, badgeChannel)
                .setSmallIcon(R.drawable.ic_stat_mmtube)
                .setContentTitle(context.getString(R.string.admin_app_name))
                .setContentText("ငွေပေးချေမှု ${count} ခု စစ်ဆေးရန် ကျန်နေပါသည်")
                .setNumber(count)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setBadgeIconType(androidx.core.app.NotificationCompat.BADGE_ICON_SMALL)
                .build()
            mgr.notify(badgeNotificationId, notif)
        }
    }

    fun setAnnouncementDryRun(value: Boolean) {
        _ui.update { it.copy(announcementDryRun = value) }
    }

    fun quickExtendChecked(user: AdminUser) {
        val remaining = (user.planExpiresAt ?: 0L) - System.currentTimeMillis()
        if (Plans.isPaid(user.plan) && remaining > 14L * 24 * 3600 * 1000) {
            _ui.update { it.copy(pendingExtendConfirmation = user) }
            return
        }
        activatePlanFor(user, "1m", "+1 လ အမြန်တိုး")
    }

    fun forceSignOutUser(user: AdminUser) {
        val db = firestore ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                db.collection("users").document(user.uid).set(
                    mapOf(
                        "forceSignOutAt" to Timestamp(Date()),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                writeActivationLog(
                    db = db,
                    uid = user.uid,
                    userCode = user.userCode,
                    plan = user.plan,
                    months = 0,
                    prevPlan = user.plan,
                    prevExpiresAt = user.planExpiresAt,
                    newExpiresAt = user.planExpiresAt,
                    source = "force_sign_out",
                    paymentId = null,
                    amountKs = null,
                    buyerPhone = null,
                    note = "Admin မှ အတင်း sign-out"
                )
            }
            result
                .onSuccess {
                    logAction("⎋ ${user.userCode} — sign out")
                    _ui.update { it.copy(busy = false, message = "${user.userCode} ကို sign-out လုပ်ပြီး") }
                    reloadUser(user.uid)
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, message = "Error (${error.message})") }
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
                        "adminNote" to note.ifBlank { "Rejected by admin" },
                        "imageBase64" to ""
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
        quickExtendChecked(user)
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
                val expires = addMonthsClamped(base, months)
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
        paymentId = getString("paymentId"),
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
