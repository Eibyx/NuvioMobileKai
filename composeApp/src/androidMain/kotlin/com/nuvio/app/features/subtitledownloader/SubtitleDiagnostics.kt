package com.nuvio.app.features.subtitledownloader

import android.os.Debug
import com.nuvio.app.features.subtitledownloader.engine.AdaptiveLimiter
import com.nuvio.app.features.subtitledownloader.engine.BudgetExceededException
import com.nuvio.app.features.subtitledownloader.engine.EngineSettings
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import com.nuvio.app.features.subtitledownloader.engine.ExtractionRequest
import com.nuvio.app.features.subtitledownloader.engine.HttpRangeFetcher
import com.nuvio.app.features.subtitledownloader.engine.NetStats
import com.nuvio.app.features.subtitledownloader.engine.NetworkModel
import com.nuvio.app.features.subtitledownloader.engine.RemoteSource
import com.nuvio.app.features.subtitledownloader.engine.SubtitleSession
import com.nuvio.app.features.subtitledownloader.engine.TrafficBudget
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

@Serializable
data class LatencySample(val label: String, val ttfbMs: List<Long>, val medianMs: Long)

@Serializable
data class ThroughputSample(val label: String, val bytes: Long, val connections: Int, val millis: Long, val bytesPerSecond: Long, val overloads: Long)

@Serializable
data class EngineSample(
    val engine: String,
    val millis: Long,
    val requests: Long,
    val trafficBytes: Long,
    val events: Int,
    val expectedEvents: Long?,
    val integrity: List<String>,
    val projectedMillis: Long? = null,
    val note: String? = null,
)

@Serializable
data class DiagnosticsReport(
    val createdAt: String,
    val sourceKind: String,
    val host: String,
    val protocol: String?,
    val fileSize: Long,
    val rangeSupported: Boolean,
    val latencyNew: LatencySample?,
    val latencyReused: LatencySample?,
    val rangeSizes: List<ThroughputSample>,
    val concurrency: List<ThroughputSample>,
    val bestConcurrency: Int?,
    val detectionMs: Long?,
    val detectionRequests: Long?,
    val detectionBytes: Long?,
    val tracks: List<String>,
    val fonts: Int,
    val fontBytes: Long,
    val cues: String,
    val statistics: Boolean,
    val engines: List<EngineSample>,
    val totalRequests: Long,
    val totalTrafficBytes: Long,
    val retries: Long,
    val overloads: Long,
    val refreshes: Long,
    val statuses: Map<Int, Long>,
    val newConnections: Long,
    val reusedConnections: Long,
    val http2Requests: Long,
    val javaHeapUsedMb: Long,
    val javaHeapMaxMb: Long,
    val nativeHeapMb: Long,
    val phases: Map<String, Long>,
    val errors: List<String>,
)

/**
 * Measures one link the way the downloader uses it, reading at most [budgetBytes]: no link,
 * cookie, token or account name goes into the report; the host is cut to its last two labels.
 */
internal object SubtitleDiagnostics {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun run(stream: StreamItem, season: Int?, episode: Int?, budgetBytes: Long, step: (String) -> Unit): DiagnosticsReport = coroutineScope {
        val errors = mutableListOf<String>()
        val phases = LinkedHashMap<String, Long>()
        step("resolve")
        var started = System.nanoTime()
        val source = SubtitleSources.forStream(stream, season, episode)
        phases["resolve"] = (System.nanoTime() - started) / 1_000_000
        val endpoint = source.resolve(false)
        val host = endpoint.url.substringAfter("://").substringBefore('/').substringBefore(':').split('.').takeLast(2).joinToString(".")
        val kind = source.stableId.substringBefore(':')

        val stats = NetStats()
        val model = NetworkModel()
        val budget = TrafficBudget(budgetBytes, stats)

        fun fetcher(client: OkHttpClient, limit: Int): HttpRangeFetcher =
            HttpRangeFetcher(client, source, stats, budget, AdaptiveLimiter(limit, limit, limit, model), model)

