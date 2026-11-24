package com.mmtube.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** Muxes two already-downloaded streams with the bundled ffmpeg (stream copy). */
object StreamMerger {

    fun merge(context: Context, video: File, audio: File, output: File): Boolean = runCatching {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val ffmpeg = File(packages, "ffmpeg/usr/bin/ffmpeg")
        val binary = if (ffmpeg.isFile) ffmpeg else
            File(File(context.applicationInfo.nativeLibraryDir), "libffmpeg.so")
        if (!binary.isFile) return false
        val libPath = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        FastAlloc.reserve(output, video.length() + audio.length())
        val command = listOf(
            binary.absolutePath,
            "-y", "-loglevel", "error",
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c", "copy",
            if (output.extension.equals("mkv", true)) "-f" else "-movflags",
            if (output.extension.equals("mkv", true)) "matroska" else "+faststart",
            output.absolutePath
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().put("LD_LIBRARY_PATH", libPath) }
            .start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val ok = process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0 &&
            output.isFile && output.length() > 0L
        if (!ok) Log.w("StreamMerger", log.take(500))
        ok
    }.onFailure { Log.w("StreamMerger", "mux failed", it) }.getOrDefault(false)

    /** Re-muxes an already merged file with explicit HDR color tags. */
    fun remuxWithHdrTags(context: Context, source: File, output: File): Boolean = runCatching {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val ffmpeg = File(packages, "ffmpeg/usr/bin/ffmpeg")
        val binary = if (ffmpeg.isFile) ffmpeg else
            File(File(context.applicationInfo.nativeLibraryDir), "libffmpeg.so")
        if (!binary.isFile || !source.isFile) return false
        FastAlloc.reserve(output, source.length())
        val libPath = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        val command = listOf(
            binary.absolutePath, "-y", "-loglevel", "error",
            "-i", source.absolutePath,
            "-map", "0", "-c", "copy",
            "-color_primaries", "bt2020",
            "-color_trc", "smpte2084",
            "-colorspace", "bt2020nc",
            output.absolutePath
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().put("LD_LIBRARY_PATH", libPath) }
            .start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val ok = process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0 &&
            output.isFile && output.length() > 0L
        if (!ok) Log.w("StreamMerger", "HDR remux failed: ${log.take(400)}")
        ok
    }.onFailure { Log.w("StreamMerger", "HDR remux crashed", it) }.getOrDefault(false)

}
