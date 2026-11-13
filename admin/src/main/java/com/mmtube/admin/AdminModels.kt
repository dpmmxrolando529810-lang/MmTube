package com.mmtube.admin

import java.security.MessageDigest

object Plans {
    const val FREE = "free"
    const val M1 = "1m"
    const val M3 = "3m"
    const val M6 = "6m"
    const val M12 = "12m"

    val paid = listOf(M1, M3, M6, M12)

    fun isPaid(plan: String): Boolean = plan in paid

    fun months(plan: String): Int = when (plan) {
        M1 -> 1
        M3 -> 3
        M6 -> 6
        M12 -> 12
        else -> 0
    }

    fun label(plan: String): String = when (plan) {
        M1 -> "1 Month"
        M3 -> "3 Months"
        M6 -> "6 Months"
        M12 -> "12 Months"
        FREE -> "Free"
        else -> plan.uppercase()
    }
}

object UserCode {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun fromUid(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder("MM-")
        for (index in 0 until 6) {
            val value = digest[index].toInt() and 0xFF
            builder.append(ALPHABET[value % ALPHABET.length])
        }
        return builder.toString()
    }

    fun normalize(input: String): String {
        val trimmed = input.trim().uppercase()
        return if (trimmed.startsWith("MM-")) trimmed else "MM-$trimmed"
    }
}

data class AdminUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val userCode: String,
    val plan: String,
    val planExpiresAt: Long?,
    val disabled: Boolean,
    val createdAt: Long?,
    val totalDownloads: Int,
    val nextAllowedAt: Long?,
    val lastDownloadAt: Long?
) {
    val isPaid: Boolean
        get() = Plans.isPaid(plan) && (planExpiresAt ?: 0L) > System.currentTimeMillis()
}

sealed interface AdminAuthState {
    data object Loading : AdminAuthState
    data object SignedOut : AdminAuthState
    data object Checking : AdminAuthState
    data object NotConfigured : AdminAuthState
    data class Denied(val uid: String, val email: String?) : AdminAuthState
    data class Ready(val uid: String, val email: String?) : AdminAuthState
}


enum class AdminTab { SEARCH, PAYMENTS, EXPIRING, LOGS, BROADCAST }

data class PaymentRequest(
    val id: String,
    val uid: String,
    val userCode: String,
    val email: String?,
    val plan: String,
    val planMonths: Int,
    val amountKs: Long,
    val buyerPhone: String?,
    val note: String?,
    val imageBase64: String,
    val status: String,
    val createdAt: Long
)

data class ActivationLog(
    val id: String,
    val uid: String,
    val userCode: String,
    val plan: String,
    val months: Int,
    val newExpiresAt: Long?,
    val prevPlan: String?,
    val prevExpiresAt: Long?,
    val source: String,
    val paymentId: String?,
    val amountKs: Long?,
    val buyerPhone: String?,
    val note: String?,
    val createdAt: Long,
    val undoneAt: Long?
)

data class AdminAction(
    val text: String,
    val at: Long
)

data class AdminUiState(
    val auth: AdminAuthState = AdminAuthState.Loading,
    val adminPhoto: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val user: AdminUser? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val actions: List<AdminAction> = emptyList(),
    val tab: AdminTab = AdminTab.SEARCH,
    val pendingPayments: List<PaymentRequest> = emptyList(),
    val payingLoading: Boolean = false,
    val paymentSearchQuery: String = "",
    val selectedPaymentIds: Set<String> = emptySet(),
    val paymentsTodayCount: Int = 0,
    val revenueTodayKs: Long = 0L,
    val monthlyRevenue: List<Pair<String, Long>> = emptyList(),
    val announcementDryRun: Boolean = false,
    val pendingExtendConfirmation: AdminUser? = null,
    val expiringUsers: List<AdminUser> = emptyList(),
    val expiringLoading: Boolean = false,
    val logs: List<ActivationLog> = emptyList(),
    val logsLoading: Boolean = false,
    val adminNote: String = "",
    val approvalTemplate: String? = null,
    val announcementText: String = "",
    val announcementSaving: Boolean = false
)
