package com.mmtube.app.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class StoredVideo(val height: Int, val fps: Int)

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
                return StoredVideo(height, fps)
            }
            null
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /** Returns a short note only when there is a real, visible downgrade. */
    fun mismatchNote(
        context: Context,
        uri: Uri,
        expectedHeight: Int,
        expectedFps: Int
    ): String? {
        if (expectedHeight <= 0) return null
        val actual = inspect(context, uri) ?: return null
        if (actual.height <= 0) return null
        val lowerResolution = actual.height < expectedHeight * 0.88
        val lowerFps = expectedFps > 0 && actual.fps > 0 && actual.fps < expectedFps * 0.8
        if (!lowerResolution && !lowerFps) return null
        return context.getString(
            com.mmtube.app.R.string.quality_mismatch,
            actual.height,
            if (actual.fps > 0) actual.fps else 0,
            expectedHeight,
            expectedFps
        )
    }
}
