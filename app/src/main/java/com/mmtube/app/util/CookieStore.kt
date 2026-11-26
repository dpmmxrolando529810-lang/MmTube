package com.mmtube.app.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Optional Netscape cookies.txt used by yt-dlp for private / member-only and
 * higher-quality formats. Stored inside app-private storage on purpose: these
 * files contain session tokens and must never be backed up or shared.
 */
object CookieStore {

    private const val NAME = "cookies.txt"

    private fun file(context: Context): File = File(context.applicationContext.noBackupFilesDir, NAME)

    fun path(context: Context): String? = runCatching {
        file(context).takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }.getOrNull()

    fun hasCookies(context: Context): Boolean = path(context) != null

    /** Copies the picked document into app-private storage. */
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
            it.readText()
        } ?: return false
        val valid = text.lineSequence().any { line ->
            line.startsWith("# Netscape HTTP Cookie File", true) ||
                (line.isNotBlank() && !line.startsWith("#") && line.count { c -> c == '\t' } >= 6)
        }
        if (!valid) return false
        val target = file(context)
        val temp = File(target.parentFile, "cookies.txt.new")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) return false
        temp.renameTo(target)
    }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Saves a pasted Netscape cookie string into app-private storage. */
    fun saveText(context: Context, text: String): Boolean {
        val value = text.trim()
        val valid = value.lineSequence().any { line ->
            line.startsWith("# Netscape HTTP Cookie File", true) ||
                (line.isNotBlank() && !line.startsWith("#") && line.count { c -> c == '\t' } >= 6)
        }
        if (!valid) return false
        val target = file(context)
        val temp = File(target.parentFile, "cookies.txt.new")
        temp.writeText(value, Charsets.UTF_8)
        if (target.exists() && !target.delete()) return false
        return temp.renameTo(target)
    }

}
