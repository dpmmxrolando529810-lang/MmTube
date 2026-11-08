package com.yausername.ffmpeg

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream

/**
 * Vendored from youtubedl-android (GPL-3.0), simplified to MMTube's needs.
 * Extracts the bundled FFmpeg runtime (from jniLibs/<abi>/libffmpeg.zip.so)
 * into noBackupFilesDir on first use, keyed by the archive's size.
 */
object FFmpeg {
    private const val TAG = "FFmpeg"
    private const val BASE_DIR = "youtubedl-android"
    private const val FFMPEG_DIR = "ffmpeg"
    private const val ZIP_LIB = "libffmpeg.zip.so"
    private const val ZIP_BIN = "libffmpeg.so"
    private const val PREFS = "mmtube_engine"
    private const val KEY_VERSION = "mmtube_ffmpeg_version"

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return

        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
        val zip = File(nativeDir, ZIP_LIB)
        require(zip.exists()) { "FFmpeg archive missing: ${zip.absolutePath}" }

        val ffmpegDir = File(File(appContext.noBackupFilesDir, BASE_DIR), FFMPEG_DIR)
        val version = zip.length().toString()
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_VERSION, null)

        if (!ffmpegDir.exists() || stored != version) {
            if (ffmpegDir.exists()) ffmpegDir.deleteRecursively()
            ffmpegDir.mkdirs()
            unzip(zip, ffmpegDir)
            prefs.edit().putString(KEY_VERSION, version).apply()
            Log.i(TAG, "FFmpeg 9.0.2 extracted ($version bytes)")
        }
        initialized = true
    }

    fun getInstance(): FFmpeg = this

    fun binaryPath(appContext: Context): String =
        File(appContext.applicationInfo.nativeLibraryDir, ZIP_BIN).absolutePath

    private fun unzip(sourceFile: File, targetDirectory: File) {
        ZipFile(sourceFile).use { zipFile ->
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val dest = File(targetDirectory, entry.name)
                val canonical = dest.canonicalFile
                val root = targetDirectory.canonicalFile
                if (!canonical.path.startsWith(root.path + File.separator)) {
                    throw IllegalAccessException("Entry escapes target dir: ${entry.name}")
                }
                when {
                    entry.isDirectory -> dest.mkdirs()
                    entry.isUnixSymlink -> {
                        dest.parentFile?.mkdirs()
                        val target = zipFile.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        runCatching { Os.symlink(target, dest.absolutePath) }
                            .onFailure { Log.w(TAG, "symlink failed: $target -> ${dest.absolutePath}", it) }
                    }
                    else -> {
                        dest.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }
}
