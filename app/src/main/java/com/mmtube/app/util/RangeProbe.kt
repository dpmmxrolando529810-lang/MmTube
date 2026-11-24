package com.mmtube.app.util

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

data class RangeInfo(val supported: Boolean, val rttMs: Long = 0L)

/** Checks whether a server accepts byte ranges and how fast it answers. */
object RangeProbe {

    private data class Entry(val at: Long, val info: RangeInfo)
    private val cache = HashMap<String, Entry>()
    private const val TTL_MS = 30 * 60 * 1000L

    fun check(url: String): RangeInfo = synchronized(cache) {
        val cached = cache[url]
        if (cached != null && System.currentTimeMillis() - cached.at < TTL_MS) {
            cached.info
        } else {
            val result = probe(url)
            cache[url] = Entry(System.currentTimeMillis(), result)
            if (cache.size > 60) cache.clear()
            result
        }
    }

    fun supports(url: String): Boolean = check(url).supported

    private fun probe(url: String): RangeInfo = runCatching {
        if (!url.startsWith("http", true)) return RangeInfo(false)
        val started = System.nanoTime()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            val header = connection.getHeaderField("Accept-Ranges")?.lowercase().orEmpty()
            val rtt = (System.nanoTime() - started) / 1_000_000L
            RangeInfo(
                supported = status == HttpURLConnection.HTTP_PARTIAL || header.contains("bytes"),
                rttMs = rtt
            )
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w("RangeProbe", "Range check failed: ${it.message}") }
        .getOrDefault(RangeInfo(false))
}