        // Latency on new connections: a fresh pool each time.
        step("latency")
        val fresh = mutableListOf<Long>()
        started = System.nanoTime()
        var protocol: String? = null
        var size = 0L
        var rangeOk = true
        repeat(3) {
            val client = SubtitleDownloaderRepository.client.newBuilder().connectionPool(ConnectionPool(1, 1, TimeUnit.SECONDS)).build()
            val f = fetcher(client, 1)
            val t = System.nanoTime()
            runCatching { f.fetch(0, 1023) }.onFailure { e -> errors += "latency-new: ${e.javaClass.simpleName}"; rangeOk = rangeOk && e !is com.nuvio.app.features.subtitledownloader.engine.RangeNotSupportedException }
            fresh += (System.nanoTime() - t) / 1_000_000
            protocol = f.lastProtocol?.toString() ?: protocol
            size = f.fileSize ?: size
            client.connectionPool.evictAll()
        }
        val shared = fetcher(SubtitleDownloaderRepository.client, 8)
        val reused = mutableListOf<Long>()
        runCatching { shared.fetch(0, 1023) }
        repeat(5) { i ->
            val offset = if (size > 0) (size / 7) * (i + 1) else 0L
            val t = System.nanoTime()
            runCatching { shared.fetch(offset, offset + 1023) }.onFailure { e -> errors += "latency-reused: ${e.javaClass.simpleName}" }
            reused += (System.nanoTime() - t) / 1_000_000
        }
        phases["latency"] = (System.nanoTime() - started) / 1_000_000

        // Throughput by range size, one connection.
        step("ranges")
        val sizes = mutableListOf<ThroughputSample>()
        started = System.nanoTime()
        if (size > 0 && rangeOk) {
            for (bytes in listOf(64L * 1024, 256L * 1024, 1L * 1024 * 1024, 4L * 1024 * 1024)) {
                val offset = (size / 3).coerceAtMost(size - bytes - 1).coerceAtLeast(0)
                val t = System.nanoTime()
                val ok = runCatching { shared.fetch(offset, offset + bytes - 1) }.isSuccess
                val ms = (System.nanoTime() - t) / 1_000_000
                if (ok) sizes += ThroughputSample("${bytes / 1024} KiB", bytes, 1, ms, if (ms > 0) bytes * 1000 / ms else 0, 0)
            }
        }
        phases["ranges"] = (System.nanoTime() - started) / 1_000_000

        // Throughput by connections: 1 MiB ranges, n at once.
        step("concurrency")
        val parallel = mutableListOf<ThroughputSample>()
        started = System.nanoTime()
        if (size > 16L * 1024 * 1024 && rangeOk) {
            for (n in listOf(1, 2, 4, 8)) {
                val overloadsBefore = stats.overloads.get()
                val f = fetcher(SubtitleDownloaderRepository.client, n)
                val chunk = 1L * 1024 * 1024
                val t = System.nanoTime()
                val ok = runCatching {
                    (0 until n).map { i ->
                        async {
                            val offset = (size / (n + 2)) * (i + 1)
                            f.fetch(offset, offset + chunk - 1)
                        }
                    }.awaitAll()
                }.isSuccess
                val ms = (System.nanoTime() - t) / 1_000_000
                if (ok) parallel += ThroughputSample("$n×1 MiB", chunk * n, n, ms, if (ms > 0) chunk * n * 1000 / ms else 0, stats.overloads.get() - overloadsBefore)
            }
        }
        phases["concurrency"] = (System.nanoTime() - started) / 1_000_000
        val best = parallel.maxByOrNull { it.bytesPerSecond }?.connections

