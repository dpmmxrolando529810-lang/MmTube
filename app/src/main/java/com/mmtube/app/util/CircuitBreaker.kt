package com.mmtube.app.util

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/** Stops retrying a host that keeps failing inside one batch/session. */
object CircuitBreaker {

    private data class State(var failures: Int, var until: Long)
    private val states = ConcurrentHashMap<String, State>()
    private const val COOLDOWN_MS = 2 * 60 * 1000L
    private const val MAX_FAILURES = 3

    private fun host(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase().orEmpty()
            .ifBlank { url }

    fun isOpen(url: String): Boolean {
        val state = states[host(url)] ?: return false
        if (System.currentTimeMillis() >= state.until) {
            states.remove(host(url))
            return false
        }
        return state.failures >= MAX_FAILURES
    }

    fun recordFailure(url: String) {
        val key = host(url)
        val state = states.getOrPut(key) { State(0, 0L) }
        state.failures += 1
        state.until = System.currentTimeMillis() + COOLDOWN_MS
    }

    fun recordSuccess(url: String) {
        states.remove(host(url))
    }

    fun reset() = states.clear()
}
