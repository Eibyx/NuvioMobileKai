package com.nuvio.app.features.subtitledownloader.engine

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Everything the downloader sent to and received from the remote server, as it happened. */
internal class NetStats {
    val requests = AtomicLong()
    val bodyBytes = AtomicLong()
    val headerBytes = AtomicLong()
    val retries = AtomicLong()
    val refreshes = AtomicLong()
    val redirects = AtomicLong()
    val overloads = AtomicLong()
    val errors = AtomicLong()
    val cancelled = AtomicLong()
    val newConnections = AtomicLong()
    val reusedConnections = AtomicLong()
    val http2Requests = AtomicLong()
    val cacheHitBytes = AtomicLong()
    private val statusCounts = java.util.concurrent.ConcurrentHashMap<Int, AtomicLong>()

    /** Bytes received from the remote server: bodies (read or abandoned) and response headers. */
    val trafficBytes: Long get() = bodyBytes.get() + headerBytes.get()

    fun status(code: Int) {
        statusCounts.getOrPut(code) { AtomicLong() }.incrementAndGet()
    }

    fun statuses(): Map<Int, Long> = statusCounts.mapValues { it.value.get() }.toSortedMap()
}

internal class BudgetExceededException(val limitBytes: Long, val neededBytes: Long) :
    java.io.IOException("download budget of $limitBytes bytes reached")

/**
 * The most the remote server may send for one extraction. Requests reserve what they expect
 * before they go out, so concurrent requests can't overshoot it together.
 */
internal class TrafficBudget(@Volatile var limitBytes: Long, private val stats: NetStats) {
    private val reserved = AtomicLong()

    val usedBytes: Long get() = stats.trafficBytes

    fun remaining(): Long = limitBytes - usedBytes - reserved.get()

    fun reserve(bytes: Long): Long {
        while (true) {
            val current = reserved.get()
            if (usedBytes + current + bytes > limitBytes) throw BudgetExceededException(limitBytes, bytes)
            if (reserved.compareAndSet(current, current + bytes)) return bytes
        }
    }

    fun release(bytes: Long) {
        reserved.addAndGet(-bytes)
    }
}

/**
 * The remote server as measured so far: time to first byte on new and reused connections, how
 * fast one connection and all of them together move data, and what a request costs in headers.
 * Until something is measured it assumes the TeraBox figures measured on the user's phone.
 */
internal class NetworkModel(
    priorLatencySeconds: Double = 0.448,
    priorBandwidth: Double = 2.71 * MIB,
) {
    private val lock = Any()
    private var ttfbReused = Ewma(priorLatencySeconds)
    private var ttfbNew = Ewma(priorLatencySeconds * 1.5)
    private var connectionBandwidth = Ewma(priorBandwidth)
    private var overheadBytes = Ewma(1_200.0)
    private var reusedShare = Ewma(0.5)
    private val transfers = ArrayDeque<Pair<Long, Long>>()

    var measuredRequests = 0
        private set

    fun record(ttfbSeconds: Double, newConnection: Boolean, bodyBytes: Long, transferSeconds: Double, headerBytes: Long) = synchronized(lock) {
        measuredRequests++
        if (newConnection) ttfbNew.add(ttfbSeconds) else ttfbReused.add(ttfbSeconds)
        reusedShare.add(if (newConnection) 0.0 else 1.0)
        // Only transfers long enough to say something about the bandwidth.
        if (bodyBytes >= 64 * 1024 && transferSeconds > 0.02) connectionBandwidth.add(bodyBytes / transferSeconds)
        overheadBytes.add(headerBytes.toDouble())
        val now = System.nanoTime()
        transfers.addLast(now to bodyBytes)
        while (transfers.isNotEmpty() && now - transfers.first().first > WINDOW_NS) transfers.removeFirst()
    }

    /** Seconds until the first byte of a request, as the next one is likely to get it. */
    fun latency(): Double = synchronized(lock) {
        val share = reusedShare.value.coerceIn(0.0, 1.0)
        share * ttfbReused.value + (1 - share) * ttfbNew.value
    }

    fun latencyReused(): Double = synchronized(lock) { ttfbReused.value }

    fun latencyNew(): Double = synchronized(lock) { ttfbNew.value }

    fun connectionBandwidth(): Double = synchronized(lock) { connectionBandwidth.value }

    /** Bytes a second over every connection in the last few seconds. */
    fun recentGoodput(): Double = synchronized(lock) {
        if (transfers.size < 2) return@synchronized 0.0
        val span = (transfers.last().first - transfers.first().first) / 1e9
        if (span <= 0.5) 0.0 else transfers.sumOf { it.second } / span
    }

    fun requestOverhead(): Double = synchronized(lock) { overheadBytes.value }

    /** What one connection gets when [connections] share the line. */
    fun shareBandwidth(connections: Int): Double {
        val total = max(recentGoodput(), connectionBandwidth())
        return min(connectionBandwidth(), total / max(1, connections)).coerceAtLeast(16 * 1024.0)
    }

    /**
     * The largest gap between two wanted byte ranges that is cheaper to read through than to skip
     * with a new request: `(L + λ·O) / (1/B + λ)`, with λ the price of a byte in seconds. With λ 0 it
     * is the bandwidth-delay product; as λ grows it falls to the cost of a request in bytes.
     */
    fun mergeGap(lambdaSecondsPerByte: Double, connections: Int): Long {
        val latency = latency()
        val bandwidth = shareBandwidth(connections)
        val overhead = requestOverhead()
        val gap = (latency + lambdaSecondsPerByte * overhead) / (1.0 / bandwidth + lambdaSecondsPerByte)
        return gap.toLong().coerceAtLeast(0L)
    }

    /** Seconds [requests] requests of [bytes] in all take over [connections] connections. */
    fun estimateSeconds(requests: Int, bytes: Long, connections: Int): Double {
        if (requests <= 0) return 0.0
        val parallel = max(1, min(connections, requests))
        val waves = Math.ceil(requests.toDouble() / parallel)
        // The line is shared: more connections cut the waiting, not the transfer; and while some
        // requests wait for their first byte, others use the line, so the two overlap.
        val total = max(recentGoodput(), connectionBandwidth()).coerceAtLeast(1.0)
        return latency() + max(waves * latency(), bytes / total)
    }

    private class Ewma(initial: Double, private val weight: Double = 0.25) {
        var value = initial
            private set
        private var samples = 0

        fun add(sample: Double) {
            if (!sample.isFinite()) return
            // The first measurements replace the assumption rather than nudge it.
            value = if (samples < 3) (value * samples + sample) / (samples + 1) else value * (1 - weight) + sample * weight
            samples++
        }
    }

    companion object {
        const val MIB = 1024.0 * 1024.0
        private const val WINDOW_NS = 6_000_000_000L
    }
}