        // Detection and the two engines, on a session of their own with what was measured.
        step("detection")
        val enginesStarted = System.nanoTime()
        val cacheDir = File(SubtitleDownloaderRepository.diagnosticsCacheDir(), "diag").apply { deleteRecursively() }
        val session = SubtitleSession(SubtitleDownloaderRepository.client, source, EngineSettings(cacheRoot = cacheDir, diskCacheBytes = 0))
        val engines = mutableListOf<EngineSample>()
        var tracks = emptyList<String>()
        var fonts = 0
        var fontBytes = 0L
        var cues = "none"
        var statistics = false
        var detectionMs: Long? = null
        var detectionRequests: Long? = null
        var detectionBytes: Long? = null
        runCatching {
            val detection = session.detect()
            detectionMs = detection.detectionMs
            detectionRequests = session.stats.requests.get()
            detectionBytes = session.stats.trafficBytes
            val layout = detection.layout
            tracks = detection.tracks.map { t ->
                "${t.track.formatLabel} ${t.track.languageCode ?: "und"} events=${t.expectedEvents ?: "?"} cues=${t.indexedEvents} withPosition=${t.indexedWithPosition}" +
                    (t.track.unsupportedReason?.let { " unsupported=$it" } ?: "")
            }
            fonts = detection.attachments.count { it.isFont }
            fontBytes = detection.attachments.filter { it.isFont }.sumOf { it.dataSize }
            cues = when {
                layout.cues == null -> "none"
                detection.tracks.all { it.indexedWithPosition > 0 } -> "subtitles with positions"
                detection.tracks.any { it.indexedEvents > 0 } -> "partial subtitles"
                else -> "video only"
            }
            statistics = layout.statistics.isNotEmpty()

            if (detection.tracks.any { it.indexedWithPosition > 0 }) {
                step("index")
                val before = session.stats.trafficBytes
                val beforeRequests = session.stats.requests.get()
                val result = runCatching {
                    session.extract(
                        ExtractionRequest(detection.tracks.filter { it.indexedWithPosition > 0 }.map { it.track.number }.toSet(), emptySet(), ExtractionMode.AUTO, (budgetBytes - before).coerceAtLeast(1)),
                        { _, _, _ -> },
                    ) {}
                }
                result.onSuccess { r ->
                    engines += EngineSample("index", r.elapsedMs, r.requests - beforeRequests, r.trafficBytes - before, r.tracks.sumOf { it.events }, r.tracks.mapNotNull { it.expectedEvents }.takeIf { it.isNotEmpty() }?.sum(), r.tracks.map { it.integrity.name })
                }.onFailure { e -> engines += EngineSample("index", 0, 0, 0, 0, null, emptyList(), note = e.javaClass.simpleName) }
            }
            step("walk")
            val walk = runCatching { session.sampleWalk(12, ExtractionMode.AUTO) }
            walk.onSuccess { w ->
                // The sample ran its clusters in parallel as the full walk would: time scales with the cluster count.
                val projected = if (w.clusters > 0) w.millis * w.estimatedClusters / w.clusters else null
                engines += EngineSample(
                    engine = "walk-sample",
                    millis = w.millis,
                    requests = w.requests,
                    trafficBytes = w.bytes,
                    events = 0,
                    expectedEvents = null,
                    integrity = emptyList(),
                    projectedMillis = projected,
                    note = "${w.clusters} of ${w.estimatedClusters} clusters, ${w.clusterBytes} bytes of clusters, window ${w.window}; projection = sample time × clusters ÷ sampled clusters",
                )
            }.onFailure { e -> errors += "walk: ${e.javaClass.simpleName}" }
        }.onFailure { e -> errors += "detection: ${e.javaClass.simpleName}" + if (e is BudgetExceededException) " (diagnostic budget)" else "" }
        phases["detection+engines"] = (System.nanoTime() - enginesStarted) / 1_000_000
        val runtime = Runtime.getRuntime()
        DiagnosticsReport(
            createdAt = java.time.OffsetDateTime.now().toString(),
            sourceKind = kind,
            host = host,
            protocol = protocol,
            fileSize = size,
            rangeSupported = rangeOk,
            latencyNew = fresh.takeIf { it.isNotEmpty() }?.let { LatencySample("new connection", it, it.sorted()[it.size / 2]) },
            latencyReused = reused.takeIf { it.isNotEmpty() }?.let { LatencySample("reused connection", it, it.sorted()[it.size / 2]) },
            rangeSizes = sizes,
            concurrency = parallel,
            bestConcurrency = best,
            detectionMs = detectionMs,
            detectionRequests = detectionRequests,
            detectionBytes = detectionBytes,
            tracks = tracks,
            fonts = fonts,
            fontBytes = fontBytes,
            cues = cues,
            statistics = statistics,
            engines = engines,
            totalRequests = stats.requests.get() + session.stats.requests.get(),
            totalTrafficBytes = stats.trafficBytes + session.stats.trafficBytes,
            retries = stats.retries.get() + session.stats.retries.get(),
            overloads = stats.overloads.get() + session.stats.overloads.get(),
            refreshes = stats.refreshes.get() + session.stats.refreshes.get(),
            statuses = (stats.statuses().keys + session.stats.statuses().keys).associateWith { (stats.statuses()[it] ?: 0) + (session.stats.statuses()[it] ?: 0) },
            newConnections = stats.newConnections.get() + session.stats.newConnections.get(),
            reusedConnections = stats.reusedConnections.get() + session.stats.reusedConnections.get(),
            http2Requests = stats.http2Requests.get() + session.stats.http2Requests.get(),
            javaHeapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            javaHeapMaxMb = runtime.maxMemory() / (1024 * 1024),
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
            phases = phases,
            errors = errors,
        ).also { cacheDir.deleteRecursively() }
    }

