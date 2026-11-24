package com.mmtube.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RenamedMedia(
    val uri: Uri,
    val localPath: String?,
    val displayName: String
)

data class ExportedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val savedToGallery: Boolean,
    val localPath: String?
)

class MediaExporter(private val context: Context) {

    suspend fun export(
        file: File,
        mode: DownloadMode,
        platform: Platform? = null,
        relativeBase: String? = null,
        volumeName: String? = null,
        fast: Boolean = false
    ): ExportedMedia = withContext(Dispatchers.IO) {
        val mime = mimeType(file, mode)
        val folder = folderFor(mode, platform)
        if (fast) {
            return@withContext fallback(file, mime, file.length(), folder)
        }
        val size = file.length()
        val audio = mode == DownloadMode.MP3
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(file, mime, audio, size, folder, relativeBase, volumeName)
                ?: fallback(file, mime, size, folder)
        } else if (hasStoragePermission()) {
            exportToPublic(file, mime, audio, size, folder, relativeBase, volumeName)
                ?: fallback(file, mime, size, folder)
        } else {
            fallback(file, mime, size, folder)
        }
    }

    private fun folderFor(mode: DownloadMode, platform: Platform?): String? {
        if (mode == DownloadMode.MP3) return null
        return when (platform) {
            Platform.YOUTUBE -> "YouTube"
            Platform.FACEBOOK -> "Facebook"
            Platform.TIKTOK -> "TikTok"
            else -> null
        }
    }

    /** Rename the stored file while keeping its extension. */
    suspend fun rename(item: HistoryItem, requested: String): RenamedMedia? = withContext(Dispatchers.IO) {
        val currentName = item.localPath?.let { File(it).name }
            ?: item.uri.substringAfterLast('/').substringBefore('?')
        val ext = currentName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 5 }
        val safe = requested.trim()
            .replace(Regex("[\\/:*?\"<>|\n\r\t]"), "_")
            .take(80)
            .trim()
        if (safe.isBlank()) return@withContext null
        val newName = if (ext != null && !safe.lowercase().endsWith("." + ext.lowercase())) {
            "$safe.$ext"
        } else {
            safe
        }

        val path = item.localPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                val parent = file.parentFile ?: return@withContext null
                val target = uniqueFile(parent, newName)
                if (!file.renameTo(target)) return@withContext null
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target
                    )
                }.getOrNull() ?: Uri.fromFile(target)
                return@withContext RenamedMedia(uri, target.absolutePath, target.name)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        val uri = Uri.parse(item.uri)
        val updated = runCatching {
            context.contentResolver.update(uri, values, null, null)
        }.getOrDefault(0)
        if (updated > 0) RenamedMedia(uri, item.localPath, newName) else null
    }

    suspend fun delete(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        var removed = false
        item.localPath?.let { path ->
            val file = File(path)
            removed = !file.exists() || file.delete()
        }
        runCatching {
            context.contentResolver.delete(Uri.parse(item.uri), null, null)
            removed = true
        }
        removed
    }

    private fun exportToMediaStore(
        file: File,
        mime: String,
        audio: Boolean,
        size: Long,
        relativeSub: String?,
        relativeBase: String?,
        volumeName: String?
    ): ExportedMedia? {
        val resolver = context.contentResolver
        val volume = volumeName?.takeIf { it.isNotBlank() } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.getContentUri(volume)
        }
        val base = if (relativeBase.isNullOrBlank()) {
            if (audio) "Music/MMTube" else "Movies/MMTube"
        } else {
            relativeBase.trim('/') + "/MMTube"
        }
        val relative = buildString {
            append(base)
            if (!relativeSub.isNullOrBlank()) append("/").append(relativeSub)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: error("Could not open MediaStore output")
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            file.delete()
            ExportedMedia(
                uri = uri,
                displayName = file.name,
                mimeType = mime,
                sizeBytes = size,
                savedToGallery = true,
                localPath = null
            )
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun exportToPublic(
        file: File,
        mime: String,
        audio: Boolean,
        size: Long,
        folder: String?,
        relativeBase: String?,
        volumeName: String?
    ): ExportedMedia? {
        return runCatching {
            val root = Environment.getExternalStorageDirectory()
            val baseFolder = if (!relativeBase.isNullOrBlank()) {
                File(File(root, relativeBase.trim('/')), "MMTube")
            } else {
                File(root, if (audio) "Music/MMTube" else "Movies/MMTube")
            }.let { if (folder != null) File(it, folder) else it }
            baseFolder.mkdirs()
            val target = uniqueFile(baseFolder, file.name)
            file.copyTo(target, overwrite = false)
            file.delete()
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mime),
                null
            )
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            ExportedMedia(
                uri = uri,
                displayName = target.name,
                mimeType = mime,
                sizeBytes = size,
                savedToGallery = true,
                localPath = target.absolutePath
            )
        }.getOrNull()
    }

    private fun fallback(file: File, mime: String, size: Long, folder: String? = null): ExportedMedia {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return ExportedMedia(
            uri = uri,
            displayName = file.name,
            mimeType = mime,
            sizeBytes = size,
            savedToGallery = false,
            localPath = file.absolutePath
        )
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (candidate.exists()) {
            candidate = File(folder, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun hasStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun mimeType(file: File, mode: DownloadMode): String {
        if (mode == DownloadMode.MP3) return "audio/mpeg"
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                else -> "video/mp4"
            }
    }
}
