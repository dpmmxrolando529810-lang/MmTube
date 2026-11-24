package com.mmtube.app.util

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads the server's Retry-After hint so retries do not hammer it. */
object RetryAfter {

    fun delayMs(url: String): Long = runCatching {
        if (!url.startsWith("http", true)) return 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        try {
            val value = connection.getHeaderField("Retry-After")?.trim().orEmpty()
            if (value.isEmpty()) return 0L
            val seconds = value.toLongOrNull()
            val millis = if (seconds != null) {
                seconds * 1000L
            } else {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                format.timeZone = TimeZone.getTimeZone("GMT")
                format.parse(value)?.time?.minus(System.currentTimeMillis()) ?: 0L
            }
            millis.coerceIn(0L, 30_000L)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(0L)
}
