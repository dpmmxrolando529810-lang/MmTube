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
    private const val PACKAGES_DIR = "packages"
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

        val baseDir = File(appContext.noBackupFilesDir, BASE_DIR)
        val packagesDir = File(baseDir, PACKAGES_DIR)
        val ffmpegDir = File(packagesDir, FFMPEG_DIR)

        // The library builds LD_LIBRARY_PATH from
        // <noBackupFilesDir>/youtubedl-android/packages/ffmpeg/usr/lib, so the
        // runtime must be extracted there — not directly under youtubedl-android.
        // Remove the pre-0.2.1 layout if it is still around.
        runCatching {
            val legacy = File(baseDir, FFMPEG_DIR)
            if (legacy.exists()) legacy.deleteRecursively()
        }

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
        alignLibcxx(packagesDir, ffmpegDir)
        pruneSystemShadows(packagesDir, ffmpegDir)
        initialized = true
    }

    /**
     * Termux packages ship unversioned link-time aliases (liblzma.so, libz.so,
     * libssl.so, ...) next to the real versioned libraries. They sit earlier on
     * LD_LIBRARY_PATH than /system/lib64, so Android system libraries that load
     * later (libunwindstack via libmediandk, for example) end up linking against
     * Termux's copy and fail on private symbols such as Xzs_Construct.
     *
     * Nothing in these packages needs the unversioned names at runtime, so they
     * are removed from every package except the FFmpeg one, where libvulkan.so
     * is a real link-time dependency of libplacebo.
     */
    private fun pruneSystemShadows(packagesDir: File, ffmpegDir: File) {
        val shadows = setOf(
            "liblzma.so", "libbz2.so", "libcrypto.so", "libssl.so", "libexpat.so",
            "libz.so", "libffi.so", "libxml2.so", "libsqlite3.so", "libiconv.so",
            "libcharset.so", "libpng.so", "libwebp.so", "libzstd.so",
            "libunwindstack.so", "libunwinding.so"
        )
        val packages = packagesDir.listFiles() ?: return
        for (pkg in packages) {
            if (!pkg.isDirectory || pkg.name == FFMPEG_DIR) continue
            val libDir = File(pkg, "usr/lib")
            if (!libDir.isDirectory) continue
            for (name in shadows) {
                val target = File(libDir, name)
                if (!target.exists()) continue
                val isLink = runCatching {
                    java.nio.file.Files.isSymbolicLink(target.toPath())
                }.getOrDefault(false)
                if (!isLink) continue
                runCatching { target.delete() }
                    .onSuccess { Log.i(TAG, "removed system shadow ${pkg.name}/usr/lib/$name") }
            }
        }
    }

    /**
     * youtubedl-android's Python and aria2c bundles ship an older
     * libc++_shared.so (clang 18) and their usr/lib directories come before
     * FFmpeg's on LD_LIBRARY_PATH. Termux FFmpeg 9.0.2 (libplacebo, clang 19)
     * needs the newer libc++, so replace every packaged copy with ours.
     */
    private fun alignLibcxx(packagesDir: File, ffmpegDir: File) {
        val source = File(File(ffmpegDir, "usr/lib"), "libc++_shared.so")
        if (!source.exists()) return
        val packages = packagesDir.listFiles() ?: return
        for (pkg in packages) {
            if (!pkg.isDirectory || pkg.name == FFMPEG_DIR) continue
            val libDir = File(pkg, "usr/lib")
            if (!libDir.isDirectory) continue
            val target = File(libDir, "libc++_shared.so")
            runCatching {
                if (target.exists() && target.length() == source.length()) return@runCatching
                source.copyTo(target, overwrite = true)
                Log.i(TAG, "libc++_shared.so updated in ${pkg.name}")
            }.onFailure { Log.w(TAG, "libc++_shared.so update failed for ${pkg.name}", it) }
        }
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
