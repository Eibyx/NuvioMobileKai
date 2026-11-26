package com.nuvio.app.features.subtitledownloader

import com.nuvio.app.features.subtitledownloader.engine.EngineSettings
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import com.nuvio.app.features.subtitledownloader.engine.ExtractionRequest
import com.nuvio.app.features.subtitledownloader.engine.Integrity
import com.nuvio.app.features.subtitledownloader.engine.RemoteEndpoint
import com.nuvio.app.features.subtitledownloader.engine.RemoteSource
import com.nuvio.app.features.subtitledownloader.engine.SubtitleSession
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A simulated benchmark, not a measurement of any real server: a 349 MiB MKV with two ASS tracks
 * (16,609 events) and 19 fonts, served at 448 ms per request and 2.71 MiB/s shared, at most eight
 * requests at once (503 beyond). Run with NUVIO_SUBDL_BENCH=1; NUVIO_SUBDL_BENCH_SCALE=10 runs ten
 * times faster (latency ÷ 10, bandwidth × 10) and reports times × 10.
 */
class SubtitleEngineBenchmark {
    private val scale = System.getenv("NUVIO_SUBDL_BENCH_SCALE")?.toDoubleOrNull() ?: 1.0

    private fun file(cues: CueStyle): Pair<TestMkvBuilder, VirtualFile> {
        val random = Random(2026)
        val duration = 1440
        val tracks = listOf(
            TestTrack(3, language = "eng", name = "Full", header = TestMkvBuilder.HEADER, events = TestMkvBuilder.events(11_200, duration, random)),
            TestTrack(4, language = "spa", name = "Latino", header = TestMkvBuilder.HEADER, events = TestMkvBuilder.events(5_409, duration, random, step = 5)),
        )
        val fonts = (0 until 19).map { i ->
            TestAttachment("Font$i.ttf", "application/x-truetype-font", random.nextBytes(40_000 + random.nextInt(1_200_000)))
        }
        val builder = TestMkvBuilder(durationSeconds = duration, videoBitsPerSecond = 1_870_000, tracks = tracks, attachments = fonts, cueStyle = cues)
        return builder to builder.build().file
    }

    private fun bench(name: String, cues: CueStyle, mode: ExtractionMode, budget: Double, fonts: Boolean = true, tracks: Boolean = true) = runBlocking {
        assumeTrue(System.getenv("NUVIO_SUBDL_BENCH") == "1")
        val (builder, virtual) = file(cues)
        val server = RangeTestServer(virtual, latencyMs = (448 / scale).toLong(), bytesPerSecond = (2.71 * 1024 * 1024 * scale).toLong(), maxConcurrent = 8)
        try {
            val source = object : RemoteSource {
                override val stableId = "bench"
                override val sizeHint: Long? = null
                override val canRefresh = true
                override suspend fun resolve(forceRefresh: Boolean) = RemoteEndpoint(server.linkUrl, mapOf("Cookie" to "ndus=x"))
            }
            val session = SubtitleSession(OkHttpClient(), source, EngineSettings(allowPrivateHosts = true))
            val detectStart = System.nanoTime()
            val detection = session.detect()
            val detectMs = (System.nanoTime() - detectStart) / 1_000_000
            val detectRequests = session.stats.requests.get()
            val detectBytes = session.stats.trafficBytes
            val result = session.extract(
                ExtractionRequest(
                    if (tracks) detection.layout.assTracks.map { it.number }.toSet() else emptySet(),
                    if (fonts) detection.attachments.filter { it.isFont }.map { it.index }.toSet() else emptySet(),
                    mode,
                    (virtual.size * budget).toLong(),
                ),
                { _, _, _ -> },
            ) {}
            if (tracks) for (track in builder.tracks) {
                val got = result.tracks.first { it.track.number == track.number.toLong() }
                assertEquals(track.expectedAss(), String(got.ass!!))
                assertEquals(Integrity.VERIFIED_STATISTICS, got.integrity)
            }
            val events = result.tracks.sumOf { it.events }
            println(
                "[bench] $name | scale ×$scale | detection ${(detectMs * scale).toLong()} ms, $detectRequests req, ${detectBytes / 1024} KiB | " +
                    "extraction ${(result.elapsedMs * scale).toLong()} ms (simulated) | ${result.strategy} | events $events | fonts ${result.fonts.size} | " +
                    "${result.requests} requests | ${result.trafficBytes / 1024 / 1024} MiB of ${virtual.size / 1024 / 1024} MiB " +
                    "(${"%.1f".format(result.trafficBytes * 100.0 / virtual.size)} %) | peak ${session.limiter.peak} conn | 503 ${server.rejected.get()}",
            )
        } finally {
            server.stop()
        }
    }

    @Test fun cues_auto() = bench("Cues + positions, automatic", CueStyle.ALL_WITH_POSITION, ExtractionMode.AUTO, 0.25)
    @Test fun cues_saver() = bench("Cues + positions, data saver", CueStyle.ALL_WITH_POSITION, ExtractionMode.SAVER, 0.25)
    @Test fun cues_speed() = bench("Cues + positions, speed", CueStyle.ALL_WITH_POSITION, ExtractionMode.SPEED, 0.25)
    @Test fun fonts_only() = bench("Fonts only", CueStyle.ALL_WITH_POSITION, ExtractionMode.AUTO, 0.25, tracks = false)
    @Test fun no_subtitle_cues_auto() = bench("Video cues only, automatic", CueStyle.VIDEO_ONLY, ExtractionMode.AUTO, 0.5, fonts = false)
}
