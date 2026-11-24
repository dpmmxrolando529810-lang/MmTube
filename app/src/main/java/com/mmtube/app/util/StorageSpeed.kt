package com.mmtube.app.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile

/** Picks the fastest writable temp location once per day. */
object StorageSpeed {

    private const val PREFS = "mmtube_storage_speed"
    private const val KEY_AT = "measured_at"
    private const val KEY_FASTEST = "fastest"
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val SAMPLE_BYTES = 4 * 1024 * 1024

    fun fastestTemp(context: Context): File {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        val cached = prefs.getString(KEY_FASTEST, null)
        if (cached != null && System.currentTimeMillis() - at < TTL_MS) return File(cached)

        val internal = File(context.filesDir, "speedtest")
        val external = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "speedtest"
        )
        val internalMs = measure(internal)
        val externalMs = measure(external)
        val fastest = if (externalMs > 0L && externalMs < internalMs * 80 / 100) external else internal
        prefs.edit().putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_FASTEST, fastest.absolutePath).apply()
        return fastest
    }

    private fun measure(folder: File): Long = runCatching {
        folder.mkdirs()
        val file = File(folder, "speed-${System.nanoTime()}.tmp")
        val start = System.nanoTime()
        val buffer = ByteArray(1024 * 1024)
        RandomAccessFile(file, "rw").use { output ->
            repeat(4) { output.write(buffer) }
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        file.delete()
        elapsed.coerceAtLeast(1L)
    }.getOrDefault(Long.MAX_VALUE)
}
