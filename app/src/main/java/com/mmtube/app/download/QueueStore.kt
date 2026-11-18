package com.mmtube.app.download

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the pending download queue on disk so a process death or a phone
 * reboot does not silently throw away downloads the user lined up.
 *
 * Tiny JSON file in the app's private storage — no permissions, no DataStore
 * schema migration needed.
 */
class QueueStore(context: Context) {

    private val file = File(context.filesDir, "pending_queue.json")
    private val activeFile = File(context.filesDir, "active_download.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(DownloadRequest.serializer())

    /** Queue left over from the previous run (empty when nothing was pending). */
    fun load(): List<DownloadRequest> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    /** The download that was running when the process died, if any. */
    fun loadActive(): DownloadRequest? = runCatching {
        if (!activeFile.exists()) return null
        json.decodeFromString(DownloadRequest.serializer(), activeFile.readText())
    }.getOrNull()

    /** Remember / forget the active download around its lifecycle. */
    @Synchronized
    fun saveActive(request: DownloadRequest?) {
        runCatching {
            if (request == null) {
                if (activeFile.exists()) activeFile.delete()
                return
            }
            val directory = activeFile.parentFile ?: return
            directory.mkdirs()
            val temp = File(directory, activeFile.name + ".tmp")
            temp.writeText(json.encodeToString(DownloadRequest.serializer(), request))
            if (!temp.renameTo(activeFile)) {
                activeFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    /** Persist the queue; an empty queue removes the file entirely. */
    @Synchronized
    fun save(items: List<DownloadRequest>) {
        runCatching {
            if (items.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val directory = file.parentFile ?: return
            directory.mkdirs()
            // Write-then-rename so a crash mid-write cannot corrupt the queue.
            val temp = File(directory, file.name + ".tmp")
            temp.writeText(json.encodeToString(serializer, items))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
