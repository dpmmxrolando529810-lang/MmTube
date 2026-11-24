package com.mmtube.app.data

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.mmtube.app.util.CookieStore
import com.mmtube.app.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MediaRepository(private val context: Context, private val engine: EngineManager) {

    private val fastProbe = FastProbe(context)
    @Volatile private var fastProbeDisabled = false
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, CacheEntry>()

    suspend fun probe(rawUrl: String): Result<ProbeResult> {
        return runCatching {
            val cookiesPath = CookieStore.path(context)
            val url = UrlUtils.normalize(rawUrl)
                ?: throw MmtubeException(ErrorKind.INVALID_LINK, "Invalid URL")
            cached(url)?.let { return@runCatching it }
            if (!engine.ensureReady()) {
                throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
            }
            val json = try {
                probeJson(url, cookiesPath, ageFallback = false)
            } catch (error: Throwable) {
                val age = error is MmtubeException && error.kind == ErrorKind.AGE ||
                    error.message?.contains("age", true) == true
                if (age && cookiesPath != null) {
                    probeJson(url, cookiesPath, ageFallback = true)
                } else {
                    throw error
                }
            }
            val info = parse(json)
            val result = buildResult(url, info, json)
            remember(url, result)
            result
        }.recoverCatching { error ->
            throw normalizeProbeError(error)
        }
    }

    private suspend fun probeJson(
        url: String,
        cookiesPath: String?,
        ageFallback: Boolean
    ): String {
        // Fast path: yt-dlp without the JavaScript runtime. Falls back to the
        // wrapper probe (QuickJS enabled) when the fast run fails or times out.
        if (fastProbeDisabled) return probeWithLibrary(url, cookiesPath, ageFallback)
        return when (val run = fastProbe.run(url, cookiesPath = cookiesPath, ageFallback = ageFallback)) {
            is FastProbe.Result.Ok -> run.json
            is FastProbe.Result.Lines -> run.json.firstOrNull() ?: "{}"
            FastProbe.Result.TimedOut -> {
                fastProbeDisabled = true
                probeWithLibrary(url, cookiesPath, ageFallback)
            }
            FastProbe.Result.Failed -> probeWithLibrary(url, cookiesPath, ageFallback)
        }
    }

    /** Analyses a whole batch in one yt-dlp process, returning results per URL. */
    suspend fun probeMany(
        rawUrls: List<String>
    ): Map<String, Result<ProbeResult>> = withContext(Dispatchers.IO) {
        val urls = rawUrls.mapNotNull { UrlUtils.normalize(it) }.distinct()
        val output = LinkedHashMap<String, Result<ProbeResult>>()
        val pending = mutableListOf<String>()
        for (url in urls) {
            val hit = cached(url)
            if (hit != null) output[url] = Result.success(hit) else pending.add(url)
        }
        if (pending.isEmpty()) return@withContext output
        if (!engine.ensureReady()) {
            pending.forEach { output[it] = Result.failure(MmtubeException(ErrorKind.ENGINE, "Engine not ready")) }
            return@withContext output
        }
        val cookiesPath = CookieStore.path(context)
        val jsonLines = runCatching {
            when (val run = fastProbe.runMany(pending, cookiesPath = cookiesPath)) {
                is FastProbe.Result.Ok -> listOf(run.json)
                is FastProbe.Result.Lines -> run.json
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (jsonLines.size == pending.size) {
            pending.zip(jsonLines).forEach { (url, line) ->
                output[url] = buildOne(url, line)
            }
        } else {
            pending.forEach { url -> output[url] = probe(url) }
        }
        output
    }

    private fun buildOne(url: String, json: String): Result<ProbeResult> = runCatching {
        val info = parse(json)
        val result = buildResult(url, info, json)
        remember(url, result)
        result
    }.recoverCatching { throw normalizeProbeError(it) }

    /** Drops huge unused fields (thumbnails, captions, descriptions, comments). */
    private fun slimJson(json: String): String = runCatching {
        val root = YoutubeDL.objectMapper.readTree(json)
        if (!root.isObject) return json
        val rootObject = root as com.fasterxml.jackson.databind.node.ObjectNode
        val keepTop = setOf(
            "id", "title", "fulltitle", "duration", "uploader", "uploader_id",
            "thumbnail", "extractor_key", "extractor", "webpage_url", "width",
            "height", "file_size", "file_size_approx", "formats"
        )
        val fields = root.fields().asSequence().toList()
        fields.forEach { (name, _) -> if (name !in keepTop) rootObject.remove(name) }
        val array = root.path("formats")
        if (array.isArray) {
            val keepFormat = setOf(
                "format_id", "format_note", "height", "width", "fps", "tbr", "abr",
                "vcodec", "acodec", "codecs", "dynamic_range", "filesize",
                "filesize_approx", "protocol", "ext", "channels", "codecs"
            )
            for (node in array) {
                if (!node.isObject) continue
                val objectNode = node as com.fasterxml.jackson.databind.node.ObjectNode
                objectNode.fields().asSequence().toList().forEach { (name, _) ->
                    if (name !in keepFormat) objectNode.remove(name)
                }
            }
        }
        YoutubeDL.objectMapper.writeValueAsString(root)
    }.getOrDefault(json)

    private fun parse(json: String): VideoInfo {
        return runCatching {
            YoutubeDL.objectMapper.readValue(slimJson(json), VideoInfo::class.java)
        }.getOrElse {
            throw MmtubeException(ErrorKind.GENERIC, "Could not parse media information", it)
        }
    }

    private suspend fun probeWithLibrary(
        url: String,
        cookiesPath: String?,
        ageFallback: Boolean = false
    ): String {
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
            if (cookiesPath != null) addOption("--cookies", cookiesPath)
            addOption(
                "--extractor-args",
                if (ageFallback) {
                    "youtube:player_client=web_embedded,ios_producer,android"
                } else {
                    "youtube:player_client=android,ios,tv,web_safari,mweb;formats=duplicate"
                }
            )
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

    private fun buildResult(url: String, info: VideoInfo, rawJson: String? = null): ProbeResult {
        val formats = info.formats.orEmpty()
        val extra: Map<String, com.fasterxml.jackson.databind.JsonNode> = runCatching {
            val root = YoutubeDL.objectMapper.readTree(rawJson ?: "{}")
            val array = root.path("formats")
            if (!array.isArray) return@runCatching emptyMap()
            val map = LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>()
            for (node in array) {
                val id = node.path("format_id").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                if (!id.isNullOrBlank()) map[id] = node
            }
            map
        }.getOrDefault(emptyMap())
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
        val formatOptions = formats.mapNotNull { format ->
            val id = format.formatId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val video = format.vcodec != null && format.vcodec != "none" && format.height > 0
            val audio = format.acodec != null && format.acodec != "none" &&
                (format.vcodec == null || format.vcodec == "none")
            if (!video && !audio) return@mapNotNull null
            val node = extra[id]
            val note = format.formatNote?.takeIf { it.isNotBlank() }
                ?: node?.path("format_note")?.takeUnless { it.isMissingNode || it.isNull }?.asText()
            val dynamicRange = node?.path("dynamic_range")?.takeUnless { it.isMissingNode || it.isNull }?.asText().orEmpty()
            val codec = format.vcodec?.lowercase().orEmpty()
            val protocol = node?.path("protocol")?.takeUnless { it.isMissingNode || it.isNull }
                ?.asText()
            val ext = node?.path("ext")?.takeUnless { it.isMissingNode || it.isNull }?.asText()
                ?: format.ext
            val channels = node?.path("channels")?.takeUnless { it.isMissingNode || it.isNull }
                ?.asInt() ?: 0
            val codecTags = node?.path("codecs")?.takeUnless { it.isMissingNode || it.isNull }
                ?.asText().orEmpty()
            val depth = when {
                codec.contains("vp9.2") || codecTags.contains("VP9.2", true) -> 10
                codec.contains("av01.2") || codecTags.contains("AV01.2", true) -> 10
                codec.contains("hvc1") || codec.contains("hev1") -> 10
                note.orEmpty().contains("10-bit", true) -> 10
                else -> 0
            }
            val hdr = note.orEmpty().contains("hdr", true) ||
                dynamicRange.contains("hdr", true) ||
                codec.contains("vp9.2") ||
                codec.startsWith("av01.2") ||
                codec.contains("hvc1") ||
                codec.contains("hev1")
            MediaFormatOption(
                id = id,
                height = format.height.coerceAtLeast(0),
                fps = format.fps.coerceAtLeast(0),
                tbr = format.tbr.coerceAtLeast(0),
                vcodec = format.vcodec?.takeIf { it != "none" },
                acodec = format.acodec?.takeIf { it != "none" },
                sizeBytes = format.fileSize.takeIf { it > 0 }
                    ?: format.fileSizeApproximate.takeIf { it > 0 } ?: 0L,
                isVideo = video,
                note = note,
                hdr = hdr,
                protocol = protocol,
                ext = ext,
                isProgressive = video && !format.acodec.isNullOrBlank() &&
                    format.acodec != "none",
                channels = channels,
                bitDepth = depth
            )
        }.distinctBy { it.id }.let { all ->
            val videos = all.filter { it.isVideo }.sortedWith(
                compareByDescending<MediaFormatOption> { it.height }
                    .thenByDescending { it.fps }.thenByDescending { it.tbr }
            ).take(20)
            val audios = all.filterNot { it.isVideo }
                .sortedByDescending { it.tbr }.take(6)
            videos + audios
        }
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
            webpageUrl = info.webpageUrl ?: url,
            formats = formatOptions
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
