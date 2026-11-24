package com.mmtube.app.util

import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.MediaFormatOption
import com.mmtube.app.data.ProbeResult
import kotlin.math.min

/**
 * Our own quality ranking. yt-dlp's -S is good but generic: a 4K stream with a
 * tiny bitrate can win over a much better 1080p stream. Here we score every
 * format using the data we already got from the probe, then pass explicit
 * format ids to yt-dlp (-f id+bestaudio) so *our* choice wins.
 */
object FormatPicker {

    data class Choice(
        val format: String,
        val height: Int,
        val fps: Int,
        val hdr: Boolean,
        val alternates: List<String> = emptyList()
    )

    fun score(format: MediaFormatOption): Double {
        if (!format.isVideo || format.height <= 0) return -1.0
        val bitrate = format.tbr.toDouble().takeIf { it > 0 }
            ?: format.sizeBytes.toDouble().div(120_000L).takeIf { it > 0 }
            ?: return -1.0
        // Approximate pixels: height * height * 16/9 (we only store height).
        val pixels = format.height.toDouble() * format.height * 1.78
        val perPixel = bitrate * 1000.0 / pixels
        val fpsBonus = when {
            format.fps >= 50 -> 1.18
            format.fps >= 30 -> 1.06
            else -> 1.0
        }
        val hdrBonus = if (format.hdr) 1.22 else 1.0
        // A little resolution bonus, but never enough to beat a much higher bitrate.
        return (bitrate + perPixel * 90.0) * fpsBonus * hdrBonus *
            (1.0 + min(format.height, 4320) / 9000.0)
    }

    /** Chooses video + audio explicitly; returns null when the probe has no formats. */
    fun choose(result: ProbeResult, mode: DownloadMode): Choice? {
        if (mode == DownloadMode.MP3) return null
        val cap = when (mode) {
            DownloadMode.P1080 -> 1080
            DownloadMode.P720 -> 720
            else -> Int.MAX_VALUE
        }
        val videos = result.formats
            .filter { it.isVideo && it.height in 1..cap }
            .sortedByDescending { score(it) }
        val primary = videos.firstOrNull() ?: return null
        val audios = result.formats.filter { !it.isVideo }.sortedByDescending { it.tbr }
        val audio = audios.firstOrNull()
        val format = if (audio != null) "${primary.id}+${audio.id}" else "${primary.id}+bestaudio"
        val alternates = videos.drop(1).take(2).map { "${it.id}+bestaudio" }
        return Choice(format, primary.height, primary.fps, primary.hdr, alternates)
    }
}
