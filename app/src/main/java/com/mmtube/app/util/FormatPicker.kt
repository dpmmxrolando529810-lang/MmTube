package com.mmtube.app.util

import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.MediaFormatOption
import com.mmtube.app.data.ProbeResult
import kotlin.math.min

/**
 * Our own quality ranking. yt-dlp's -S is generic: a 4K stream with a tiny
 * bitrate can win over a much better 1080p stream. We score every format from
 * the probe data ourselves and pass explicit format ids to yt-dlp (-f id+id).
 */
object FormatPicker {

    data class Choice(
        val format: String,
        val height: Int,
        val fps: Int,
        val hdr: Boolean,
        val alternates: List<String> = emptyList(),
        /** A direct progressive file already contains audio: no +bestaudio needed. */
        val complete: Boolean = false
    )

    fun score(format: MediaFormatOption, hdrSupported: Boolean): Double {
        if (!format.isVideo || format.height <= 0) return -1.0
        val bitrate = format.tbr.toDouble().takeIf { it > 0 }
            ?: format.sizeBytes.toDouble().div(120_000L).takeIf { it > 0 }
            ?: return -1.0
        val pixels = format.height.toDouble() * format.height * 1.78
        val perPixel = bitrate * 1000.0 / pixels
        val fpsBonus = when {
            format.fps >= 50 -> 1.18
            format.fps >= 30 -> 1.06
            else -> 1.0
        }
        val hdrBonus = when {
            format.hdr && hdrSupported -> 1.22
            format.hdr -> 0.94 // HDR text but this screen cannot show it
            else -> 1.0
        }
        val codecBonus = when {
            format.vcodec?.startsWith("av01", true) == true -> 1.08
            format.vcodec?.startsWith("vp9", true) == true -> 1.05
            format.vcodec?.startsWith("avc", true) == true ||
                format.vcodec?.startsWith("h264", true) == true -> 0.99
            else -> 1.0
        }
        val sourceBonus = if (format.note.orEmpty().let {
                it.contains("original", true) || it.contains("source", true)
            }
        ) 1.06 else 1.0
        return (bitrate + perPixel * 90.0) * fpsBonus * hdrBonus *
            codecBonus * sourceBonus * (1.0 + min(format.height, 4320) / 9000.0)
    }

    fun choose(
        result: ProbeResult,
        mode: DownloadMode,
        hdrSupported: Boolean = false,
        preferMultichannel: Boolean = false
    ): Choice? {
        if (mode == DownloadMode.MP3) return null
        val cap = when (mode) {
            DownloadMode.P1080 -> 1080
            DownloadMode.P720 -> 720
            else -> Int.MAX_VALUE
        }
        val videos = result.formats
            .filter { it.isVideo && it.height in 1..cap }
            .sortedByDescending { score(it, hdrSupported) }
        val primary = videos.firstOrNull() ?: return null
        val audios = result.formats.filter { !it.isVideo }.let { list ->
            if (preferMultichannel) {
                list.sortedWith(
                    compareByDescending<com.mmtube.app.data.MediaFormatOption> { it.channels }
                        .thenByDescending { it.tbr }
                )
            } else {
                list.sortedByDescending { it.tbr }
            }
        }
        val audio = audios.firstOrNull()

        // A direct progressive https file avoids two downloads + an mux. Only
        // take it when it is close to the best adaptive pair in real quality.
        val combinedBitrate = primary.tbr + (audio?.tbr ?: 0)
        val progressive = result.formats
            .filter { format ->
                format.isProgressive && format.height in 1..cap &&
                    format.protocol?.startsWith("http") == true &&
                    format.protocol?.contains("dash") != true &&
                    format.protocol?.contains("m3u8") != true &&
                    format.height >= primary.height * 0.9 &&
                    (combinedBitrate <= 0 || format.tbr >= combinedBitrate * 0.85)
            }
            .maxByOrNull { it.tbr }
        if (progressive != null) {
            return Choice(
                format = progressive.id,
                height = progressive.height,
                fps = progressive.fps,
                hdr = progressive.hdr,
                alternates = videos.take(2).map { "${it.id}+bestaudio" },
                complete = true
            )
        }

        val format = if (audio != null) "${primary.id}+${audio.id}" else "${primary.id}+bestaudio"
        val alternates = videos.drop(1).take(2).map { "${it.id}+bestaudio" }
        return Choice(format, primary.height, primary.fps, primary.hdr, alternates, false)
    }
}
