package com.mmtube.app.util

import java.io.File
import java.io.RandomAccessFile

/** Pre-allocates a known-size output file to reduce fragmentation on slow storage. */
object FastAlloc {

    fun reserve(file: File, bytes: Long): Boolean = runCatching {
        if (bytes <= 0L) return false
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes.coerceAtMost(8L * 1024 * 1024 * 1024)) }
        true
    }.getOrDefault(false)
}
