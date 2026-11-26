package com.nuvio.app.features.subtitledownloader.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Where the bytes of a remote file come from: a cache in front of ranged requests. */
internal interface ByteSource {
    val size: Long

    /** Exactly [length] bytes from [start]; whatever isn't cached is fetched with at least [window] bytes. */
    suspend fun read(start: Long, length: Int, window: Long = length.toLong()): ByteArray

    /** Fetches what isn't cached of every range, in parallel, splitting large ones. */
    suspend fun prefetch(ranges: List<LongRange>)

    /** Bytes held already from [start], up to [length]: read without asking the server. */
    fun cached(start: Long, length: Int): ByteArray?
}

/**
 * Reads a remote file through its [cache], fetching what is missing with [fetcher]: requests for
 * bytes another request is already bringing wait for it rather than ask again.
 */
internal class RangeReader(
    private val fetcher: HttpRangeFetcher,
    @Volatile var cache: SparseCache,
    private val stats: NetStats,
    override val size: Long,
    /** The largest single request, so one range can't hold up the others. */
    private val maxRequestBytes: () -> Long,
) : ByteSource {
    private val lock = Any()
    private val inFlight = HashMap<LongRange, Deferred<Unit>>()

    override fun cached(start: Long, length: Int): ByteArray? {
        val clipped = minOf(length.toLong(), size - start).toInt()
        if (clipped <= 0) return ByteArray(0)
        return cache.get(start, clipped)
    }

    override suspend fun read(start: Long, length: Int, window: Long): ByteArray {
        require(start >= 0 && length >= 0)
        val end = minOf(size, start + length)
        if (end <= start) return ByteArray(0)
        val count = (end - start).toInt()
        while (true) {
            cache.get(start, count)?.let {
                stats.cacheHitBytes.addAndGet(count.toLong())
                return it
            }
            val gap = cache.missing(start, count.toLong()).first()
            val fetchEnd = minOf(size, maxOf(gap.last + 1, gap.first + window), nextCachedOrEnd(gap.first, gap.first + maxOf(window, count.toLong())))
            ensure(gap.first until maxOf(fetchEnd, gap.last + 1))
        }
    }

    override suspend fun prefetch(ranges: List<LongRange>) = coroutineScope {
        val pieces = ranges.flatMap { range ->
            val clipped = range.first.coerceAtLeast(0) until minOf(size, range.last + 1)
            if (clipped.isEmpty()) emptyList() else cache.missing(clipped.first, clipped.last - clipped.first + 1).flatMap(::split)
        }
        pieces.map { piece -> async { ensure(piece) } }.awaitAll()
        Unit
    }

    private fun split(range: LongRange): List<LongRange> {
        val limit = maxRequestBytes().coerceAtLeast(64 * 1024)
        val out = mutableListOf<LongRange>()
        var at = range.first
        val parts = Math.ceil((range.last - range.first + 1).toDouble() / limit).toLong().coerceAtLeast(1)
        val each = Math.ceil((range.last - range.first + 1).toDouble() / parts).toLong()
        while (at <= range.last) {
            val stop = minOf(range.last, at + each - 1)
            out += at..stop
            at = stop + 1
        }
        return out
    }

    /** The end to stop a fetch from [from] at, so it doesn't fetch again what is cached. */
    private fun nextCachedOrEnd(from: Long, wantedEnd: Long): Long {
        val gaps = cache.missing(from, (minOf(size, wantedEnd) - from).coerceAtLeast(1))
        return gaps.firstOrNull()?.let { it.last + 1 } ?: minOf(size, wantedEnd)
    }

    /** Fetches [range] unless a request in flight already brings it. */
    private suspend fun ensure(range: LongRange) {
        while (true) {
            val (waitFor, mine) = synchronized(lock) {
                val overlapping = inFlight.entries.firstOrNull { (r, _) -> r.first <= range.last && range.first <= r.last }
                if (overlapping != null) {
                    overlapping.value to null
                } else {
                    val deferred = CompletableDeferred<Unit>()
                    inFlight[range] = deferred
                    null to deferred
                }
            }
            if (waitFor != null) {
                runCatching { waitFor.await() }
                if (cache.missing(range.first, range.last - range.first + 1).isEmpty()) return
                continue
            }
            try {
                val missing = cache.missing(range.first, range.last - range.first + 1)
                for (gap in missing) {
                    val bytes = fetcher.fetch(gap.first, gap.last)
                    cache.put(gap.first, bytes)
                }
                mine!!.complete(Unit)
            } catch (e: Throwable) {
                mine!!.completeExceptionally(e)
                throw e
            } finally {
                synchronized(lock) { inFlight.remove(range) }
            }
            return
        }
    }
}
