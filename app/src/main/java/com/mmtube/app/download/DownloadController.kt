package com.mmtube.app.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.EngineManager
import com.mmtube.app.data.ErrorKind
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.data.HistoryStore
import com.mmtube.app.data.MediaExporter
import com.mmtube.app.data.MmtubeException
import com.mmtube.app.data.Platform
import com.mmtube.app.data.QuotaManager
import com.mmtube.app.data.SettingsStore
import com.mmtube.app.util.CookieStore
import com.mmtube.app.util.FastAlloc
import com.mmtube.app.util.FormatMemory
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.MediaVerifier
import com.mmtube.app.util.NetworkProfile
import com.mmtube.app.util.ProbeVerifier
import com.mmtube.app.util.RangeProbe
import com.mmtube.app.util.RetryAfter
import com.mmtube.app.util.StorageSpeed
import com.mmtube.app.util.StreamMerger
import com.mmtube.app.util.ThermalGuard
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class DownloadRequest(
    val url: String,
    val title: String,
    val platform: Platform,
    val mode: DownloadMode,
    val thumbnail: String?,
    val durationSeconds: Int,
    val qualityLabel: String,
    /** yt-dlp format id chosen in the format picker, if any. */
    val formatId: String? = null,
    val resume: Boolean = false,
    /** What the preview promised, used to verify the stored file. */
    val expectedHeight: Int = 0,
    val expectedFps: Int = 0,
    val formatIsVideo: Boolean = true,
    /** Alternate format specs tried on retry when the best format is unavailable. */
    val alternates: List<String> = emptyList(),
    val preferMkv: Boolean = false,
    val audioFormat: String = "mp3",
    val adaptiveProfile: Boolean = true,
    val parallelStreams: Boolean = false,
    val estimatedBytes: Long = 0L,
    val memoryKey: String = "",
    val preserveAudio: Boolean = false,
    val autoChunk: Boolean = true,
    val loudnessTarget: Int = 16,
    val verify: Boolean = true,
    val throttledRateKb: Int = 0,
    val checkFormats: Boolean = false,
    val bufferSizeKb: Int = 0,
    val forceIpv4: Boolean = false,
    val sortForce: Boolean = false,
    val embedChapters: Boolean = false,
    val multiVideoStreams: Boolean = false,
    val multiAudioStreams: Boolean = false,
    val keepFragments: Boolean = false,
    val hlsUseMpegts: Boolean = false,
    val proxyUrl: String = "",
    val sourceAddress: String = "",
    /** Stable identity for reorderable queue UI. */
    val queueId: String = java.util.UUID.randomUUID().toString()
)

private data class SpeedConfig(
    val concurrency: Int,
    val chunkMb: Int,
    val speedMode: Boolean,
    val audioFormat: String,
    val parallelStreams: Boolean,
    val supportsRanges: Boolean,
    val network: String,
    val adaptive: Boolean,
    val rateLimitMb: Int = 0
)

class DownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: EngineManager,
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val quotaManager: QuotaManager,
    private val exporter: MediaExporter,
    private val queueStore: QueueStore
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null
    private var processId: String? = null
    private var currentRequest: DownloadRequest? = null
    private var lastProgressUpdate = 0L
    private var lastRawLineAt: Long = 0L

    /** Snapshot of the paused request so the UI can offer Resume. */
    var pausedRequest: DownloadRequest? = null
        private set

    /** When true the queue is held: the active download is paused and the next
     *  item will not start automatically until the user resumes. */
    val queuePaused = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Downloads waiting to start — surfaced for the reorderable queue UI. */
    val queue: kotlinx.coroutines.flow.MutableStateFlow<List<DownloadRequest>> =
        kotlinx.coroutines.flow.MutableStateFlow(queueStore.load())

    init {
        cleanupTempFiles(olderThanMs = 10 * 60 * 1000L)
        // Reboot / process-death recovery: put the interrupted active download
        // back at the front, then continue with the rest of the queue.
        queueStore.loadActive()?.let { interrupted ->
            updateQueue(listOf(interrupted) + queue.value)
            queueStore.saveActive(null)
        }
        // The short delay lets the engine bootstrap settle first.
        if (queue.value.isNotEmpty()) {
            scope.launch {
                kotlinx.coroutines.delay(3_000L)
                if (!isActive() && _state.value !is DownloadState.Paused) {
                    dequeueHead()?.let { restored ->
                        // Use the foreground service so a restored download gets
                        // the same wakelock + progress notification as a fresh one.
                        runCatching { DownloadService.start(context, restored) }
                            .onFailure { start(restored) }
                    }
                }
            }
        }
    }

    /** Single funnel for queue mutations so every change is persisted. */
    private fun updateQueue(items: List<DownloadRequest>) {
        queue.value = items
        queueStore.save(items)
    }

    /** Snapshot of the head of the queue (first queued download) — started automatically when the active one finishes. */
    val queuedRequest: DownloadRequest?
        get() = queue.value.firstOrNull()

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        updateQueue(queue.value.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(toIndex.coerceIn(0, size), item)
        })
    }

    fun removeFromQueue(index: Int): DownloadRequest? {
        val list = queue.value
        if (index !in list.indices) return null
        val removed = list[index]
        updateQueue(list.filterIndexed { i, _ -> i != index })
        return removed
    }

    /** Start the next queued item unless the user paused the queue. */
    private var measuredSpeedBytes = 0L
    private var observedRetries = 0
    private var lastRetryLineAt = 0L
    private var previousSpeed = 0L
    private var previousConcurrency = 0
    private var plateauCount = 0
    private var storageBoundCount = 0
    private var lastWriteSampleAt = 0L
    private var lastWriteBytes = 0L
    private var thermalPenalty = 0

    /** Adjusts fragments from measured speed, retries and diminishing returns. */
    private fun tunedConcurrency(base: Int): Int {
        val retries = observedRetries.coerceAtMost(6)
        val plateau = if (plateauCount >= 2) 8 else 0
        val boost = if (measuredSpeedBytes >= 8L * 1024 * 1024 &&
            retries == 0 && plateauCount == 0
        ) 8 else 0
        val storagePenalty = if (storageBoundCount >= 2) 8 else 0
        return (base + boost - retries * 4 - plateau - storagePenalty - thermalPenalty)
            .coerceIn(8, 40)
    }

    /** Detects a slow storage device that cannot keep up with the network. */
    private fun sampleWriteSpeed(directory: File, now: Long): Long {
        val bytes = runCatching {
            directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        val previousAt = lastWriteSampleAt
        lastWriteSampleAt = now
        if (previousAt == 0L || now - previousAt < 2_000L || bytes < lastWriteBytes) {
            lastWriteBytes = bytes
            return 0L
        }
        val elapsed = now - previousAt
        val speed = (bytes - lastWriteBytes) * 1000L / elapsed.coerceAtLeast(1L)
        lastWriteBytes = bytes
        return speed
    }

    private fun noteThroughput(concurrency: Int) {
        if (previousSpeed > 0L && measuredSpeedBytes in 1..(previousSpeed * 105 / 100) &&
            concurrency >= previousConcurrency
        ) {
            plateauCount += 1
        } else if (measuredSpeedBytes > previousSpeed * 110 / 100) {
            plateauCount = 0
        }
        previousSpeed = measuredSpeedBytes
        previousConcurrency = concurrency
    }

    private fun maybeStartNext() {
        if (queuePaused.value) return
        if (_state.value is DownloadState.Paused) return
        dequeueHead()?.let { queued ->
            scope.launch {
                kotlinx.coroutines.delay(400L)
                start(queued)
            }
        }
    }

    private fun dequeueHead(): DownloadRequest? {
        val list = queue.value
        if (list.isEmpty()) return null
        val head = list.first()
        updateQueue(list.drop(1))
        return head
    }

    fun start(request: DownloadRequest, force: Boolean = false) {
        if (!force && queuePaused.value && !request.resume) {
            updateQueue(queue.value + request)
            return
        }
        if (job?.isActive == true) { updateQueue(queue.value + request); return }
        if (!force && _state.value is DownloadState.Paused && !request.resume) {
            updateQueue(queue.value + request)
            return
        }
        currentRequest = request
        queueStore.saveActive(request)
        _state.value = DownloadState.Preparing(request.title, request.mode)
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            val capturedStartedAt = startedAt
            try {
                if (!engine.ensureReady()) {
                    throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
                }
                startWatchdog()
                val settingsSnapshot = settingsStore.snapshot()
                val turbo = settingsSnapshot.turbo
                observedRetries = 0
                plateauCount = 0
                storageBoundCount = 0
                thermalPenalty = ThermalGuard.penalty(context)
                lastWriteSampleAt = 0L
                lastWriteBytes = 0L
                val network = NetworkProfile.current(context)
                val profileKey = if (settingsSnapshot.adaptiveProfile) {
                    NetworkProfile.learned(context, network) ?: settingsSnapshot.turboProfile
                } else {
                    settingsSnapshot.turboProfile
                }
                val base = when (profileKey) {
                    "standard" -> 8
                    "aggressive" -> 40
                    else -> 24
                }
                val range = RangeProbe.check(request.url)
                val autoChunk = when {
                    range.rttMs > 400L -> 4
                    range.rttMs > 150L -> 2
                    else -> 1
                }
                val config = SpeedConfig(
                    concurrency = base,
                    chunkMb = if (request.autoChunk && settingsSnapshot.autoChunk) autoChunk
                    else settingsSnapshot.chunkMb,
                    speedMode = settingsSnapshot.speedMode,
                    audioFormat = settingsSnapshot.audioFormat,
                    parallelStreams = settingsSnapshot.parallelStreams,
                    supportsRanges = range.supported,
                    network = network,
                    adaptive = settingsSnapshot.adaptiveProfile,
                    rateLimitMb = ThermalGuard.rateCapMb(context)
                )
                val directory = downloadDirectory(config.speedMode, settingsSnapshot.autoStorage)
                    .apply { mkdirs() }
                var file = downloadWithFallback(request, directory, turbo, config, startedAt)
                var color = ProbeVerifier.inspect(context, file)
                if (request.preferMkv && color != null && !color.isHdr) {
                    val hdrFile = File(directory, "hdr-${startedAt}.mkv")
                    if (StreamMerger.remuxWithHdrTags(context, file, hdrFile)) {
                        runCatching { file.delete() }
                        file = hdrFile
                        color = ProbeVerifier.inspect(context, file)
                    }
                }
                _state.value = DownloadState.Saving(request.title, request.mode)
                val exported = exporter.export(
                    file = file,
                    mode = request.mode,
                    platform = request.platform,
                    relativeBase = settingsSnapshot.downloadFolder.takeIf { it.isNotBlank() },
                    volumeName = settingsSnapshot.downloadFolderVolume,
                    fast = config.speedMode
                )
                var qualityNote = if (request.verify) runCatching {
                    MediaVerifier.mismatchNote(
                        context,
                        android.net.Uri.parse(exported.uri.toString()),
                        request.expectedHeight,
                        request.expectedFps,
                        config.audioFormat
                    )
                }.getOrNull() else null
                if (request.verify && qualityNote == null &&
                    exported.localPath != null
                ) {
                    val frames = ProbeVerifier.frameInfo(context, java.io.File(exported.localPath))
                    if (frames?.variable == true) {
                        qualityNote = context.getString(
                            com.mmtube.app.R.string.quality_vfr,
                            frames.avgFps
                        )
                    }
                }
                cleanupTempFiles(
                    olderThanMs = 10 * 60 * 1000L,
                    keepFragments = request.keepFragments
                )
                val item = HistoryItem(
                    id = UUID.randomUUID().toString(),
                    title = request.title,
                    sourceUrl = request.url,
                    platform = request.platform.name,
                    quality = request.qualityLabel,
                    sizeBytes = exported.sizeBytes,
                    mimeType = exported.mimeType,
                    uri = exported.uri.toString(),
                    localPath = exported.localPath,
                    thumbnail = request.thumbnail,
                    durationSeconds = request.durationSeconds,
                    createdAt = System.currentTimeMillis(),
                    mode = request.mode.key,
                    savedToGallery = exported.savedToGallery,
                    downloadDurationMs = System.currentTimeMillis() - capturedStartedAt,
                    qualityNote = qualityNote,
                    isHdr = color?.isHdr ?: false
                )
                runCatching { historyStore.add(item) }
                    .onFailure { Log.w(TAG, "Could not save history", it) }
                runCatching { quotaManager.consume() }
                    .onFailure { Log.w(TAG, "Could not consume quota", it) }
                _state.value = DownloadState.Completed(item)
                if (request.memoryKey.isNotBlank() && request.alternates.isNotEmpty() &&
                    request.estimatedBytes > 0L && exported.sizeBytes < request.estimatedBytes * 70 / 100
                ) {
                    FormatMemory.put(context, request.memoryKey, request.alternates.first())
                }
                if (config.adaptive && measuredSpeedBytes > 0L) {
                    val learned = when {
                        measuredSpeedBytes >= 6L * 1024 * 1024 -> "aggressive"
                        measuredSpeedBytes <= 700L * 1024 -> "standard"
                        else -> "balanced"
                    }
                    NetworkProfile.remember(context, config.network, learned)
                }
                // Auto-start the next queued download.
                maybeStartNext()
            } catch (cancelled: CancellationException) {
                if (_state.value !is DownloadState.Cancelled &&
                    _state.value !is DownloadState.Paused
                ) {
                    _state.value = DownloadState.Cancelled
                }
                maybeStartNext()
            } catch (cancelled: YoutubeDL.CanceledException) {
                if (_state.value !is DownloadState.Paused &&
                    _state.value !is DownloadState.Cancelled
                ) {
                    _state.value = DownloadState.Cancelled
                }
                maybeStartNext()
            } catch (error: Throwable) {
                if (_state.value is DownloadState.Cancelled) return@launch
                Log.w(TAG, "Download failed", error)
                maybeStartNext()
                val kind = (error as? MmtubeException)?.kind ?: ErrorKind.DOWNLOAD
                _state.value = DownloadState.Failed(
                    title = request.title,
                    kind = kind,
                    detail = error.message
                )
            } finally {
                processId = null
                currentRequest = null
                queueStore.saveActive(null)
            }
        }
    }

    fun cancel() {
        val id = processId
        if (id != null) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
        watchdog?.cancel()
        job?.cancel()
        pausedRequest = null
        queueStore.saveActive(null)
        _state.value = DownloadState.Cancelled
    }

    fun pause() {
        val state = _state.value
        val request = currentRequest ?: return
        val id = processId
        if (id != null) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
        watchdog?.cancel()
        val progress = (state as? DownloadState.Running)?.progress ?: 0f
        pausedRequest = request
        queueStore.saveActive(null)
        // Set Paused before the coroutine's cancellation handler runs so it does
        // not overwrite the state with Cancelled.
        _state.value = DownloadState.Paused(request.title, request.mode, progress)
        job?.cancel()
    }

    fun resume() {
        val request = pausedRequest ?: return
        pausedRequest = null
        start(request.copy(resume = true), force = true)
    }

    /** Hold the whole queue: pause the active download and stop auto-start. */
    fun pauseQueue() {
        queuePaused.value = true
        if (job?.isActive == true) pause()
    }

    /** Release the queue and continue with the paused or next download. */
    fun resumeQueue() {
        queuePaused.value = false
        if (pausedRequest != null) {
            resume()
        } else if (job?.isActive != true) {
            dequeueHead()?.let { start(it) }
        }
    }

    /** Called when Android 14+ ends a long-running data-sync foreground service.
     *  Pause instead of dying silently so the user can resume from the notification. */
    fun pauseForTimeout() {
        if (_state.value is DownloadState.Running || _state.value is DownloadState.Preparing ||
            _state.value is DownloadState.Merging || _state.value is DownloadState.Saving
        ) {
            pause()
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000L)
                val current = _state.value
                if (current !is DownloadState.Running) break
                val ageMs = System.currentTimeMillis() - lastRawLineAt
                if (ageMs > 6_000L && !current.reconnecting) {
                    _state.value = current.copy(reconnecting = true)
                } else if (ageMs <= 6_000L && current.reconnecting) {
                    _state.value = current.copy(reconnecting = false)
                }
            }
        }
    }

    /**
     * Experimental: downloads a whole batch with ONE yt-dlp process by
     * passing all URLs to YoutubeDLRequest(List<String>). Progress is shared,
     * so per-file mapping is best-effort.
     */
    fun startBatchSingleProcess(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        if (job?.isActive == true) {
            updateQueue(queue.value + requests)
            return
        }
        val first = requests.first()
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            var lastItem: HistoryItem? = null
            processId = "batch-${startedAt}"
            try {
                if (!engine.ensureReady()) {
                    throw MmtubeException(ErrorKind.ENGINE, "Engine not ready")
                }
                startWatchdog()
                val settings = settingsStore.snapshot()
                val network = NetworkProfile.current(context)
                val profileKey = if (settings.adaptiveProfile) {
                    NetworkProfile.learned(context, network) ?: settings.turboProfile
                } else {
                    settings.turboProfile
                }
                val base = when (profileKey) {
                    "standard" -> 8
                    "aggressive" -> 40
                    else -> 24
                }
                val range = RangeProbe.check(first.url)
                val autoChunk = when {
                    range.rttMs > 400L -> 4
                    range.rttMs > 150L -> 2
                    else -> 1
                }
                val config = SpeedConfig(
                    concurrency = base,
                    chunkMb = if (first.autoChunk && settings.autoChunk) autoChunk
                    else settings.chunkMb,
                    speedMode = settings.speedMode,
                    audioFormat = settings.audioFormat,
                    parallelStreams = false,
                    supportsRanges = range.supported,
                    network = network,
                    adaptive = settings.adaptiveProfile,
                    rateLimitMb = ThermalGuard.rateCapMb(context)
                )
                val directory = downloadDirectory(config.speedMode, settings.autoStorage)
                    .apply { mkdirs() }
                _state.value = DownloadState.Preparing(first.title, first.mode)

                val baseRequest = buildRequest(first, directory, settings.turbo, config)
                val options = baseRequest.buildCommand().toMutableList()
                if (options.isNotEmpty()) options.removeAt(options.lastIndex)
                val batchRequest = YoutubeDLRequest(requests.map { it.url })
                batchRequest.addCommands(options)

                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
                        batchRequest,
                        processId = "batch-${startedAt}",
                        redirectErrorStream = false
                    ) { progress, eta, rawLine ->
                        val now = System.currentTimeMillis()
                        lastRawLineAt = now
                        val line = rawLine
                            .replace(Regex("\u001B\\[[;\\d]*m"), "")
                            .trim()
                        val current = _state.value
                        if (current !is DownloadState.Cancelled &&
                            System.currentTimeMillis() - lastProgressUpdate >= 140L
                        ) {
                            lastProgressUpdate = System.currentTimeMillis()
                            val speed = Formatters.parseSpeed(line)
                            if (speed > 0L) measuredSpeedBytes = speed
                            _state.value = DownloadState.Running(
                                title = context.getString(com.mmtube.app.R.string.batch_progress_title),
                                mode = first.mode,
                                progress = progress.coerceIn(0f, 100f),
                                etaSeconds = eta,
                                speedBytes = speed,
                                totalBytes = Formatters.parseTotal(line).takeIf { it > 0 }
                                    ?: 0L,
                                line = line.take(180),
                                concurrency = tunedConcurrency(config.concurrency)
                            )
                        }
                    }
                }

                val outputs = directory.listFiles()
                    ?.filter { it.isFile && it.length() > 0L }
                    ?.filter { file ->
                        val ext = file.extension.lowercase()
                        ext in setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "aac", "flac")
                    }
                    ?.filter { it.lastModified() >= startedAt - 5_000L }
                    ?.sortedBy { it.lastModified() }
                    .orEmpty()

                outputs.forEachIndexed { index, file ->
                    val source = requests.getOrNull(index) ?: first
                    val exported = exporter.export(
                        file = file,
                        mode = source.mode,
                        platform = source.platform,
                        relativeBase = settings.downloadFolder.takeIf { it.isNotBlank() },
                        volumeName = settings.downloadFolderVolume,
                        fast = config.speedMode
                    )
                    val item = HistoryItem(
                        id = UUID.randomUUID().toString(),
                        title = source.title.ifBlank { file.nameWithoutExtension },
                        sourceUrl = source.url,
                        platform = source.platform.name,
                        quality = source.qualityLabel,
                        sizeBytes = exported.sizeBytes,
                        mimeType = exported.mimeType,
                        uri = exported.uri.toString(),
                        localPath = exported.localPath,
                        thumbnail = source.thumbnail,
                        durationSeconds = source.durationSeconds,
                        createdAt = System.currentTimeMillis(),
                        mode = source.mode.key,
                        savedToGallery = exported.savedToGallery,
                        qualityNote = if (source.verify) {
                            runCatching {
                                MediaVerifier.mismatchNote(
                                    context,
                                    android.net.Uri.parse(exported.uri.toString()),
                                    source.expectedHeight,
                                    source.expectedFps,
                                    config.audioFormat
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    )
                    runCatching { historyStore.add(item) }
                    runCatching { quotaManager.consume() }
                    lastItem = item
                }
                if (lastItem == null) {
                    throw MmtubeException(ErrorKind.DOWNLOAD, "No files were produced")
                }
                _state.value = DownloadState.Completed(lastItem!!)
            } catch (cancelled: CancellationException) {
                _state.value = DownloadState.Cancelled
            } catch (cancelled: YoutubeDL.CanceledException) {
                _state.value = DownloadState.Cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Batch process failed", error)
                val kind = (error as? MmtubeException)?.kind ?: ErrorKind.DOWNLOAD
                _state.value = DownloadState.Failed(first.title, kind, error.message)
            } finally {
                processId = null
            }
        }
    }

    /** Consumes (clears) the paused snapshot and returns it, if any. */
    fun consumePausedRequest(): DownloadRequest? {
        val req = pausedRequest ?: return null
        pausedRequest = null
        _state.value = DownloadState.Preparing(req.title, req.mode)
        return req
    }

    fun reset() {
        if (_state.value !is DownloadState.Running &&
            _state.value !is DownloadState.Preparing &&
            _state.value !is DownloadState.Merging &&
            _state.value !is DownloadState.Saving
        ) {
            _state.value = DownloadState.Idle
        }
    }

    fun isActive(): Boolean = job?.isActive == true

    /** Queues a whole batch in one controller/session instead of N service starts. */
    fun startBatch(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        val first = requests.first()
        updateQueue(requests.drop(1) + queue.value)
        start(first, force = true)
    }

    private suspend fun downloadWithFallback(
        request: DownloadRequest,
        directory: File,
        turbo: Boolean,
        config: SpeedConfig,
        startedAt: Long
    ): File {
        val maxAttempts = 3
        var sawError = false
        for (attempt in 0 until maxAttempts) {
            val useTurbo = turbo && attempt == 0
            val id = "download-${System.nanoTime()}-$attempt"
            processId = id
            try {
                if (attempt == 0 && config.parallelStreams && config.supportsRanges &&
                    request.mode != DownloadMode.MP3 && request.formatId?.contains("+") == true
                ) {
                    val merged = runCatching { downloadParallel(request, directory, config, startedAt) }
                        .getOrNull()
                    if (merged != null) {
                        sawError = false
                        break
                    }
                }
                val attemptRequest = if (attempt > 0) {
                    val alternate = request.alternates.getOrNull(attempt - 1)
                    if (alternate != null) {
                        request.copy(formatId = alternate, alternates = emptyList(), resume = false)
                    } else {
                        request.copy(resume = true)
                    }
                } else {
                    request.copy(resume = false)
                }
                execute(attemptRequest, directory, id, useTurbo, config)
                sawError = false
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: YoutubeDL.CanceledException) {
                throw cancelled
            } catch (error: Throwable) {
                sawError = true
                val turboFallback = useTurbo && attempt == 0
                val transient = isTransient(error)
                if (!turboFallback && (!transient || attempt == maxAttempts - 1)) throw error
                val line = if (request.alternates.getOrNull(attempt - 1) != null && !turboFallback) {
                    context.getString(com.mmtube.app.R.string.download_retry_format)
                } else if (turboFallback) {
                    context.getString(com.mmtube.app.R.string.download_retry_turbo)
                } else {
                    context.getString(
                        com.mmtube.app.R.string.download_retry_connection,
                        attempt + 1,
                        maxAttempts
                    )
                }
                Log.w(TAG, "$line: ${error.message}", error)
                _state.value = DownloadState.Running(
                    title = request.title,
                    mode = request.mode,
                    progress = 0f,
                    etaSeconds = -1L,
                    speedBytes = 0L,
                    totalBytes = 0L,
                    line = line,
                    reconnecting = true,
                    retries = attempt + 1,
                    concurrency = config.concurrency
                )
                val serverHint = RetryAfter.delayMs(request.url)
                kotlinx.coroutines.delay(
                    maxOf(if (turboFallback) 400L else 2_000L * (attempt + 1), serverHint)
                )
            }
        }
        if (sawError) {
            throw MmtubeException(
                ErrorKind.DOWNLOAD,
                context.getString(com.mmtube.app.R.string.download_failed_attempts, maxAttempts)
            )
        }
        return findOutput(directory, request.mode, startedAt)
            ?: throw MmtubeException(ErrorKind.DOWNLOAD, context.getString(com.mmtube.app.R.string.download_file_missing))
    }

    private suspend fun downloadParallel(
        request: DownloadRequest,
        directory: File,
        config: SpeedConfig,
        startedAt: Long
    ): File? {
        val parts = request.formatId?.split("+", limit = 2) ?: return null
        if (parts.size != 2) return null
        val videoDir = File(directory, "stream-v-${startedAt}").apply { mkdirs() }
        val audioDir = File(directory, "stream-a-${startedAt}").apply { mkdirs() }
        val videoRequest = request.copy(
            formatId = parts[0],
            formatIsVideo = false,
            alternates = emptyList(),
            resume = false,
            title = request.title + " (video)"
        )
        val audioRequest = request.copy(
            formatId = parts[1],
            mode = DownloadMode.MP3,
            alternates = emptyList(),
            resume = false,
            title = request.title + " (audio)"
        )
        coroutineScope {
            listOf(
                async { execute(videoRequest, videoDir, "pv-${startedAt}", false, config) },
                async { execute(audioRequest, audioDir, "pa-${startedAt}", false, config) }
            ).awaitAll()
        }
        val video = findOutput(videoDir, request.mode, startedAt) ?: return null
        val audio = findOutput(audioDir, DownloadMode.MP3, startedAt) ?: return null
        val extension = if (request.preferMkv) "mkv" else "mp4"
        val output = File(directory, "merged-${startedAt}.$extension")
        FastAlloc.reserve(output, video.length() + audio.length())
        if (!StreamMerger.merge(context, video, audio, output)) {
            runCatching { video.delete(); audio.delete() }
            return null
        }
        runCatching { video.delete(); audio.delete() }
        return output
    }

    private fun isTransient(error: Throwable): Boolean {
        val text = buildString {
            append(error.message.orEmpty()).append(' ').append(error.cause?.message.orEmpty())
        }.lowercase()
        return listOf(
            "timed out", "timeout", "connection reset", "connection aborted",
            "temporary failure", "network is unreachable", "unreachable",
            "handshake", "ssl", "502", "503", "504", "try again"
        ).any { it in text }
    }

    private suspend fun execute(
        request: DownloadRequest,
        directory: File,
        processId: String,
        turbo: Boolean,
        config: SpeedConfig
    ) {
        val ytRequest = buildRequest(request, directory, turbo, config)
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(
                ytRequest,
                processId = processId,
                redirectErrorStream = false
            ) { progress, eta, rawLine ->
                val now = System.currentTimeMillis()
                lastRawLineAt = now
                val line = rawLine
                    .replace(Regex("\u001B\\[[;\\d]*m"), "")
                    .trim()
                if (line.contains("retry", true)) {
                    val lineTime = System.currentTimeMillis()
                    if (lineTime - lastRetryLineAt > 2_000L) {
                        observedRetries += 1
                        lastRetryLineAt = lineTime
                    }
                }
                val current = _state.value
                if (current !is DownloadState.Cancelled) {
                    if (line.contains("[Merger]", true) ||
                        line.contains("Merging formats", true) ||
                        line.contains("Merging", true)
                    ) {
                        _state.value = DownloadState.Merging(request.title, request.mode)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 140L || progress >= 100f) {
                            lastProgressUpdate = now
                            val previous = current as? DownloadState.Running
                            val speed = Formatters.parseSpeed(line)
                            if (speed > 0L) {
                                measuredSpeedBytes = speed
                                noteThroughput(tunedConcurrency(config.concurrency))
                                val writeSpeed = sampleWriteSpeed(directory, now)
                                if (writeSpeed in 1..(speed * 6 / 10)) {
                                    storageBoundCount += 1
                                }
                            }
                            val total = Formatters.parseTotal(line).takeIf { it > 0 }
                                ?: previous?.totalBytes ?: 0L
                            _state.value = DownloadState.Running(
                                title = request.title,
                                mode = request.mode,
                                progress = progress.coerceIn(0f, 100f),
                                etaSeconds = eta,
                                speedBytes = speed,
                                totalBytes = total,
                                line = line.take(180),
                                reconnecting = false,
                                retries = observedRetries,
                                concurrency = tunedConcurrency(config.concurrency)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestFormat(request: DownloadRequest): Pair<String, String> {
        val selected = request.formatId?.takeIf { it.isNotBlank() }
        if (selected != null) {
            // A combined "video+audio" spec or an exact stream id is already complete.
            if (selected.contains("+") || !request.formatIsVideo ||
                request.mode == DownloadMode.MP3
            ) {
                return selected to ""
            }
            // A picked video format usually has no audio — keep the best audio track.
            return "$selected+bestaudio/$selected/best" to ""
        }
        if (request.mode == DownloadMode.MP3) {
            return "bestaudio/best" to "tbr:desc"
        }
        val limit = when (request.mode) {
            DownloadMode.P1080 -> "[height<=1080]"
            DownloadMode.P720 -> "[height<=720]"
            else -> ""
        }
        val format = if (limit.isEmpty()) {
            "bestvideo+bestaudio/best"
        } else {
            "bestvideo$limit+bestaudio/best$limit/best"
        }
        // Same source, different priorities: YouTube has both resolution and
        // frame-rate variants, TikTok is usually bitrate-bound.
        val sort = when (request.platform) {
            Platform.TIKTOK -> "tbr:desc,res:desc,fps:desc"
            Platform.FACEBOOK -> "res:desc,tbr:desc,fps:desc"
            else -> "res:desc,fps:desc,tbr:desc"
        }
        return format to sort
    }

    private fun buildRequest(
        request: DownloadRequest,
        directory: File,
        turbo: Boolean,
        config: SpeedConfig
    ): YoutubeDLRequest {
        val output = File(directory, "%(title).90B [%(id)s].%(ext)s").absolutePath
        val concurrency = tunedConcurrency(config.concurrency)
        val cookies = CookieStore.path(context)
        return YoutubeDLRequest(request.url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-update")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--force-overwrites")
            addOption("--ignore-config")
            addOption("--socket-timeout", "25")
            addOption("--retries", "10")
            addOption("--fragment-retries", "30")
            addOption("--extractor-retries", "3")
            addOption("--concurrent-fragments", concurrency.toString())
            if (request.throttledRateKb > 0) {
                addOption("--throttled-rate", "${request.throttledRateKb}K")
            }
            if (request.checkFormats) addOption("--check-formats")
            if (request.bufferSizeKb > 0) {
                addOption("--buffer-size", "${request.bufferSizeKb}K")
                addOption("--resize-buffer")
            }
            if (request.forceIpv4) addOption("--force-ipv4")
            if (request.sortForce) addOption("--format-sort-force")
            if (request.embedChapters) addOption("--embed-chapters")
            if (request.multiVideoStreams) addOption("--video-multistreams")
            if (request.multiAudioStreams) addOption("--audio-multistreams")
            if (request.keepFragments) addOption("--keep-fragments")
            if (request.hlsUseMpegts) addOption("--hls-use-mpegts")
            if (request.proxyUrl.isNotBlank()) addOption("--proxy", request.proxyUrl)
            if (request.sourceAddress.isNotBlank()) {
                addOption("--source-address", request.sourceAddress)
            }
            if (config.rateLimitMb > 0) {
                addOption("--limit-rate", "${config.rateLimitMb}M")
            }
            if (!config.speedMode) {
                addOption("--embed-metadata")
                addOption("--parse-metadata", """title:^(?P<title>.*?)\s*\([^\(\)]*\)\s*${'$'}""")
                addOption("--parse-metadata", """title:^(?P<title>.*?)\s*\[[^\[\]]*\]\s*${'$'}""")
                addOption("--parse-metadata", """title:^(?P<title>.*?)\s*\{[^\{\}]*\}\s*${'$'}""")
            }
            if (cookies != null) addOption("--cookies", cookies)
            addOption(
                "--extractor-args",
                "youtube:player_client=android,ios,tv,web_safari,mweb;formats=duplicate"
            )
            if (!turbo && config.supportsRanges) {
                // Chunked requests help direct (non-segmented) servers that
                // throttle one long connection.
                addOption("--http-chunk-size", "${config.chunkMb}M")
            }
            addOption("-o", output)
            if (request.resume) addOption("--continue")
            val (format, sort) = requestFormat(request)
            addOption("-f", format)
            if (sort.isNotBlank()) addOption("-S", sort)
            if (request.mode == DownloadMode.MP3) {
                if (!config.speedMode) {
                    addOption("--write-thumbnail")
                    addOption("--embed-thumbnail")
                }
                addOption("--extract-audio")
                addOption("--audio-format", config.audioFormat)
                if (config.audioFormat != "flac") addOption("--audio-quality", "0")
                if (!request.preserveAudio) {
                    // Even out loudness between tracks (single-pass EBU R128).
                    addOption(
                        "--postprocessor-args",
                        "ffmpeg:-af loudnorm=I=-${request.loudnessTarget}:TP=-1.5:LRA=11"
                    )
                }
            } else {
                addOption(
                    "--merge-output-format",
                    if (request.preferMkv) "mkv" else "mp4"
                )
            }
            if (turbo && config.supportsRanges) {
                val sizeMb = request.estimatedBytes / (1024L * 1024L)
                val alignedChunk = when {
                    request.resume && sizeMb > 1024L -> 16
                    request.resume && sizeMb > 400L -> 8
                    request.resume && sizeMb > 100L -> 4
                    else -> config.chunkMb
                }
                addOption("--downloader", "libaria2c.so")
                addOption(
                    "--external-downloader-args",
                    "aria2c:-x$concurrency -s$concurrency -k${alignedChunk}M"
                )
            }
        }
    }

    private fun findOutput(directory: File, mode: DownloadMode, startedAt: Long): File? {
        val extensions = if (mode == DownloadMode.MP3) {
            setOf("mp3", "m4a", "opus", "aac", "wav")
        } else {
            setOf("mp4", "mkv", "webm", "mov")
        }
        val files = directory.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.filter { it.extension.lowercase() in extensions }
            ?.filterNot {
                it.name.endsWith(".part") ||
                    it.name.endsWith(".ytdl") ||
                    it.name.endsWith(".aria2") ||
                    it.name.endsWith(".temp")
            }
            .orEmpty()
        if (files.isEmpty()) return null
        val fresh = files.filter { it.lastModified() >= startedAt - 10_000L }
        return (fresh.ifEmpty { files }).maxWithOrNull(
            compareBy<File> { it.lastModified() }.thenBy { it.length() }
        )
    }

    /** Remove leftovers from failed/cancelled downloads so they do not eat storage. */
    private fun allTempDirs(): List<File> = listOf(
        downloadDirectory(false, false),
        downloadDirectory(true, false)
    ).distinctBy { it.absolutePath }

    private fun cleanupTempFiles(olderThanMs: Long, keepFragments: Boolean = false) {
        if (keepFragments) return
        val now = System.currentTimeMillis()
        allTempDirs().forEach { dir ->
            runCatching {
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.filter { file ->
                        val name = file.name.lowercase()
                        name.endsWith(".part") || name.endsWith(".ytdl") ||
                            name.endsWith(".aria2") || name.endsWith(".temp") ||
                            name.endsWith(".tmp")
                    }
                    ?.filter { now - it.lastModified() >= olderThanMs }
                    ?.forEach { runCatching { it.delete() } }
            }
        }
    }

    fun tempBytes(): Long = runCatching {
        allTempDirs().sumOf { dir -> dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }
    }.getOrDefault(0L)

    fun clearTempFiles(): Int {
        var removed = 0
        allTempDirs().forEach { dir ->
            runCatching {
                dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    val name = file.name.lowercase()
                    if (name.endsWith(".part") || name.endsWith(".ytdl") ||
                        name.endsWith(".aria2") || name.endsWith(".temp") || name.endsWith(".tmp")
                    ) {
                        if (runCatching { file.delete() }.getOrDefault(false)) removed++
                    }
                }
            }
        }
        return removed
    }

    private fun downloadDirectory(fast: Boolean, autoStorage: Boolean): File {
        val base = when {
            fast && autoStorage -> StorageSpeed.fastestTemp(context)
            fast -> context.filesDir
            else -> context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
        }
        return File(base, "MMTube")
    }

    companion object {
        private const val TAG = "DownloadController"
    }
}