    fun toJson(report: DiagnosticsReport): String = json.encodeToString(DiagnosticsReport.serializer(), report)

    fun toText(report: DiagnosticsReport): String = buildString {
        appendLine("Nuvio Subtitle Downloader — diagnostics")
        appendLine("Measured on this device; times depend on the network at that moment.")
        appendLine("Date: ${report.createdAt}")
        appendLine("Source: ${report.sourceKind} · host *.${report.host} · ${report.protocol ?: "?"}")
        appendLine("File: ${formatBytes(report.fileSize)} · HTTP Range: ${if (report.rangeSupported) "yes" else "no"}")
        report.latencyNew?.let { appendLine("TTFB new connection: median ${it.medianMs} ms ${it.ttfbMs}") }
        report.latencyReused?.let { appendLine("TTFB reused connection: median ${it.medianMs} ms ${it.ttfbMs}") }
        appendLine("Range sizes (one connection):")
        report.rangeSizes.forEach { appendLine("  ${it.label}: ${it.millis} ms · ${formatBytes(it.bytesPerSecond)}/s") }
        appendLine("Connections (1 MiB each):")
        report.concurrency.forEach { appendLine("  ${it.label}: ${it.millis} ms · ${formatBytes(it.bytesPerSecond)}/s · overloads ${it.overloads}") }
        report.bestConcurrency?.let { appendLine("Best concurrency measured: $it") }
        appendLine("Detection: ${report.detectionMs ?: "?"} ms · ${report.detectionRequests ?: "?"} requests · ${report.detectionBytes?.let(::formatBytes) ?: "?"}")
        appendLine("Cues: ${report.cues} · statistics tags: ${if (report.statistics) "yes" else "no"}")
        report.tracks.forEach { appendLine("  Track: $it") }
        appendLine("Fonts: ${report.fonts} · ${formatBytes(report.fontBytes)}")
        report.engines.forEach { e ->
            appendLine("Engine ${e.engine}: ${e.millis} ms · ${e.requests} requests · ${formatBytes(e.trafficBytes)} · events ${e.events}/${e.expectedEvents ?: "?"} ${e.integrity}")
            e.projectedMillis?.let { appendLine("  projected for the whole file: ${formatDuration(it)}") }
            e.note?.let { appendLine("  $it") }
        }
        appendLine("Totals: ${report.totalRequests} requests · ${formatBytes(report.totalTrafficBytes)} · retries ${report.retries} · overloads ${report.overloads} · link refreshes ${report.refreshes}")
        appendLine("Connections: ${report.newConnections} new · ${report.reusedConnections} reused · HTTP/2 requests ${report.http2Requests}")
        appendLine("HTTP statuses: ${report.statuses}")
        appendLine("Memory: Java ${report.javaHeapUsedMb}/${report.javaHeapMaxMb} MB · native ${report.nativeHeapMb} MB")
        appendLine("Phases (ms): ${report.phases}")
        if (report.errors.isNotEmpty()) appendLine("Errors: ${report.errors}")
    }
}
