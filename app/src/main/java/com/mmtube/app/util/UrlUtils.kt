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

    /**
     * Stable identity for "same video" checks. YouTube links arrive as
     * youtu.be/ID, watch?v=ID, shorts/ID or with si=/utm_ params; all of them
     * must be treated as the same download.
     */
    fun canonicalKey(raw: String?): String? {
        val url = normalize(raw) ?: return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()

        val youtubeId = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> when {
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
                path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
                path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
                else -> uri.getQueryParameter("v").orEmpty()
            }
            else -> ""
        }
        if (youtubeId.isNotBlank()) return "youtube:" + youtubeId

        val fbId = when {
            host == "fb.watch" -> path.trim('/').substringBefore('/')
            host == "facebook.com" || host.endsWith(".facebook.com") ||
                host == "fb.com" || host.endsWith(".fb.com") -> when {
                path.startsWith("/reel/") -> path.removePrefix("/reel/").substringBefore('/')
                path.startsWith("/videos/") -> path.removePrefix("/videos/").substringBefore('/')
                else -> uri.getQueryParameter("v").orEmpty()
            }
            else -> ""
        }
        if (fbId.isNotBlank()) return "facebook:" + fbId

        if (host == "tiktok.com" || host.endsWith(".tiktok.com")) {
            val id = path.trim('/').split('/').lastOrNull { it.isNotBlank() }.orEmpty()
            if (id.isNotBlank()) return "tiktok:$id"
        }

        val cleanPath = path.trimEnd('/').lowercase()
        return "$host$cleanPath"
    }

    fun sameVideo(a: String?, b: String?): Boolean {
        val keyA = canonicalKey(a) ?: return false
        val keyB = canonicalKey(b) ?: return false
        return keyA == keyB
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
