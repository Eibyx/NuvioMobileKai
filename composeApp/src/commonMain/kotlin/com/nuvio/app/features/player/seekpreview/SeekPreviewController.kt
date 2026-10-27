package com.nuvio.app.features.player.seekpreview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.TimeSource

private const val FrameMaxWidthPx = 320

/** Thumbnails kept in memory: at most this many bytes of pixels (≈ 70 frames of 320×180). */
internal const val SeekPreviewCacheBytes = 16L * 1024 * 1024

private const val MinBucketMs = 1_000L
private const val MaxBucketMs = 5_000L
private const val BucketsPerTitle = 1_000L

private const val MaxInitialFailures = 3

private const val WarmUpDelayMs = 1_200L
internal const val SeekPreviewIdleReleaseMs = 30_000L

private const val ThumbnailAskMs = 80L
private const val ThumbnailIndexMs = 5_000L
internal const val SeekPreviewFirstImageTimeoutMs = 15_000L

private const val LogTag = "Player/SeekPreview"

internal class SeekPreviewFrame(val positionMs: Long, val bitmap: ImageBitmap)

/**
 * How scene previews are made for a stream, the same with mpv and with ExoPlayer:
 * - [Native]: luqman's Rust thumbnailer (#265), which reads the file itself (MKV, MP4…).
 * - [ExoHls]: an ExoPlayer frame grabber, only for HLS, which the native one does not read.
 * - [None]: nothing to read (torrents and P2P are left out before this): only the time shows.
 */
internal enum class SeekPreviewMethod(val logName: String) {
    Native("native"),
    ExoHls("exoplayer-hls"),
    None("none"),
}

internal fun seekPreviewMethodFor(url: String): SeekPreviewMethod {
    val scheme = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
    if (scheme !in setOf("http", "https", "file", "")) return SeekPreviewMethod.None
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return if (path.endsWith(".m3u8") || path.contains(".m3u8/")) SeekPreviewMethod.ExoHls else SeekPreviewMethod.Native
}

