package com.mmtube.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "$bytes ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "0:00"
        val total = seconds.coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds < 0L) return "—"
        return formatDuration(seconds)
    }

    fun formatDate(millis: Long?): String {
        if (millis == null || millis <= 0L) return "—"
        return runCatching {
            Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        }.getOrDefault("—")
    }

    fun formatCountdown(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%ds", seconds)
        }
    }

    fun qualityLabel(height: Int, fps: Int = 0): String {
        val base = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height > 0 -> "${height}p"
            else -> "Audio"
        }
        return if (height > 0 && fps >= 50) "$base • ${fps}fps" else base
    }

    fun modeLabel(mode: com.mmtube.app.data.DownloadMode): String = when (mode) {
        com.mmtube.app.data.DownloadMode.MAX -> "MAX"
        com.mmtube.app.data.DownloadMode.P1080 -> "1080p"
        com.mmtube.app.data.DownloadMode.P720 -> "720p"
        com.mmtube.app.data.DownloadMode.MP3 -> "MP3"
    }

    private val speedRegex = Regex(
        """at\s+([\d.]+)\s*([KMGTP]?i?B)/s""",
        RegexOption.IGNORE_CASE
    )
    private val totalRegex = Regex(
        """of\s+~?\s*([\d.]+)\s*([KMGTP]?i?B)""",
        RegexOption.IGNORE_CASE
    )

    fun parseSpeed(line: String): Long {
        val match = speedRegex.find(line) ?: return 0L
        return parseSize(match.groupValues[1], match.groupValues[2])
    }

    fun parseTotal(line: String): Long {
        val match = totalRegex.find(line) ?: return 0L
        return parseSize(match.groupValues[1], match.groupValues[2])
    }

    private fun parseSize(number: String, unit: String): Long {
        val value = number.toDoubleOrNull() ?: return 0L
        val multiplier = when (unit.uppercase(Locale.US)) {
            "B" -> 1L
            "KB" -> 1024L
            "KIB" -> 1024L
            "MB" -> 1024L * 1024L
            "MIB" -> 1024L * 1024L
            "GB" -> 1024L * 1024L * 1024L
            "GIB" -> 1024L * 1024L * 1024L
            "TB" -> 1024L * 1024L * 1024L * 1024L
            "TIB" -> 1024L * 1024L * 1024L * 1024L
            else -> 1L
        }
        return (value * multiplier).toLong()
    }

    fun progressPercent(progress: Float): Int =
        progress.coerceIn(0f, 100f).toInt()
}
