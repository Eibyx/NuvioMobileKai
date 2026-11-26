package com.nuvio.app.features.subtitledownloader.engine

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the index led to: the events found, and clusters whose subtitles it couldn't place. */
internal class IndexedOutcome(
    val events: List<AssEvent>,
    val cueTargets: Int,
    val walkClusters: Set<Long>,
    val invalidCues: Int,
    val headerRequests: Int,
    val cueTimeTrusted: Boolean,
)

/**
 * Reads the subtitle blocks the Cues point at. CueRelativePosition places each block in its
 * cluster and CueTime gives its time, so only the blocks are asked for: the cluster's own header
 * (its size field's length, its timestamp) is read for a sample of clusters, to check that the
 * index agrees with the clusters, and for any block that can't be placed without it. Nearby blocks
 * share one request when the planner finds that cheaper than another request.
 */
internal class IndexedExtractor(
    private val source: ByteSource,
    private val layout: MkvLayout,
    private val tracks: Map<Long, MkvTrack>,
    /** Joins the wanted ranges into the requests to make: the planner's choice of time against traffic. */
    private val plan: (List<LongRange>) -> List<LongRange>,
    private val onEvents: (Int) -> Unit,
) {
    suspend fun run(): IndexedOutcome = coroutineScope {
        val cues = tracks.keys.flatMap { layout.cuesFor(it) }
        val walk = HashSet<Long>()
        val byCluster = TreeMap<Long, MutableList<CueEntry>>()
        val seen = HashSet<Pair<Long, Long>>()
        for (cue in cues) {
            val cluster = layout.absolute(cue.clusterPosition)
            if (cue.relativePosition == null) {
                walk += cluster
                continue
            }
            if (seen.add(cluster to cue.relativePosition)) byCluster.getOrPut(cluster) { mutableListOf() } += cue
        }
        val estimate = tracks.values.associate { track ->
            val stats = layout.statistics[track.uid]
            val average = if (stats?.frames != null && stats.bytes != null && stats.frames > 0) stats.bytes / stats.frames else 700L
            track.number to (average * 2 + 96).coerceIn(512L, 64L * 1024)
        }
        val clusters = byCluster.keys.toList()
        // The clusters whose header is read to check the index: the first, then one in every SAMPLE_EVERY.
        val sample = clusters.filterIndexed { i, _ -> i % SAMPLE_EVERY == 0 }.toSet()
        fun windows(cluster: Long, entries: List<CueEntry>) = entries.map { cue ->
            // The cluster's size takes 1 to 8 bytes, so the block starts 5 to 12 bytes past the cluster.
            val from = cluster + 5 + cue.relativePosition!!
            from until from + 7 + (estimate[cue.track] ?: 2048L)
        }
        val wanted = mutableListOf<LongRange>()
        byCluster.forEach { (cluster, entries) ->
            if (cluster in sample) wanted += cluster until cluster + CLUSTER_HEAD
            wanted += windows(cluster, entries)
        }
        source.prefetch(plan(wanted))

        val events = ConcurrentHashMap.newKeySet<AssEvent>()
        val invalid = AtomicInteger()
        val headers = AtomicInteger()
        val walkFound = ConcurrentHashMap.newKeySet<Long>()
        val mismatch = java.util.concurrent.atomic.AtomicBoolean(false)

        // The sample first: exact positions and cluster timestamps, compared with CueTime.
        sample.map { cluster ->
            async {
                val entries = byCluster.getValue(cluster)
                val found = runCatching { readExact(cluster, entries) }.getOrNull()
                if (found == null) {
                    walkFound += cluster
                    invalid.addAndGet(entries.size)
                } else {
                    events += found.events
                    if (found.bad > 0) { invalid.addAndGet(found.bad); walkFound += cluster }
                    if (found.cueTimeMismatch) mismatch.set(true)
                    onEvents(found.events.size)
                }
            }
        }.awaitAll()
        val trustCueTime = !mismatch.get()
        val rest = clusters.filter { it !in sample }
        if (!trustCueTime) {
            // The index's times don't match the clusters': every cluster's header is read.
            source.prefetch(plan(rest.map { it until it + CLUSTER_HEAD }))
        }
        rest.map { cluster ->
            async {
                val entries = byCluster.getValue(cluster)
                val found = runCatching {
                    if (trustCueTime) readByCues(cluster, entries, headers) else readExact(cluster, entries).also { headers.incrementAndGet() }
                }.getOrNull()
                if (found == null) {
                    walkFound += cluster
                    invalid.addAndGet(entries.size)
                } else {
                    events += found.events
                    if (found.bad > 0) { invalid.addAndGet(found.bad); walkFound += cluster }
                    onEvents(found.events.size)
                }
            }
        }.awaitAll()
        IndexedOutcome(events.toList(), byCluster.values.sumOf { it.size }, walk + walkFound, invalid.get(), headers.get() + sample.size, trustCueTime)
    }

    private class Found(val events: List<AssEvent>, val bad: Int, val cueTimeMismatch: Boolean = false)

    /** With the cluster's header read: exact block positions, the cluster's timestamp, CueTime checked. */
    private suspend fun readExact(cluster: Long, entries: List<CueEntry>): Found {
        val header = Ebml.header(source.read(cluster, 12), 0) ?: throw MkvFormatException("no cluster header")
        if (header.id != Ebml.CLUSTER) throw MkvFormatException("cue points at no cluster")
        val dataStart = cluster + header.headerLength
        val timestamp = clusterTimestamp(dataStart, if (header.sizeKnown) dataStart + header.size else layout.segmentEnd)
        val out = mutableListOf<AssEvent>()
        var bad = 0
        var mismatch = false
        for (cue in entries) {
            val block = runCatching { blockAt(dataStart + cue.relativePosition!!, cue.track, timestamp) }.getOrNull()
            if (block == null) {
                bad++
            } else {
                if (block.any { it.start != cue.time }) mismatch = true
                out += block
            }
        }
        return Found(out, bad, mismatch)
    }

    /**
     * Without the cluster's header: each block is found among the eight places its cluster's size
     * field allows, by its structure (block id, size, track, and BlockDuration against CueDuration),
     * and timed by CueTime. A block that can't be told apart this way gets the header read after all.
     */
    private suspend fun readByCues(cluster: Long, entries: List<CueEntry>, headers: AtomicInteger): Found {
        val out = mutableListOf<AssEvent>()
        var bad = 0
        var exact: Long? = null
        for (cue in entries) {
            val base = cluster + 5 + cue.relativePosition!!
            val located = exact?.let { listOf(it + cue.relativePosition) } ?: candidates(base, cue)
            if (located.size == 1) {
                val events = runCatching { blockAt(located.single(), cue.track, null, cue.time) }.getOrNull()
                if (events != null) {
                    out += events
                    continue
                }
            }
            // Ambiguous or unreadable: the cluster's header settles where its data starts.
            if (exact == null) {
                headers.incrementAndGet()
                val header = Ebml.header(source.read(cluster, 12), 0)
                if (header == null || header.id != Ebml.CLUSTER) {
                    bad++
                    continue
                }
                exact = cluster + header.headerLength
            }
            val events = runCatching { blockAt(exact + cue.relativePosition, cue.track, null, cue.time) }.getOrNull()
            if (events == null) bad++ else out += events
        }
        return Found(out, bad)
    }

    /** Where among the eight possible starts a block of [cue]'s track, matching its duration, is. */
    private suspend fun candidates(base: Long, cue: CueEntry): List<Long> {
        val window = source.read(base, 24 + 8)
        val found = mutableListOf<Long>()
        for (k in 0 until 8) {
            val h = runCatching { Ebml.header(window, k) }.getOrNull() ?: continue
            if (!h.sizeKnown || h.size <= 0 || h.size > 16L * 1024 * 1024) continue
            val position = base + k
            when (h.id) {
                Ebml.SIMPLE_BLOCK -> {
                    val track = runCatching { Blocks.trackOf(window, k + h.headerLength, window.size) }.getOrNull()?.first
                    if (track == cue.track) found += position
                }
                Ebml.BLOCK_GROUP -> {
                    val inner = runCatching { Ebml.header(window, k + h.headerLength) }.getOrNull() ?: continue
                    if (inner.id != Ebml.BLOCK && inner.id != Ebml.BLOCK_DURATION) continue
                    if (inner.id == Ebml.BLOCK) {
                        val track = runCatching { Blocks.trackOf(window, k + h.headerLength + inner.headerLength, window.size) }.getOrNull()?.first
                        if (track != cue.track) continue
                    }
                    if (cue.duration != null && !durationMatches(position, h, cue.duration)) continue
                    found += position
                }
            }
        }
        return found
    }

    private suspend fun durationMatches(position: Long, group: Ebml.Header, duration: Long): Boolean {
        val data = source.read(position + group.headerLength, group.size.toInt())
        var matches = true
        runCatching {
            Ebml.children(data, 0, data.size) { c, at ->
                if (c.id == Ebml.BLOCK_DURATION && Ebml.uint(data, at, c.size.toInt()) != duration) matches = false
            }
        }.onFailure { return false }
        return matches
    }

    private suspend fun clusterTimestamp(from: Long, to: Long): Long {
        var at = from
        while (at < to) {
            val h = Ebml.header(source.read(at, 12), 0) ?: break
            val content = at + h.headerLength
            if (h.id == Ebml.TIMESTAMP) return Ebml.uint(source.read(content, h.size.toInt()), 0, h.size.toInt())
            if (h.id == Ebml.SIMPLE_BLOCK || h.id == Ebml.BLOCK_GROUP || !h.sizeKnown) break
            at = content + h.size
        }
        throw MkvFormatException("cluster without a timestamp before its blocks")
    }

    /** The events of the block at [position]; timed from the cluster's [clusterTimestamp], else from [cueTime]. */
    private suspend fun blockAt(position: Long, track: Long, clusterTimestamp: Long?, cueTime: Long? = null): List<AssEvent>? {
        val h = Ebml.header(source.read(position, 12), 0) ?: return null
        if (!h.sizeKnown) return null
        val content = position + h.headerLength
        val data = source.read(content, h.size.toInt())
        val mkvTrack = tracks[track] ?: return null
        fun base(block: ParsedBlock) = clusterTimestamp ?: (cueTime!! - block.relativeTimestamp)
        return when (h.id) {
            Ebml.SIMPLE_BLOCK -> {
                val block = Blocks.parse(data, 0, data.size)
                if (block.track != track) null else Blocks.events(block, mkvTrack, base(block), null, position)
            }
            Ebml.BLOCK_GROUP -> {
                var block: ParsedBlock? = null
                var duration: Long? = null
                Ebml.children(data, 0, data.size) { c, at ->
                    when (c.id) {
                        Ebml.BLOCK -> block = Blocks.parse(data, at, c.size.toInt())
                        Ebml.BLOCK_DURATION -> duration = Ebml.uint(data, at, c.size.toInt())
                    }
                }
                val found = block ?: return null
                if (found.track != track) null else Blocks.events(found, mkvTrack, base(found), duration, position)
            }
            else -> null
        }
    }

    private companion object {
        const val CLUSTER_HEAD = 48L
        const val SAMPLE_EVERY = 16
    }
}

