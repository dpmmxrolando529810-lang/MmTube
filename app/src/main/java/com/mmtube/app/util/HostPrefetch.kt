package com.mmtube.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Warms TCP/TLS connections to the hosts a batch is about to use. */
object HostPrefetch {

    suspend fun warm(urls: List<String>) = withContext(Dispatchers.IO) {
        val hosts = urls.mapNotNull { runCatching { URL(it).host }.getOrNull() }
            .distinct().take(8)
        coroutineScope {
            hosts.map { host ->
                async {
                    runCatching {
                        val connection = URL("https://$host").openConnection() as HttpURLConnection
                        connection.requestMethod = "HEAD"
                        connection.connectTimeout = 3_000
                        connection.readTimeout = 3_000
                        try {
                            connection.responseCode
                        } finally {
                            connection.disconnect()
                        }
                    }.onFailure { Log.d("HostPrefetch", "warm failed for $host: ${it.message}") }
                }
            }.awaitAll()
        }
    }
}
