package com.mmtube.app.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class StoredVideo(
    val height: Int,
    val fps: Int,
    val audioMime: String? = null,
    val audioBitrate: Int = 0
)

/**
 * Reads the saved file itself (no re-encode, no external tool) so we can tell
 * the user when the stored resolution/fps is lower than the format requested.
 */
object MediaVerifier {

    fun inspect(context: Context, uri: Uri): StoredVideo? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
                    ?: continue
                if (!mime.startsWith("video/")) continue
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    format.getInteger(MediaFormat.KEY_HEIGHT)
                } else {
                    0
                }
                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    0
                }
                var audioMime: String? = null
                var audioBitrate = 0
                for (track in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(track)
                    val mime = runCatching { trackFormat.getString(MediaFormat.KEY_MIME) }.getOrNull()
                        ?: continue
                    if (mime.startsWith("audio/")) {
                        audioMime = mime
                        if (trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            audioBitrate = trackFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        break
                    }
                }
                return StoredVideo(height, fps, audioMime, audioBitrate)
            }
            null
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /** Returns a short note only when there is a real, visible downgrade. */
    fun expectedAudioMime(format: String): String = when (format.lowercase()) {
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    fun mismatchNote(
        context: Context,
        uri: Uri,
        expectedHeight: Int,
        expectedFps: Int,
        expectedAudioFormat: String = "mp3"
    ): String? {
        if (expectedHeight <= 0) return null
        val actual = inspect(context, uri) ?: return null
        if (actual.height <= 0) return null
        val lowerResolution = actual.height < expectedHeight * 0.88
        val lowerFps = expectedFps > 0 && actual.fps > 0 && actual.fps < expectedFps * 0.8
        val expectedMime = expectedAudioMime(expectedAudioFormat)
        val audioMismatch = actual.audioMime != null &&
            !actual.audioMime.equals(expectedMime, true) &&
            !(expectedMime == "audio/mpeg" && actual.audioMime.contains("mp4a"))
        if (!lowerResolution && !lowerFps && !audioMismatch) return null
        val base = context.getString(
            com.mmtube.app.R.string.quality_mismatch,
            actual.height,
            if (actual.fps > 0) actual.fps else 0,
            expectedHeight,
            expectedFps
        )
        return if (audioMismatch) {
            base + " · " + context.getString(
                com.mmtube.app.R.string.audio_mismatch,
                actual.audioMime.orEmpty()
            )
        } else {
            base
        }
    }
}