/** Errors for the diagnostics log: no addresses (they may hold tokens), nothing long. */
internal fun seekPreviewErrorForLog(error: String?): String =
    (error ?: "unknown error")
        .replace(Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+"""), "<url>")
        .replace(Regex("""(?i)(token|key|auth|apikey|password)=\S+"""), "$1=<hidden>")
        .take(200)

/**
 * Previews of the scene under the finger while dragging the progress bar.
 *
 * - The newest position wins: a fast drag drops the positions it passes.
 * - The picture shown stays until the next one arrives; nothing blanks it in between.
 * - Thumbnails are kept in memory up to [SeekPreviewCacheBytes]; the connection closes
 *   [SeekPreviewIdleReleaseMs] after the finger lifts and opens again on the next drag.
 * - When pictures cannot be made ([isUnavailable]) the overlay shows the time alone.
 * - The diagnostics log (Player/SeekPreview) gets the method, the time to the first picture and
 *   the errors; never the address, tokens or headers.
 */
@Stable
internal class SeekPreviewController(
    url: String,
    headers: Map<String, String>,
    val method: SeekPreviewMethod = seekPreviewMethodFor(url),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val openFeed: ((Long, ImageBitmap?, String?) -> Unit) -> SeekPreviewFeed? = { onThumbnail ->
        openSeekPreviewFeed(url, headers, onThumbnail)
    },
    private val openSource: () -> SeekPreviewFrameSource? = { openSeekPreviewFrameSource(url, headers) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val requests = MutableStateFlow<Long?>(null)
    private val cache = SeekPreviewFrameCache(SeekPreviewCacheBytes)
    private var worker: Job? = null
    private var warmJob: Job? = null
    private var releaseJob: Job? = null
    private var scrubbing = false
    private var consecutiveFailures = 0
    private var hasSucceeded = false
    private var disposed = false
    private var durationMs = 0L
    private var firstAskAt: kotlin.time.TimeMark? = null

    @kotlin.concurrent.Volatile
    private var source: SeekPreviewFrameSource? = null
    private var sourceOpened = false
    private var connectionGeneration = 0

    private var feed: SeekPreviewFeed? = null
    private var feedWanted: Long? = null
    private var feedAskedAt: kotlin.time.TimeMark? = null
    private var feedAskJob: Job? = null
    private var firstImageTimeout: Job? = null

    var frame by mutableStateOf<SeekPreviewFrame?>(null)
        private set

    var isUnavailable by mutableStateOf(method == SeekPreviewMethod.None)
        private set

    /** Number of thumbnails held in memory, for tests and the log. */
    internal val cachedFrames: Int get() = cache.size

    init {
        InAppLogger.info(LogTag, "method=${method.logName} headers=${headers.size}")
    }

    fun request(positionMs: Long, durationMs: Long) {
        if (disposed || isUnavailable || durationMs <= 0L) return
        scrubbing = true
        warmJob?.cancel()
        releaseJob?.cancel()
        if (firstAskAt == null) firstAskAt = timeSource.markNow()
        this.durationMs = durationMs
        val bucket = bucketFor(positionMs.coerceIn(0L, durationMs), durationMs)
        cache[bucket]?.let { frame = SeekPreviewFrame(bucket, it) }
        when (method) {
            SeekPreviewMethod.Native -> wantFeed(positionMs.coerceIn(0L, durationMs), bucket)
            SeekPreviewMethod.ExoHls -> {
                requests.value = bucket
                if (worker == null) worker = scope.launch { decodeRequests() }
            }
            SeekPreviewMethod.None -> Unit
        }
    }

    fun warmUp(positionMs: Long, durationMs: Long) {
        if (disposed || isUnavailable || durationMs <= 0L || scrubbing) return
        when (method) {
            SeekPreviewMethod.Native -> {
                if (warmJob?.isActive == true) return
                warmJob = scope.launch {
                    delay(ThumbnailIndexMs)
                    if (!scrubbing) currentFeed()?.prepare()
                }
            }
            SeekPreviewMethod.ExoHls -> {
                warmJob?.cancel()
                warmJob = scope.launch {
                    delay(WarmUpDelayMs)
                    if (scrubbing || isUnavailable) return@launch
                    requests.value = bucketFor(positionMs.coerceIn(0L, durationMs), durationMs)
                    if (worker == null) worker = scope.launch { decodeRequests() }
                    scheduleRelease()
                }
            }
            SeekPreviewMethod.None -> Unit
        }
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        feedWanted = null
        feedAskJob?.cancel()
        feedAskJob = null
        requests.value = null
        scheduleRelease()
    }

    // --- Native thumbnails ---------------------------------------------------------------

    private fun currentFeed(): SeekPreviewFeed? {
        feed?.let { return it }
        if (isUnavailable) return null
        val opened = openFeed(::onFeedThumbnail)
        if (opened == null) {
            fail("native thumbnails could not start")
            return null
        }
        feed = opened
        return opened
    }

    private fun wantFeed(positionMs: Long, bucket: Long) {
        // A thumbnail already in memory for this spot needs no new request.
        if (cache[bucket] != null) {
            feedWanted = positionMs
            return
        }
        feedWanted = positionMs
        if (feedAskJob?.isActive == true) return
        val waitMs = feedAskedAt?.let { ThumbnailAskMs - it.elapsedNow().inWholeMilliseconds } ?: 0L
        if (waitMs <= 0L) {
            askFeed()
        } else {
            feedAskJob = scope.launch {
                delay(waitMs)
                askFeed()
            }
        }
    }

    private fun askFeed() {
        feedAskJob = null
        val wanted = feedWanted ?: return
        val activeFeed = currentFeed() ?: return
        feedAskedAt = timeSource.markNow()
        activeFeed.want(wanted, frame?.positionMs)
        if (!hasSucceeded && firstImageTimeout == null) {
            firstImageTimeout = scope.launch {
                delay(SeekPreviewFirstImageTimeoutMs)
                if (!hasSucceeded) fail("no picture after ${SeekPreviewFirstImageTimeoutMs}ms")
            }
        }
    }

    private fun onFeedThumbnail(positionMs: Long, bitmap: ImageBitmap?, error: String?) {
        if (disposed || feed == null) return
        if (bitmap == null) {
            fail(error)
            return
        }
        onPicture(bucketFor(positionMs, durationMs.coerceAtLeast(positionMs)), bitmap)
        if (feedWanted != null) frame = SeekPreviewFrame(positionMs, bitmap)
    }

    // --- ExoPlayer, for HLS -------------------------------------------------------------

    private suspend fun decodeRequests() {
        requests.filterNotNull().collect { bucket ->
            if (cache[bucket] != null && source != null) return@collect
            val generation = connectionGeneration
            val bitmap = try {
                withContext(decodeDispatcher) { decodeFrame(bucket) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (generation != connectionGeneration) return@collect
                onDecodeFailure(error)
                return@collect
            }
            if (bitmap == null) {
                onDecodeFailure(null)
                return@collect
            }
            consecutiveFailures = 0
            onPicture(bucket, bitmap)
            val latest = requests.value
            frame = when {
                latest == bucket -> SeekPreviewFrame(bucket, bitmap)
                latest != null -> nearestCached(latest) ?: SeekPreviewFrame(bucket, bitmap)
                else -> frame ?: SeekPreviewFrame(bucket, bitmap)
            }
        }
    }

    private fun decodeFrame(positionMs: Long): ImageBitmap? {
        if (!sourceOpened) {
            sourceOpened = true
            source = openSource()
        }
        val activeSource = source ?: throw UnsupportedSeekPreviewSource
        return activeSource.frameAt(positionMs, FrameMaxWidthPx)
    }

    private fun onDecodeFailure(error: Throwable?) {
        consecutiveFailures++
        val reason = error?.let { "${it::class.simpleName}: ${it.message}" } ?: "no frame"
        if (error === UnsupportedSeekPreviewSource || (!hasSucceeded && consecutiveFailures >= MaxInitialFailures)) {
            fail(reason)
        } else {
            InAppLogger.warn(LogTag, "frame failed: ${seekPreviewErrorForLog(reason)}")
        }
    }

    // --- Shared ------------------------------------------------------------------------

    private fun onPicture(key: Long, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
        if (!hasSucceeded) {
            hasSucceeded = true
            firstImageTimeout?.cancel()
            val elapsed = firstAskAt?.elapsedNow()?.inWholeMilliseconds
            InAppLogger.info(LogTag, "first picture method=${method.logName} after ${elapsed ?: -1}ms ${bitmap.width}x${bitmap.height}")
        }
    }

    private fun fail(error: String?) {
        if (isUnavailable) return
        InAppLogger.warn(LogTag, "pictures off for this stream method=${method.logName}: ${seekPreviewErrorForLog(error)}")
        isUnavailable = true
        frame = null
        feedWanted = null
        feedAskJob?.cancel()
        firstImageTimeout?.cancel()
        worker?.cancel()
        worker = null
        closeConnection()
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(SeekPreviewIdleReleaseMs)
            if (scrubbing) return@launch
            closeConnection()
            InAppLogger.debug(LogTag, "connection released")
        }
    }

    private fun closeConnection() {
        feed?.close()
        feed = null
        connectionGeneration++
        val activeSource = source
        activeSource?.cancel()
        source = null
        sourceOpened = false
        if (activeSource != null) {
            CoroutineScope(decodeDispatcher).launch { runCatching { activeSource.close() } }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        closeConnection()
        cache.clear()
        scope.cancel()
    }

    private fun nearestCached(bucket: Long): SeekPreviewFrame? =
        cache.nearest(bucket)?.let { (key, bitmap) -> SeekPreviewFrame(key, bitmap) }

    private object UnsupportedSeekPreviewSource : RuntimeException("no frame source for this stream")
}

/** Least recently used thumbnails, bounded by the bytes their pixels take. */
internal class SeekPreviewFrameCache(private val maxBytes: Long) {
    private val frames = LinkedHashMap<Long, ImageBitmap>()
    private var bytes = 0L

    val size: Int get() = frames.size
    val totalBytes: Long get() = bytes

    operator fun get(key: Long): ImageBitmap? =
        frames.remove(key)?.also { frames[key] = it }

    fun put(key: Long, bitmap: ImageBitmap) {
        frames.remove(key)?.let { bytes -= it.byteCount() }
        frames[key] = bitmap
        bytes += bitmap.byteCount()
        while (bytes > maxBytes && frames.size > 1) {
            val eldest = frames.keys.first()
            frames.remove(eldest)?.let { bytes -= it.byteCount() }
        }
    }

    fun nearest(key: Long): Pair<Long, ImageBitmap>? =
        frames.keys.minByOrNull { abs(it - key) }?.let { it to frames.getValue(it) }

    fun clear() {
        frames.clear()
        bytes = 0L
    }

    private fun ImageBitmap.byteCount(): Long = width.toLong() * height * 4
}

internal fun bucketFor(positionMs: Long, durationMs: Long): Long {
    val bucketMs = (durationMs / BucketsPerTitle).coerceIn(MinBucketMs, MaxBucketMs)
    return ((positionMs + bucketMs / 2) / bucketMs * bucketMs).coerceAtMost(durationMs)
}

@Composable
internal fun rememberSeekPreviewController(
    url: String?,
    headers: Map<String, String>,
): SeekPreviewController? {
    val controller = remember(url, headers) {
        url?.let { SeekPreviewController(it, headers) }
    }
    DisposableEffect(controller) {
        onDispose { controller?.dispose() }
    }
    return controller
}