internal object RangePlanner {
    /** Sorted, overlapping or close ranges joined: a gap up to [gap] bytes is read rather than skipped. */
    fun merge(ranges: List<LongRange>, gap: Long): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val out = mutableListOf<LongRange>()
        var current = sorted.first()
        for (range in sorted.drop(1)) {
            if (range.first <= current.last + 1 + gap) {
                current = current.first..maxOf(current.last, range.last)
            } else {
                out += current
                current = range
            }
        }
        out += current
        return out
    }
}

internal class SparseOutcome(
    val events: List<AssEvent>,
    val clusters: Int,
    val coverageComplete: Boolean,
    val blocks: Map<Long, Int>,
    val laced: Int,
    val gaps: List<LongRange>,
    val broken: List<Long>,
)

/**
 * Walks every cluster by its headers, many at once: the clusters the Cues name are walked in
 * parallel, and each walk, at its end, finds the next cluster, so clusters no cue names are
 * reached too. How much to read through instead of skipping is retuned as it goes, from the time
 * and traffic it is heading for.
 */
internal class SparseExtractor(
    private val source: ByteSource,
    private val layout: MkvLayout,
    private val tracks: Map<Long, MkvTrack>,
    private val controller: WindowController,
    private val onCluster: (bytesCovered: Long, events: Int) -> Unit,
    private val workers: Int = 16,
    private val skipClusters: Set<Long> = emptySet(),
) {
    suspend fun run(starts: Collection<Long>): SparseOutcome = coroutineScope {
        val walker = ClusterWalker(source, layout, tracks, controller::window)
        val scanned = ConcurrentHashMap<Long, Long>()
        val covered = ConcurrentHashMap<Long, Long>()
        val queued = ConcurrentHashMap.newKeySet<Long>()
        val events = ConcurrentHashMap.newKeySet<AssEvent>()
        val blocks = ConcurrentHashMap<Long, AtomicInteger>()
        val laced = AtomicInteger()
        val pending = AtomicInteger()
        val coveredBytes = AtomicLong()
        val broken = ConcurrentHashMap.newKeySet<Long>()
        val queue = Channel<Long>(Channel.UNLIMITED)
        fun offer(position: Long) {
            if (position >= layout.segmentEnd || !queued.add(position)) return
            pending.incrementAndGet()
            queue.trySend(position)
        }
        starts.sorted().forEach(::offer)
        if (pending.get() == 0) queue.close()
        val tuner = launch {
            while (isActive) {
                delay(1_000)
                controller.retune(coveredBytes.get())
            }
        }
        val jobs = (0 until workers).map {
            launch {
                for (position in queue) {
                    run launchItem@{ try {
                        if (!scanned.containsKey(position)) {
                            var next: Long? = null
                            val header = Ebml.header(source.read(position, 12, controller.window()), 0)
                            when {
                                header == null -> {}
                                header.id == Ebml.CLUSTER -> {
                                    // A sized cluster names the next one at once: walked in parallel, not after this one.
                                    if (header.sizeKnown) offer(position + header.headerLength + header.size)
                                    val scan = try {
                                        walker.scan(position)
                                    } catch (e: MkvFormatException) {
                                        broken += position
                                        null
                                    } ?: return@launchItem
                                    scanned[position] = scan.end
                                    covered[position] = scan.end
                                    if (position !in skipClusters) {
                                        events += scan.events
                                        scan.blocks.forEach { (t, n) -> blocks.getOrPut(t) { AtomicInteger() }.addAndGet(n) }
                                        laced.addAndGet(scan.laced)
                                    }
                                    coveredBytes.addAndGet(scan.end - position)
                                    onCluster(coveredBytes.get(), scan.events.size)
                                    next = scan.end
                                }
                                header.sizeKnown && header.id in Ebml.LEVEL1 || header.id == Ebml.VOID || header.id == Ebml.CRC32 -> {
                                    val end = position + header.headerLength + header.size
                                    covered[position] = end
                                    coveredBytes.addAndGet(end - position)
                                    next = end
                                }
                            }
                            next?.let(::offer)
                        }
                    } finally {
                        if (pending.decrementAndGet() == 0) queue.close()
                    } }
                }
            }
        }
        jobs.forEach { it.join() }
        tuner.cancel()
        // Coverage: from the first cluster, every byte up to the segment's end belongs to something walked.
        val first = (starts.minOrNull() ?: layout.segmentEnd)
        val gaps = mutableListOf<LongRange>()
        var reach = first
        covered.toSortedMap().forEach { (start, end) ->
            if (start > reach) gaps += reach until start
            reach = maxOf(reach, end)
        }
        if (reach < layout.segmentEnd) gaps += reach until layout.segmentEnd
        SparseOutcome(
            events = events.toList(),
            clusters = scanned.size,
            coverageComplete = gaps.isEmpty(),
            blocks = blocks.mapValues { it.value.get() },
            laced = laced.get(),
            gaps = gaps,
            broken = broken.toList(),
        )
    }
}

