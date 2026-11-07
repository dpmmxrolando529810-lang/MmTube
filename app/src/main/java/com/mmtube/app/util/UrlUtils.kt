package com.mmtube.app.util

import android.net.Uri
import com.mmtube.app.data.Platform

object UrlUtils {

    private val urlRegex = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text.trim())?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', ')', ']', '}', '"', '\'')
    }

    fun normalize(raw: String?): String? {
        val candidate = extractUrl(raw) ?: raw?.trim().orEmpty()
        if (candidate.isBlank()) return null
        val withScheme = when {
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("www.", true) -> "https://$candidate"
            else -> return null
        }
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains('.')) return null
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
        return uri.toString()
    }

    fun detectPlatform(raw: String?): Platform? {
        val url = normalize(raw) ?: return null
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return null
        return when {
            host == "youtu.be" ||
                host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" ||
                host.endsWith(".youtube-nocookie.com") -> Platform.YOUTUBE

            host == "fb.watch" ||
                host == "fb.com" ||
                host.endsWith(".fb.com") ||
                host == "facebook.com" ||
                host.endsWith(".facebook.com") -> Platform.FACEBOOK

            host == "tiktok.com" || host.endsWith(".tiktok.com") -> Platform.TIKTOK

            else -> Platform.OTHER
        }
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null
}
