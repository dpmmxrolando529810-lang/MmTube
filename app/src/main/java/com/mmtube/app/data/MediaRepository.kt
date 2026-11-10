package com.mmtube.app.data

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.mmtube.app.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MediaRepository(context: Context, private val engine: EngineManager) {

    private val fastProbe = FastProbe(context)
    @Volatile private var fastProbeDisabled = false
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, CacheEntry>()

    suspend fun probe(rawUrl: String): Result<ProbeResult> {
        return runCatching {
            val url = UrlUtils.normalize(rawUrl)
                ?: throw MmtubeException(ErrorKind.INVALID_LINK, "Invalid URL")
            cached(url)?.let { return@runCatching it }
            if (!engine.ensureReady()) {
                throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
            }
            // Fast path: yt-dlp without the JavaScript runtime. Falls back to the
            // wrapper probe (QuickJS enabled) when the fast run fails or times
            // out; after a timeout the fast path is parked for this session so a
            // bad run can never make every analysis wait twice.
            val json = if (fastProbeDisabled) {
                probeWithLibrary(url)
            } else {
                when (val run = fastProbe.run(url)) {
                    is FastProbe.Result.Ok -> run.json
                    FastProbe.Result.TimedOut -> {
                        fastProbeDisabled = true
                        probeWithLibrary(url)
                    }
                    FastProbe.Result.Failed -> probeWithLibrary(url)
                }
            }
            val info = parse(json)
            val result = buildResult(url, info)
            remember(url, result)
            result
        }.recoverCatching { error ->
            throw normalizeProbeError(error)
        }
    }

    private fun parse(json: String): VideoInfo {
        return runCatching {
            YoutubeDL.objectMapper.readValue(json, VideoInfo::class.java)
        }.getOrElse {
            throw MmtubeException(ErrorKind.GENERIC, "Could not parse media information", it)
        }
    }

    private suspend fun probeWithLibrary(url: String): String {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-update")
            addOption("--no-check-formats")
            addOption("--ignore-config")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("--extractor-retries", "2")
        }
        val processId = "probe-${System.nanoTime()}"
        val response = try {
            withTimeoutOrNull(LIBRARY_PROBE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
                        request,
                        processId = processId,
                        redirectErrorStream = false,
                        callback = null
                    )
                }
            } ?: throw MmtubeException(
                ErrorKind.NETWORK,
                "Analysis timed out. Check your connection and retry."
            )
        } catch (error: Throwable) {
            YoutubeDL.getInstance().destroyProcessById(processId)
            throw error
        }
        return response.out.lineSequence()
            .firstOrNull { it.trimStart().startsWith("{") }
            ?: throw MmtubeException(
                ErrorKind.UNSUPPORTED,
                response.err.takeIf { it.isNotBlank() } ?: "No media information returned"
            )
    }

    private fun cached(url: String): ProbeResult? = synchronized(cacheLock) {
        val entry = cache[url] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(url)
            null
        } else {
            entry.result
        }
    }

    private fun remember(url: String, result: ProbeResult) = synchronized(cacheLock) {
        cache[url] = CacheEntry(System.currentTimeMillis(), result)
        while (cache.size > CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
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

    private data class CacheEntry(val at: Long, val result: ProbeResult)

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val CACHE_ENTRIES = 20
        const val LIBRARY_PROBE_TIMEOUT_MS = 120_000L
    }
}
