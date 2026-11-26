package com.nuvio.app.features.subtitledownloader.engine

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal class EngineSettings(
    val memoryCacheBytes: Long = 96L * 1024 * 1024,
    val diskCacheBytes: Long = 256L * 1024 * 1024,
    /** Where each file version's cached ranges go; null keeps them in memory only. */
    val cacheRoot: File? = null,
    val initialConcurrency: Int = 4,
    val maxConcurrency: Int = 8,
    val targetSeconds: Double = 60.0,
    val limitSeconds: Double = 120.0,
    val allowPrivateHosts: Boolean = false,
)

enum class Strategy { INDEX, SPARSE, HYBRID, NONE }

enum class Integrity {
    /** Every event the file's statistics count was extracted. */
    VERIFIED_STATISTICS,
    /** Every cluster was walked, so every block of the track was seen. */
    VERIFIED_STRUCTURE,
    /** As many events as index entries, ReadOrder without gaps, but no statistics to check against. */
    CONSISTENT_INDEX,
    INCOMPLETE,
    UNSUPPORTED,
}

internal class TrackEstimate(
    val track: MkvTrack,
    val expectedEvents: Long?,
    val indexedEvents: Int,
    val indexedWithPosition: Int,
)

internal class Detection(
    val layout: MkvLayout,
    val attachments: List<MkvAttachment>,
    val tracks: List<TrackEstimate>,
    val strategy: Strategy,
    val estimatedSeconds: Double,
    val estimatedBytes: Long,
    val rangeSupported: Boolean,
    val detectionMs: Long,
)

internal class TrackResult(
    val track: MkvTrack,
    val ass: ByteArray?,
    val events: Int,
    val expectedEvents: Long?,
    val integrity: Integrity,
    val notes: List<String>,
)

internal class WalkSample(
    val clusters: Int,
    val requests: Long,
    val bytes: Long,
    val millis: Long,
    val clusterBytes: Long,
    val estimatedClusters: Int,
    val window: Long,
)

internal class FontResult(val attachment: MkvAttachment, val sha256: String)

internal class ExtractionResult(
    val tracks: List<TrackResult>,
    val fonts: List<FontResult>,
    val strategy: Strategy,
    val elapsedMs: Long,
    val trafficBytes: Long,
    val requests: Long,
    val fileSize: Long,
    val notes: List<String>,
)

/** A snapshot of an extraction as it runs. */
internal data class EngineProgress(
    val phase: Phase,
    val strategy: Strategy,
    val events: Int,
    val expectedEvents: Long?,
    val fontsDone: Int,
    val fontsTotal: Int,
    val trafficBytes: Long,
    val requests: Long,
    val fileSize: Long,
    val elapsedMs: Long,
    val etaMs: Long?,
    val bytesPerSecond: Double,
    val concurrency: Int,
    val coveredFraction: Double,
) {
    enum class Phase { CONNECTING, DETECTING, FONTS, INDEX, WALK, WRITING, DONE }
}

internal class ExtractionRequest(
    val tracks: Set<Long>,
    /** Attachment indexes to save; fonts only. */
    val fonts: Set<Int>,
    val mode: ExtractionMode,
    val budgetBytes: Long,
)

/** Receives each extracted font, its bytes checked with SHA-256, as soon as it is complete. */
internal fun interface FontSink {
    suspend fun font(attachment: MkvAttachment, bytes: ByteArray, sha256: String)
}

/**
 * One remote MKV: its headers read once, then any of its ASS/SSA tracks and attached fonts
 * extracted with the strategy the network and the file's indexes make fastest within the budget.
 */
