package com.mmtube.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled yt-dlp directly, outside the youtubedl-android wrapper.
 *
 * The wrapper always appends `--js-runtimes quickjs:<libqjs.so>` after the
 * request options, so every analysis solves YouTube's nsig challenge inside
 * QuickJS on the phone CPU. That adds 15-40 s per probe while we only need
 * metadata (title, duration, format list) to show the preview card.
 *
 * Downloads keep using the wrapper with QuickJS enabled, because playable
 * format URLs do need the challenge.
 *
 * The environment below mirrors YoutubeDL.execute() exactly so the same
 * bundled Python runtime is reused.
 */
class FastProbe(private val context: Context) {

    sealed interface Result {
        data class Ok(val json: String) : Result
        data object Failed : Result
        data object TimedOut : Result
    }

    suspend fun run(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        withContext(Dispatchers.IO) {
            val command = buildCommand(url)
            runInterruptible { execute(command, timeoutMs) }
        }

    private fun execute(command: List<String>, timeoutMs: Long): Result {
        val builder = ProcessBuilder(command).redirectErrorStream(false)
        builder.environment().putAll(environment())

        val process = try {
            builder.start()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start yt-dlp", error)
            return Result.Failed
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Thread {
            runCatching { stdout.append(process.inputStream.bufferedReader().readText()) }
        }
        val stderrReader = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (stderr.length < 4_000) stderr.appendLine(line)
                }
            }
        }
        stdoutReader.isDaemon = true
        stderrReader.isDaemon = true
        stdoutReader.start()
        stderrReader.start()

        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Probe timed out after ${timeoutMs}ms")
                return Result.TimedOut
            }
            stdoutReader.join(3_000)
            stderrReader.join(1_000)
            val exit = process.exitValue()
            if (exit != 0) {
                Log.w(TAG, "Probe exit=$exit ${stderr.toString().take(400)}")
                return Result.Failed
            }
            val json = stdout.toString()
            if (!json.trimStart().startsWith("{")) {
                Log.w(TAG, "Probe returned no JSON: ${stderr.toString().take(400)}")
                return Result.Failed
            }
            return Result.Ok(json)
        } finally {
            process.destroyForcibly()
        }
    }

    private fun buildCommand(url: String): List<String> {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val workDir = File(context.noBackupFilesDir, "youtubedl-android")
        return listOf(
            File(nativeDir, "libpython.so").absolutePath,
            File(workDir, "yt-dlp/yt-dlp").absolutePath,
            "--dump-json",
            "--no-playlist",
            "--no-warnings",
            "--no-update",
            "--no-check-formats",
            "--no-js-runtimes",
            "--ignore-config",
            "--socket-timeout", "15",
            "--retries", "2",
            "--extractor-retries", "2",
            url
        )
    }

    private fun environment(): Map<String, String> {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val python = File(packages, "python/usr")
        val libraryPath = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        return mapOf(
            "LD_LIBRARY_PATH" to libraryPath,
            "SSL_CERT_FILE" to File(python, "etc/tls/cert.pem").absolutePath,
            "PATH" to ((System.getenv("PATH") ?: "/system/bin") + ":" + nativeDir.absolutePath),
            "PYTHONHOME" to python.absolutePath,
            "HOME" to python.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath
        )
    }

    companion object {
        private const val TAG = "FastProbe"
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
