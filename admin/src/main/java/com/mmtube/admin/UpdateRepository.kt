package com.mmtube.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.mmtube.admin.util.canInstallPackages
import com.mmtube.admin.util.openUnknownSourcesSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AdminUpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val htmlUrl: String
)

@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val html_url: String = "",
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L
)

class UpdateRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val version: String = BuildConfig.VERSION_NAME

    suspend fun checkForUpdate(): Result<AdminUpdateInfo?> = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO.trim()
        if (repo.isBlank()) return@withContext Result.success(null)
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MMTube-Admin/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty release body")
                val release = json.decodeFromString<GithubRelease>(body)
                val latest = release.tag_name.removePrefix("v").trim()
                if (latest.isBlank() || !isNewer(latest, BuildConfig.VERSION_NAME)) {
                    return@use null
                }
                val asset = release.assets
                    .filter { it.name.endsWith(".apk", true) }
                    .filter { it.name.contains("admin", true) }
                    .maxByOrNull { it.size } ?: return@use null
                AdminUpdateInfo(
                    version = latest,
                    tag = release.tag_name,
                    notes = release.body.orEmpty(),
                    assetUrl = asset.browser_download_url,
                    assetName = asset.name,
                    sizeBytes = asset.size,
                    htmlUrl = release.html_url
                )
            }
        }
    }

    /** Returns an already-downloaded update APK for [info] if one exists on disk. */
    fun findDownloaded(info: AdminUpdateInfo): File? {
        val directory = updatesDirectory() ?: return null
        return runCatching {
            directory.listFiles()
                ?.filter { it.isFile && it.extension == "apk" }
                ?.firstOrNull { it.name.equals(info.assetName, ignoreCase = true) }
        }.getOrNull()
    }

    suspend fun download(
        info: AdminUpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = updatesDirectory() ?: throw IOException("Storage not available")
            directory.listFiles()
                ?.filter { it.extension == "apk" || it.extension == "part" }
                ?.forEach { runCatching { it.delete() } }
            val target = File(directory, info.assetName)
            val temp = File(directory, "${info.assetName}.part")
            val request = Request.Builder()
                .url(info.assetUrl)
                .header("User-Agent", "MMTube-Admin/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty update body")
                val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes
                var read = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0L) {
                                onProgress(((read * 100L) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                if (!verifyApkSignature(target)) {
                    target.delete()
                    throw IOException("Update APK signature does not match — refusing to install a different app")
                }
                target
            }
        }
    }

    fun install(file: File): Result<Unit> {
        if (!context.canInstallPackages()) {
            return Result.failure(IOException("Install permission is required"))
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun openInstallSettings(): Boolean {
        return context.openUnknownSourcesSettings()
    }

    fun openReleasePage(info: AdminUpdateInfo) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun updatesDirectory(): File? {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "updates") }
            ?.apply { mkdirs() }
    }

    private fun verifyApkSignature(apk: File): Boolean {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val myInfo = context.packageManager.getPackageInfo(context.packageName, flags)
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            val mySha = signerSha256(myInfo)
            val archiveSha = signerSha256(archiveInfo)
            mySha != null && archiveSha != null && mySha == archiveSha
        }.getOrDefault(false)
    }

    private fun signerSha256(info: PackageInfo?): String? = runCatching {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info?.signatures?.firstOrNull()?.toByteArray()
        }
        bytes ?: return@runCatching null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = remote.substringBefore('-').split('.').map {
            it.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
        val currentParts = current.substringBefore('-').split('.').map {
            it.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
        val size = maxOf(remoteParts.size, currentParts.size)
        for (index in 0 until size) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }
        return false
    }
}