internal class SubtitleSession(
    client: OkHttpClient,
    private val source: RemoteSource,
    private val settings: EngineSettings,
    val stats: NetStats = NetStats(),
    val model: NetworkModel = NetworkModel(),
) {
    val limiter = AdaptiveLimiter(settings.initialConcurrency, 1, settings.maxConcurrency, model)
    val budget = TrafficBudget(Long.MAX_VALUE, stats)
    private val fetcher = HttpRangeFetcher(client, source, stats, budget, limiter, model, allowPrivateHosts = settings.allowPrivateHosts)
    private var reader: RangeReader? = null
    var detection: Detection? = null
        private set
    private val startedNs = System.nanoTime()

    val protocol: String? get() = fetcher.lastProtocol?.toString()

    /** Reads the headers and indexes (and lists the attachments) without touching a cluster. */
    suspend fun detect(listAttachments: Boolean = true): Detection {
        detection?.let { return it }
        val started = System.nanoTime()
        val firstWindow = FIRST_WINDOW
        val first = fetcher.fetch(0, firstWindow - 1)
        val size = fetcher.fileSize ?: source.sizeHint ?: throw RangeNotSupportedException("the server gives no file size")
        val versionKey = sha256Hex(("${source.stableId}|$size|").toByteArray() + sha256(first.copyOf(minOf(first.size, 64 * 1024))))
        fetcher.identityPrefix = first.copyOf(minOf(first.size, 4096))
        val cache = SparseCache(
            versionKey,
            settings.memoryCacheBytes,
            settings.cacheRoot?.let { File(it, versionKey.take(32)) },
            settings.diskCacheBytes,
        )
        cache.put(0, first)
        val reader = RangeReader(fetcher, cache, stats, size) { maxRequestBytes() }
        this.reader = reader
        val probe = MkvProbe(reader) { headerWindow() }
        val layout = probe.probe()
        // A small Attachments element comes whole, in parallel, instead of one header after another:
        // the fonts are then already here when they are saved.
        layout.attachmentsContent?.let { content ->
            val length = content.last - content.first + 1
            if (listAttachments && length <= minOf(64L * 1024 * 1024, size / 10)) reader.prefetch(listOf(content))
        }
        val attachments = if (listAttachments) layout.attachmentsContent?.let { probe.listAttachments(it) }.orEmpty() else emptyList()
        val estimates = layout.assTracks.map { track ->
            val cues = layout.cuesFor(track.number)
            TrackEstimate(track, layout.expectedFrames(track), cues.size, cues.count { it.relativePosition != null })
        }
        val (strategy, seconds, bytes) = estimate(layout, estimates)
        return Detection(layout, attachments, estimates, strategy, seconds, bytes, true, (System.nanoTime() - started) / 1_000_000)
            .also { detection = it }
    }

    private fun headerWindow(): Long = model.mergeGap(2e-5, limiter.limit).coerceIn(16L * 1024, 512L * 1024)

    private fun maxRequestBytes(): Long =
        (model.connectionBandwidth() * maxOf(model.latency(), 0.2) * 6).toLong().coerceIn(1L * 1024 * 1024, 8L * 1024 * 1024)

    /** Time and traffic each strategy is expected to take, and the one chosen. */
    private fun estimate(layout: MkvLayout, tracks: List<TrackEstimate>): Triple<Strategy, Double, Long> {
        if (tracks.isEmpty()) return Triple(Strategy.NONE, 0.0, 0)
        val indexed = tracks.all { it.indexedWithPosition > 0 && (it.expectedEvents == null || it.indexedWithPosition >= it.expectedEvents) }
        val limit = limiter.limit
        return if (indexed) {
            val targets = tracks.sumOf { it.indexedWithPosition }
            val clusters = tracks.flatMap { t -> layout.cuesFor(t.track.number).map { it.clusterPosition } }.distinct().size
            val ranges = minOf(targets + clusters, (clusters * 1.2).toInt() + 1)
            val bytes = ranges * 4096L
            Triple(Strategy.INDEX, model.estimateSeconds(ranges, bytes, limit), bytes)
        } else {
            val clusterBytes = (layout.segmentEnd - (layout.firstClusterPosition ?: layout.segmentDataPosition)).coerceAtLeast(1)
            val window = model.mergeGap(2e-5, limit).coerceIn(4L * 1024, 8L * 1024 * 1024)
            // A first guess: one request per window of the clusters' headers, corrected as the walk measures it.
            val clusters = layout.cues?.map { it.clusterPosition }?.distinct()?.size?.coerceAtLeast(1) ?: (clusterBytes / (2L * 1024 * 1024)).toInt().coerceAtLeast(1)
            val requests = (clusters * 40)
            val bytes = minOf(clusterBytes, requests * window)
            Triple(if (tracks.any { it.indexedWithPosition > 0 }) Strategy.HYBRID else Strategy.SPARSE, model.estimateSeconds(requests, bytes, limit), bytes)
        }
    }

    suspend fun extract(request: ExtractionRequest, fontSink: FontSink, onProgress: (EngineProgress) -> Unit): ExtractionResult = coroutineScope {
        val detection = detect()
        val reader = checkNotNull(reader)
        val layout = detection.layout
        val started = System.nanoTime()
        budget.limitBytes = stats.trafficBytes + request.budgetBytes
        val notes = mutableListOf<String>()
        notes += layout.notes
        val selected = layout.assTracks.filter { it.number in request.tracks }
        val unsupported = selected.filter { it.unsupportedReason != null }
        val readable = (selected - unsupported.toSet()).associateBy { it.number }
        val expectedTotal = readable.values.mapNotNull { layout.expectedFrames(it) }.takeIf { it.size == readable.size }?.sum()
        val fontsWanted = detection.attachments.filter { it.index in request.fonts && it.isFont }

        val phase = AtomicReference(EngineProgress.Phase.FONTS)
        val strategy = AtomicReference(Strategy.NONE)
        val eventCount = AtomicInteger()
        val fontsDone = AtomicInteger()
        val covered = java.util.concurrent.atomic.AtomicLong()
        val clusterSpan = (layout.segmentEnd - (layout.firstClusterPosition ?: layout.segmentDataPosition)).coerceAtLeast(1)
        val controllerRef = AtomicReference<WindowController?>()

        fun snapshot(done: Boolean = false): EngineProgress {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val fraction = when {
                done -> 1.0
                expectedTotal != null && expectedTotal > 0 -> (eventCount.get().toDouble() / expectedTotal).coerceAtMost(0.99)
                phase.get() == EngineProgress.Phase.WALK -> (covered.get().toDouble() / clusterSpan).coerceAtMost(0.99)
                else -> 0.0
            }
            val eta = (controllerRef.get()?.projectedSeconds ?: plannedSeconds?.takeIf { phase.get() == EngineProgress.Phase.INDEX })
                ?.let { ((it * 1000) - elapsed).toLong().coerceAtLeast(0) }
                ?: if (fraction > 0.02) (elapsed / fraction - elapsed).toLong() else null
            return EngineProgress(
                phase = if (done) EngineProgress.Phase.DONE else phase.get(),
                strategy = strategy.get(),
                events = eventCount.get(),
                expectedEvents = expectedTotal,
                fontsDone = fontsDone.get(),
                fontsTotal = fontsWanted.size,
                trafficBytes = stats.trafficBytes,
                requests = stats.requests.get(),
                fileSize = layout.fileSize,
                elapsedMs = elapsed,
                etaMs = if (done) 0 else eta,
                bytesPerSecond = model.recentGoodput(),
                concurrency = limiter.limit,
                coveredFraction = fraction,
            )
        }

        val ticker = launch {
            while (isActive) {
                onProgress(snapshot())
                delay(250)
            }
        }

        // Fonts first: their ranges are known, so they go out at once, in parallel.
        val fontsJob = async {
            if (fontsWanted.isEmpty()) return@async emptyList<FontResult>()
            val ranges = fontsWanted.map { it.dataPosition until it.dataPosition + it.dataSize }
            reader.prefetch(RangePlanner.merge(ranges, model.mergeGap(0.0, limiter.limit) / 4))
            fontsWanted.map { attachment ->
                val bytes = reader.read(attachment.dataPosition, attachment.dataSize.toInt())
                val hash = sha256Hex(bytes)
                fontSink.font(attachment, bytes, hash)
                fontsDone.incrementAndGet()
                FontResult(attachment, hash)
            }
        }

        val events = ConcurrentHashMap<Triple<Long, Long, Int>, AssEvent>()
        fun add(found: Collection<AssEvent>) {
            found.forEach { e -> events.putIfAbsent(Triple(e.track, e.position, e.frameIndex), e) }
            eventCount.set(events.size)
        }
        var structural = emptySet<Long>()
        var sparseBlocks: Map<Long, Int> = emptyMap()
        var laced = 0
        if (readable.isNotEmpty()) {
            val withIndex = readable.values.filter { layout.cuesFor(it.number).isNotEmpty() }.associateBy { it.number }
            var walkClusters = emptySet<Long>()
            if (withIndex.isNotEmpty()) {
                phase.set(EngineProgress.Phase.INDEX)
                strategy.set(Strategy.INDEX)
                val fontBytes = fontsWanted.sumOf { a -> reader.cache.missing(a.dataPosition, a.dataSize).sumOf { it.last - it.first + 1 } }
                val outcome = IndexedExtractor(reader, layout, withIndex, { wanted -> planMerge(wanted, request.mode, fontBytes) }) { }.run()
                add(outcome.events)
                walkClusters = outcome.walkClusters
                if (outcome.invalidCues > 0) notes += "${outcome.invalidCues} index entries didn't point at a subtitle block"
            }
            // Which tracks the index can't vouch for: no cues, cues without positions, or fewer events than counted.
            val needWalk = readable.values.filter { track ->
                val got = events.values.count { it.track == track.number }
                val expected = layout.expectedFrames(track)
                track.number !in withIndex || walkClusters.isNotEmpty() || (expected != null && got < expected) ||
                    (expected == null && !readOrderComplete(events.values.filter { it.track == track.number }))
            }
            val verifyOnly = needWalk.isNotEmpty() && needWalk.all { track ->
                track.number in withIndex && layout.expectedFrames(track) == null && walkClusters.isEmpty()
            }
            val walkWorthIt = if (verifyOnly) {
                // Nothing missing as far as the index tells; a full walk only to verify, when it fits the time and budget.
                val (_, seconds, bytes) = estimate(layout, emptyList<TrackEstimate>().plus(needWalk.map { TrackEstimate(it, null, 0, 0) }))
                seconds <= settings.limitSeconds && bytes < budget.remaining()
            } else needWalk.isNotEmpty()
            if (walkWorthIt) {
                phase.set(EngineProgress.Phase.WALK)
                strategy.set(if (withIndex.isEmpty()) Strategy.SPARSE else Strategy.HYBRID)
                val controller = WindowController(model, limiter, budget, request.mode, settings.targetSeconds, clusterSpan)
                controllerRef.set(controller)
                val starts = buildSet {
                    layout.firstClusterPosition?.let(::add)
                    layout.cues?.forEach { add(layout.absolute(it.clusterPosition)) }
                    addAll(walkClusters)
                }
                val walkTracks = needWalk.associateBy { it.number }
                val outcome = SparseExtractor(reader, layout, walkTracks, controller, { bytes, _ -> covered.set(bytes) }).run(starts)
                add(outcome.events)
                sparseBlocks = outcome.blocks
                laced = outcome.laced
                if (outcome.coverageComplete && outcome.broken.isEmpty()) structural = walkTracks.keys
                else notes += "the walk left ${outcome.gaps.size} gaps and ${outcome.broken.size} unreadable clusters"
            } else if (needWalk.isNotEmpty()) {
                notes += "verifying by walking every cluster was left out: it would not fit the time limit or the budget"
            }
        }
        val fonts = fontsJob.await()

        phase.set(EngineProgress.Phase.WRITING)
        val results = selected.map { track ->
            val reason = track.unsupportedReason
            if (reason != null) return@map TrackResult(track, null, 0, layout.expectedFrames(track), Integrity.UNSUPPORTED, listOf("frames are $reason"))
            val trackEvents = events.values.filter { it.track == track.number }
            val written = AssWriter.write(track, Blocks.codecPrivate(track), trackEvents, layout.timestampScale)
            val expected = layout.expectedFrames(track)
            val trackNotes = mutableListOf<String>()
            if (written.malformedEvents > 0) trackNotes += "${written.malformedEvents} events didn't match the Format line and were kept whole"
            if (written.inferredDurations > 0) trackNotes += "${written.inferredDurations} events had no duration: they end where the next one starts"
            val duplicates = trackEvents.groupBy { it.readOrder }.count { (k, v) -> k != null && v.size > 1 }
            if (duplicates > 0) trackNotes += "$duplicates ReadOrder values appear more than once"
            val integrity = when {
                expected != null && trackEvents.size.toLong() == expected -> Integrity.VERIFIED_STATISTICS
                expected != null && trackEvents.size < expected -> Integrity.INCOMPLETE.also { trackNotes += "${expected - trackEvents.size} of $expected events are missing" }
                track.number in structural && (sparseBlocks[track.number] ?: 0) <= trackEvents.size -> Integrity.VERIFIED_STRUCTURE
                expected == null && track.number !in structural && readOrderComplete(trackEvents) &&
                    trackEvents.size >= layout.cuesFor(track.number).size -> Integrity.CONSISTENT_INDEX
                else -> Integrity.INCOMPLETE
            }
            TrackResult(track, written.bytes, trackEvents.size, expected, integrity, trackNotes)
        }
        if (laced > 0) notes += "$laced laced subtitle blocks were unpacked"
        ticker.cancel()
        reader.cache.persist()
        onProgress(snapshot(done = true))
        ExtractionResult(
            tracks = results,
            fonts = fonts,
            strategy = strategy.get(),
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            trafficBytes = stats.trafficBytes,
            requests = stats.requests.get(),
            fileSize = layout.fileSize,
            notes = notes,
        )
    }

    /**
     * The requests for [wanted]: the merge of nearby ranges the mode prefers among those the budget
     * allows. Automatic takes the least traffic that the model expects to finish within the target
     * time (else within the limit), data saver the least traffic, speed the least time.
     */
    private fun planMerge(wanted: List<LongRange>, mode: ExtractionMode, reservedBytes: Long): List<LongRange> {
        val connections = minOf(limiter.externalCeiling, settings.maxConcurrency).coerceAtLeast(1)
        val missing = wanted.flatMap { r -> reader!!.cache.missing(r.first, r.last - r.first + 1) }
        if (missing.isEmpty()) return emptyList()
        val options = LAMBDAS.map { lambda ->
            val merged = RangePlanner.merge(missing, model.mergeGap(lambda, connections))
            val bytes = merged.sumOf { it.last - it.first + 1 } + (merged.size * model.requestOverhead()).toLong()
            Triple(merged, bytes, model.estimateSeconds(merged.size, bytes, connections))
        }.distinctBy { it.first.size }
        val allowed = (budget.remaining() - reservedBytes) * 0.95
        val feasible = options.filter { it.second <= allowed }.ifEmpty { listOf(options.minBy { it.second }) }
        val chosen = when (mode) {
            ExtractionMode.SPEED -> feasible.minBy { it.third }
            ExtractionMode.SAVER -> feasible.minBy { it.second }
            // Speed first: among plans within 15 % of the fastest, the one with the least traffic.
            ExtractionMode.AUTO -> feasible.minBy { it.third }.third.let { best -> feasible.filter { it.third <= best * 1.15 }.minBy { it.second } }
        }
        plannedSeconds = chosen.third
        return chosen.first
    }

    /** What the last plan expected to take, for the progress shown. */
    @Volatile
    var plannedSeconds: Double? = null
        private set

    /** Walks [count] clusters spread over the file, to measure what the cluster walk costs here. */
    suspend fun sampleWalk(count: Int, mode: ExtractionMode): WalkSample = coroutineScope {
        val detection = detect()
        val reader = checkNotNull(reader)
        val layout = detection.layout
        val known = layout.cues.orEmpty().map { layout.absolute(it.clusterPosition) }.distinct().sorted()
        val starts = if (known.isNotEmpty()) {
            val step = maxOf(1, known.size / count)
            known.filterIndexed { i, _ -> i % step == 0 }.take(count)
        } else listOfNotNull(layout.firstClusterPosition)
        val span = (layout.segmentEnd - (layout.firstClusterPosition ?: layout.segmentDataPosition)).coerceAtLeast(1)
        val controller = WindowController(model, limiter, budget, mode, settings.targetSeconds, span)
        val walker = ClusterWalker(reader, layout, layout.assTracks.associateBy { it.number }, controller::window)
        val requestsBefore = stats.requests.get()
        val bytesBefore = stats.trafficBytes
        val started = System.nanoTime()
        val scans = starts.map { position -> async { runCatching { walker.scan(position) }.getOrNull() } }.map { it.await() }.filterNotNull()
        val millis = (System.nanoTime() - started) / 1_000_000
        val clusterBytes = scans.sumOf { it.end - it.position }
        val estimated = if (known.isNotEmpty()) known.size else if (scans.isNotEmpty()) (span / maxOf(1, clusterBytes / scans.size)).toInt() else 0
        WalkSample(scans.size, stats.requests.get() - requestsBefore, stats.trafficBytes - bytesBefore, millis, clusterBytes, estimated, controller.window())
    }

    /** Whether ReadOrder runs from 0 without a gap or a repeat. */
    private fun readOrderComplete(events: Collection<AssEvent>): Boolean {
        if (events.isEmpty()) return false
        val orders = events.mapNotNull { it.readOrder }
        if (orders.size != events.size) return false
        val sorted = orders.sorted()
        return sorted.first() == 0L && sorted.last() == sorted.size - 1L && sorted.distinct().size == sorted.size
    }

    companion object {
        const val FIRST_WINDOW = 256L * 1024
        private val LAMBDAS = doubleArrayOf(0.0, 1e-8, 3e-8, 1e-7, 3e-7, 1e-6, 3e-6, 1e-5, 3e-5, 1e-4, 1e-3, 1e-1)

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }
    }
}