/**
 * The read-through window of the cluster walk: the cost model's merge gap at a price of traffic
 * λ that starts from the chosen mode and moves with the projected time and traffic.
 */
internal class WindowController(
    private val model: NetworkModel,
    private val limiter: AdaptiveLimiter,
    private val budget: TrafficBudget,
    private val mode: ExtractionMode,
    private val targetSeconds: Double,
    private val totalBytes: Long,
    private val startedNs: Long = System.nanoTime(),
    private val trafficAtStart: Long = budget.usedBytes,
) {
    @Volatile
    var lambda: Double = when (mode) {
        ExtractionMode.AUTO -> 2e-5
        ExtractionMode.SAVER -> 2e-4
        ExtractionMode.SPEED -> 0.0
    }
        private set

    @Volatile
    var projectedSeconds: Double? = null
        private set

    fun window(): Long = model.mergeGap(lambda, limiter.limit).coerceIn(MIN_WINDOW, MAX_WINDOW)

    fun retune(coveredBytes: Long) {
        if (coveredBytes <= 0 || totalBytes <= 0) return
        val done = (coveredBytes.toDouble() / totalBytes).coerceIn(1e-4, 1.0)
        val elapsed = (System.nanoTime() - startedNs) / 1e9
        val traffic = (budget.usedBytes - trafficAtStart).toDouble()
        val timeAtEnd = elapsed / done
        val trafficAtEnd = budget.usedBytes + traffic / done * (1 - done)
        projectedSeconds = timeAtEnd
        val overBudget = trafficAtEnd > budget.limitBytes * 0.9
        when {
            overBudget -> lambda = (if (lambda == 0.0) 1e-6 else lambda * 2).coerceAtMost(1e-2)
            mode == ExtractionMode.SAVER -> {}
            timeAtEnd > targetSeconds && trafficAtEnd * 1.3 < budget.limitBytes -> lambda /= 2
            mode == ExtractionMode.AUTO && timeAtEnd < targetSeconds * 0.5 && lambda < 2e-4 -> lambda *= 1.5
        }
        if (lambda < 1e-9) lambda = 0.0
    }

    private companion object {
        const val MIN_WINDOW = 256L
        const val MAX_WINDOW = 8L * 1024 * 1024
    }
}

enum class ExtractionMode { AUTO, SAVER, SPEED }
