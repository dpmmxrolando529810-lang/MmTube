package com.mmtube.app.data

import kotlinx.serialization.Serializable

object Plans {
    const val FREE = "free"
    const val M1 = "1m"
    const val M3 = "3m"
    const val M6 = "6m"
    const val M12 = "12m"

    val paid = listOf(M1, M3, M6, M12)

    fun months(plan: String): Int = when (plan) {
        M1 -> 1
        M3 -> 3
        M6 -> 6
        M12 -> 12
        else -> 0
    }

    fun isPaid(plan: String): Boolean = plan in paid
}

enum class Platform {
    YOUTUBE,
    FACEBOOK,
    TIKTOK,
    OTHER
}

enum class DownloadMode(val key: String) {
    MAX("max"),
    P1080("1080"),
    P720("720"),
    MP3("mp3");

    companion object {
        fun fromKey(key: String?): DownloadMode = entries.firstOrNull { it.key == key } ?: MAX
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name.equals(key, true) } ?: SYSTEM
    }
}

enum class ErrorKind {
    INVALID_LINK,
    UNSUPPORTED,
    PRIVATE,
    AGE,
    UNAVAILABLE,
    NETWORK,
    ENGINE,
    DOWNLOAD,
    QUOTA,
    NO_SPACE,
    FIREBASE,
    SIGN_IN_CANCELLED,
    SIGN_IN_UNAVAILABLE,
    NOT_ADMIN,
    INSTALL_PERMISSION,
    GENERIC
}

class MmtubeException(
    val kind: ErrorKind,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val userCode: String,
    val plan: String,
    val planExpiresAt: Long?,
    val dailyLimit: Int,
    val disabled: Boolean,
    val createdAt: Long?
) {
    val isPaid: Boolean
        get() = !disabled &&
            Plans.isPaid(plan) &&
            (planExpiresAt ?: 0L) > System.currentTimeMillis()

    val isExpired: Boolean
        get() = Plans.isPaid(plan) && (planExpiresAt ?: 0L) <= System.currentTimeMillis()
}

data class UsageInfo(
    val lastDownloadAt: Long? = null,
    val nextAllowedAt: Long? = null,
    val totalDownloads: Int = 0,
    val quotaResetAt: Long? = null
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(
        val uid: String,
        val email: String?,
        val displayName: String?,
        val photoUrl: String?
    ) : AuthState

    data class Error(val message: String) : AuthState
}

data class AccountUi(
    val auth: AuthState = AuthState.Loading,
    val profile: UserProfile? = null,
    val usage: UsageInfo? = null,
    val loading: Boolean = false,
    val error: String? = null
) {
    val isSignedIn: Boolean get() = auth is AuthState.SignedIn
    val isGuest: Boolean get() = auth is AuthState.SignedOut
}

data class QuotaState(
    val allowed: Boolean,
    val paid: Boolean,
    val guest: Boolean,
    val nextAllowedAt: Long? = null,
    val totalDownloads: Int = 0,
    val disabled: Boolean = false,
    val reason: String? = null
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        ((nextAllowedAt ?: 0L) - now).coerceAtLeast(0L)
}

data class ProbeResult(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnail: String?,
    val platform: Platform,
    val maxHeight: Int,
    val maxFps: Int,
    val estimatedBytes: Long,
    val audioOnly: Boolean,
    val extractor: String?,
    val webpageUrl: String?
)

sealed interface ProbeUi {
    data object Idle : ProbeUi
    data class Loading(val url: String, val startedAt: Long = System.currentTimeMillis()) : ProbeUi
    data class Success(val result: ProbeResult) : ProbeUi
    data class Error(val kind: ErrorKind, val detail: String? = null) : ProbeUi
}

sealed interface EngineState {
    data object Idle : EngineState
    data object Initializing : EngineState
    data class Ready(val version: String?) : EngineState
    data class Failed(val message: String) : EngineState
}

data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val htmlUrl: String
)

data class UpdateUi(
    val info: UpdateInfo? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val file: java.io.File? = null,
    val error: String? = null,
    val dismissed: Boolean = false
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Preparing(val title: String, val mode: DownloadMode) : DownloadState
    data class Running(
        val title: String,
        val mode: DownloadMode,
        val progress: Float,
        val etaSeconds: Long,
        val speedBytes: Long,
        val totalBytes: Long,
        val line: String
    ) : DownloadState

    data class Merging(val title: String, val mode: DownloadMode) : DownloadState
    data class Saving(val title: String, val mode: DownloadMode) : DownloadState
    data class Completed(val item: HistoryItem) : DownloadState
    data class Failed(val title: String, val kind: ErrorKind, val detail: String? = null) : DownloadState
    data class Paused(val title: String, val mode: DownloadMode, val progress: Float) : DownloadState
    data object Cancelled : DownloadState
}

@Serializable
data class HistoryItem(
    val id: String,
    val title: String,
    val platform: String,
    val quality: String,
    val sizeBytes: Long,
    val mimeType: String,
    val uri: String,
    val localPath: String? = null,
    val thumbnail: String? = null,
    val durationSeconds: Int = 0,
    val createdAt: Long,
    val mode: String,
    val savedToGallery: Boolean = false
)

data class SettingsSnapshot(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val turbo: Boolean = true,
    val autoEngineUpdate: Boolean = true,
    val autoAppUpdate: Boolean = true,
    val acceptedTerms: Boolean = false,
    val guestNextAllowedAt: Long = 0L,
    val guestTotalDownloads: Int = 0,
    val lastEngineUpdate: Long = 0L,
    val lastUpdateCheck: Long = 0L,
    val language: String = "",
    val onboardingSeen: Boolean = false,
    val colorblindMode: Boolean = false,
    val dynamicColor: Boolean = false
)
