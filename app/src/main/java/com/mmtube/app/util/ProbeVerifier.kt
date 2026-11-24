package com.mmtube.app.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ColorInfo(val isHdr: Boolean, val bitDepth: Int, val colorSpace: String?)
data class FrameInfo(val avgFps: Float, val rFps: Float) {
    val variable: Boolean get() = rFps > 0f && avgFps > 0f && kotlin.math.abs(rFps - avgFps) / rFps > 0.05f
}

/** Uses the bundled ffprobe to verify that HDR/color metadata survived the mux. */
object ProbeVerifier {

    fun inspect(context: Context, file: File): ColorInfo? = runCatching {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val binary = File(packages, "ffmpeg/usr/bin/ffprobe")
        if (!binary.isFile) return null
        val libPath = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        val command = listOf(
            binary.absolutePath, "-v", "quiet", "-select_streams", "v:0",
            "-show_entries",
            "stream=pix_fmt,bits_per_raw_sample,color_space,color_transfer,color_primaries",
            "-of", "json", file.absolutePath
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().put("LD_LIBRARY_PATH", libPath) }
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        val stream = JSONObject(text).optJSONArray("streams")?.optJSONObject(0) ?: return null
        val pix = stream.optString("pix_fmt")
        val space = stream.optString("color_space").takeIf { it.isNotBlank() && it != "unknown" }
        val transfer = stream.optString("color_transfer")
        val depth = stream.optString("bits_per_raw_sample").toIntOrNull()
            ?: if (pix.contains("10") || pix.contains("12")) 10 else 8
        val hdr = (space?.let { it.contains("2020") } == true &&
            (transfer.contains("2084") || transfer.contains("b67"))) || depth >= 10
        ColorInfo(hdr, depth, space)
    }.onFailure { Log.w("ProbeVerifier", "ffprobe check failed", it) }.getOrNull()

    /** Compares average vs nominal frame rate to flag VFR streams. */
    fun frameInfo(context: Context, file: File): FrameInfo? = runCatching {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val binary = File(packages, "ffmpeg/usr/bin/ffprobe")
        if (!binary.isFile) return null
        val libPath = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        val command = listOf(
            binary.absolutePath, "-v", "quiet", "-select_streams", "v:0",
            "-show_entries", "stream=avg_frame_rate,r_frame_rate",
            "-of", "json", file.absolutePath
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().put("LD_LIBRARY_PATH", libPath) }
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        val stream = JSONObject(text).optJSONArray("streams")?.optJSONObject(0) ?: return null
        fun parse(value: String): Float {
            val parts = value.split("/")
            val a = parts.getOrNull(0)?.toFloatOrNull() ?: return 0f
            val b = parts.getOrNull(1)?.toFloatOrNull() ?: return a
            return if (b == 0f) 0f else a / b
        }
        FrameInfo(parse(stream.optString("avg_frame_rate")), parse(stream.optString("r_frame_rate")))
    }.onFailure { Log.w("ProbeVerifier", "frame check failed", it) }.getOrNull()

}
