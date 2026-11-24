package com.mmtube.app.util

import android.media.MediaCodecList

/** Checks whether this device can actually decode a chosen video codec. */
object FormatSupport {

    fun isPlayable(vcodec: String?): Boolean {
        if (vcodec.isNullOrBlank() || vcodec.equals("none", true)) return true
        val mime = when {
            vcodec.startsWith("av01", true) -> "video/av01"
            vcodec.startsWith("vp9", true) || vcodec.startsWith("vp09", true) ->
                "video/x-vnd.on2.vp9"
            vcodec.startsWith("vp8", true) -> "video/x-vnd.on2.vp8"
            vcodec.startsWith("avc", true) || vcodec.startsWith("h264", true) -> "video/avc"
            vcodec.startsWith("hev", true) || vcodec.startsWith("hvc", true) -> "video/hevc"
            else -> return true // unknown codec: do not block the download
        }
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
        }.getOrDefault(true)
    }

    fun isAudioPlayable(acodec: String?): Boolean {
        if (acodec.isNullOrBlank() || acodec.equals("none", true)) return true
        val value = acodec.lowercase()
        val mime = when {
            value.startsWith("flac") -> "audio/flac"
            value.startsWith("opus") -> "audio/opus"
            value.startsWith("vorbis") -> "audio/vorbis"
            value.startsWith("mp3") -> "audio/mpeg"
            value.startsWith("aac") || value.startsWith("mp4a") -> "audio/mp4a-latm"
            value.startsWith("ac3") -> "audio/ac3"
            value.startsWith("eac3") -> "audio/eac3"
            else -> return true
        }
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
        }.getOrDefault(true)
    }

}
