package com.mmtube.app.util

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Checks whether a server accepts byte ranges before aria2c splits the file. */
object RangeProbe {

    private data class Entry(val at: Long, val supported: Boolean)
    private val cache = HashMap<String, Entry>()
    private const val TTL_MS = 30 * 60 * 1000L

    fun supports(url: String): Boolean = synchronized(cache) {
        val cached = cache[url]
        if (cached != null && System.currentTimeMillis() - cached.at < TTL_MS) {
            cached.supported
        } else {
            val result = probe(url)
            cache[url] = Entry(System.currentTimeMillis(), result)
            if (cache.size > 60) cache.clear()
            result
        }
    }

    private fun probe(url: String): Boolean = runCatching {
        if (!url.startsWith("http", true)) return false
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            val header = connection.getHeaderField("Accept-Ranges")?.lowercase().orEmpty()
            status == HttpURLConnection.HTTP_PARTIAL || header.contains("bytes")
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w("RangeProbe", "Range check failed: ${it.message}") }.getOrDefault(false)
}
