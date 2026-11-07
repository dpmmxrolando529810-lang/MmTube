package com.mmtube.app.data

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.mmtube.app.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val engine: EngineManager) {

    suspend fun probe(rawUrl: String): Result<ProbeResult> {
        return runCatching {
            val url = UrlUtils.normalize(rawUrl)
                ?: throw MmtubeException(ErrorKind.INVALID_LINK, "Invalid URL")
            if (!engine.ensureReady()) {
                throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
            }
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-formats")
                addOption("--ignore-config")
                addOption("--socket-timeout", "25")
                addOption("--retries", "3")
                addOption("--extractor-retries", "3")
                addOption("--geo-bypass")
            }
            val processId = "probe-${System.nanoTime()}"
            val response = try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
                        request,
                        processId = processId,
                        redirectErrorStream = false,
                        callback = null
                    )
                }
            } catch (error: Throwable) {
                YoutubeDL.getInstance().destroyProcessById(processId)
                throw error
            }
            val json = response.out.lineSequence()
                .firstOrNull { it.trimStart().startsWith("{") }
                ?: throw MmtubeException(
                    ErrorKind.UNSUPPORTED,
                    response.err.takeIf { it.isNotBlank() } ?: "No media information returned"
                )
            val info = runCatching {
                YoutubeDL.objectMapper.readValue(json, VideoInfo::class.java)
            }.getOrElse {
                throw MmtubeException(ErrorKind.GENERIC, "Could not parse media information", it)
            }
            buildResult(url, info)
        }.recoverCatching { error ->
            throw normalizeProbeError(error)
        }
    }

    private fun buildResult(url: String, info: VideoInfo): ProbeResult {
        val formats = info.formats.orEmpty()
        val videoFormats = formats.filter {
            it.vcodec != null && it.vcodec != "none" && it.height > 0
        }
        val audioFormats = formats.filter {
            it.acodec != null && it.acodec != "none" &&
                (it.vcodec == null || it.vcodec == "none")
        }
        val bestVideo = videoFormats.maxWithOrNull(
            compareBy<VideoFormat> { it.height }
                .thenBy { it.fps }
                .thenBy { it.tbr }
        )
        val bestAudio = audioFormats.maxByOrNull { it.abr }
        val height = bestVideo?.height ?: info.height
        val fps = bestVideo?.fps ?: 0
        val videoSize = bestVideo?.fileSize?.takeIf { it > 0 }
            ?: bestVideo?.fileSizeApproximate?.takeIf { it > 0 }
            ?: 0L
        val audioSize = bestAudio?.fileSize?.takeIf { it > 0 }
            ?: bestAudio?.fileSizeApproximate?.takeIf { it > 0 }
            ?: 0L
        val estimated = (videoSize + audioSize).takeIf { it > 0 }
            ?: info.fileSizeApproximate.takeIf { it > 0 }
            ?: info.fileSize.takeIf { it > 0 }
            ?: 0L
        val platform = platformFrom(info.extractorKey ?: info.extractor, url)
        return ProbeResult(
            url = url,
            title = info.title ?: info.fulltitle ?: "Untitled",
            uploader = info.uploader ?: info.uploaderId,
            durationSeconds = info.duration.coerceAtLeast(0),
            thumbnail = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url,
            platform = platform,
            maxHeight = height.coerceAtLeast(0),
            maxFps = fps.coerceAtLeast(0),
            estimatedBytes = estimated,
            audioOnly = height <= 0,
            extractor = info.extractorKey ?: info.extractor,
            webpageUrl = info.webpageUrl ?: url
        )
    }

    private fun platformFrom(extractor: String?, url: String): Platform {
        val value = extractor?.lowercase().orEmpty()
        return when {
            value.contains("youtube") -> Platform.YOUTUBE
            value.contains("facebook") -> Platform.FACEBOOK
            value.contains("tiktok") -> Platform.TIKTOK
            else -> UrlUtils.detectPlatform(url) ?: Platform.OTHER
        }
    }

    private fun normalizeProbeError(error: Throwable): Throwable {
        if (error is MmtubeException) return error
        val message = error.message.orEmpty()
        val lower = message.lowercase()
        val kind = when {
            "private" in lower || "login" in lower || "sign in" in lower -> ErrorKind.PRIVATE
            "age" in lower && ("restrict" in lower || "confirm" in lower) -> ErrorKind.AGE
            "unsupported url" in lower || "not supported" in lower -> ErrorKind.UNSUPPORTED
            "unavailable" in lower || "removed" in lower || "not available" in lower -> ErrorKind.UNAVAILABLE
            "timed out" in lower || "timeout" in lower ||
                "connection" in lower || "network" in lower || "resolve" in lower -> ErrorKind.NETWORK
            else -> ErrorKind.GENERIC
        }
        Log.w("MediaRepository", "Probe failed: ${message.take(400)}")
        return MmtubeException(kind, message, error)
    }
}
