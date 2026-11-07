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

data class ExportedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val savedToGallery: Boolean,
    val localPath: String?
)

class MediaExporter(private val context: Context) {

    suspend fun export(file: File, mode: DownloadMode): ExportedMedia = withContext(Dispatchers.IO) {
        val mime = mimeType(file, mode)
        val size = file.length()
        val audio = mode == DownloadMode.MP3
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(file, mime, audio, size) ?: fallback(file, mime, size)
        } else if (hasStoragePermission()) {
            exportToPublic(file, mime, audio, size) ?: fallback(file, mime, size)
        } else {
            fallback(file, mime, size)
        }
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
        size: Long
    ): ExportedMedia? {
        val resolver = context.contentResolver
        val collection = if (audio) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (audio) "Music/MMTube" else "Movies/MMTube"
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
        size: Long
    ): ExportedMedia? {
        return runCatching {
            val base = Environment.getExternalStoragePublicDirectory(
                if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            )
            val folder = File(base, "MMTube").apply { mkdirs() }
            val target = uniqueFile(folder, file.name)
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

    private fun fallback(file: File, mime: String, size: Long): ExportedMedia {
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
