package com.mmtube.app.util

/** Platform-specific yt-dlp extractor arguments. */
object ExtractorArgs {

    fun forUrl(url: String, tiktokProfile: Int = 0): String? {
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        return when {
            host.contains("tiktok") -> tiktokArgs(tiktokProfile)
            host.contains("facebook") || host.contains("fb.watch") -> null
            else -> youtubeArgs()
        }
    }

    private fun youtubeArgs(): String =
        "youtube:player_client=android,ios,tv,web_safari,mweb;formats=duplicate"

    private fun tiktokArgs(profile: Int): String? = when (profile) {
        1 -> "tiktok:api_hostname=api22-normal-c-alisg.tiktokv.com"
        2 -> "tiktok:app_name=trill;app_version=34.1.2;" +
            "manifest_app_version=2023401020;aid=1180"
        else -> null
    }
}
