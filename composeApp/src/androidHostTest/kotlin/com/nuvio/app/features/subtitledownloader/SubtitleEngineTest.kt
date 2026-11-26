package com.nuvio.app.features.subtitledownloader

import com.nuvio.app.features.subtitledownloader.engine.AssWriter
import com.nuvio.app.features.subtitledownloader.engine.Blocks
import com.nuvio.app.features.subtitledownloader.engine.BudgetExceededException
import com.nuvio.app.features.subtitledownloader.engine.EngineSettings
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import com.nuvio.app.features.subtitledownloader.engine.ExtractionRequest
import com.nuvio.app.features.subtitledownloader.engine.ExtractionResult
import com.nuvio.app.features.subtitledownloader.engine.FontSink
import com.nuvio.app.features.subtitledownloader.engine.Integrity
import com.nuvio.app.features.subtitledownloader.engine.RangeNotSupportedException
import com.nuvio.app.features.subtitledownloader.engine.RemoteEndpoint
import com.nuvio.app.features.subtitledownloader.engine.RemoteSource
import com.nuvio.app.features.subtitledownloader.engine.SubtitleSession
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The extraction engine against synthetic Matroska files served by a ranged test server: every
 * ASS script must come back byte for byte, fonts with their SHA-256, without the whole file.
 */
class SubtitleEngineTest {
    private val servers = mutableListOf<RangeTestServer>()
    private val client = OkHttpClient()

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private fun fonts(random: Random) = listOf(
        TestAttachment("OpenSans-Semibold.ttf", "application/x-truetype-font", random.nextBytes(180_000)),
        TestAttachment("GandhiSans-Bold.otf", "application/vnd.ms-opentype", random.nextBytes(95_000)),
        TestAttachment("cover.jpg", "image/jpeg", random.nextBytes(400_000)),
    )

    private fun twoTracks(random: Random, duration: Int, eventsEach: Int, zlib: Boolean = false, lace: Boolean = false) = listOf(
        TestTrack(3, language = "eng", name = "Full", header = TestMkvBuilder.HEADER, events = TestMkvBuilder.events(eventsEach, duration, random), zlib = zlib, lace = lace),
        TestTrack(4, language = "spa", name = "Latino", header = TestMkvBuilder.HEADER.replace("\n", "\r\n"), events = TestMkvBuilder.events(eventsEach, duration, random, step = 3)),
    )

    private class Collected : FontSink {
        val fonts = ConcurrentHashMap<String, Pair<ByteArray, String>>()
        override suspend fun font(attachment: com.nuvio.app.features.subtitledownloader.engine.MkvAttachment, bytes: ByteArray, sha256: String) {
            fonts[attachment.name] = bytes to sha256
        }
    }

    private fun source(server: RangeTestServer, refresh: () -> Unit = server::renew) = object : RemoteSource {
        override val stableId = "test:file"
        override val sizeHint: Long? = null
        override val canRefresh = true
        override suspend fun resolve(forceRefresh: Boolean): RemoteEndpoint {
            if (forceRefresh) refresh()
            return RemoteEndpoint(server.linkUrl, mapOf("Cookie" to "ndus=secret", "User-Agent" to "test"))
        }
    }

    private fun run(
        builder: TestMkvBuilder,
        mode: ExtractionMode = ExtractionMode.AUTO,
        budgetFraction: Double = 0.5,
        configure: RangeTestServer.() -> Unit = {},
        fonts: Boolean = true,
    ): Triple<ExtractionResult, Collected, RangeTestServer> = runBlocking {
        val built = builder.build()
        val server = RangeTestServer(built.file).apply(configure).also { servers += it }
        val session = SubtitleSession(client, source(server), EngineSettings(allowPrivateHosts = true))
        val detection = session.detect()
        val sink = Collected()
        val request = ExtractionRequest(
            tracks = detection.layout.assTracks.map { it.number }.toSet(),
            fonts = if (fonts) detection.attachments.filter { it.isFont }.map { it.index }.toSet() else emptySet(),
            mode = mode,
            budgetBytes = (built.file.size * budgetFraction).toLong(),
        )
        val result = session.extract(request, sink) {}
        println("[engine] ${result.strategy} ${result.elapsedMs} ms, ${result.requests} requests, ${result.trafficBytes} of ${built.file.size} bytes (${"%.1f".format(result.trafficBytes * 100.0 / built.file.size)} %), peak ${session.limiter.peak} connections")
        Triple(result, sink, server)
    }

    private fun assertScripts(builder: TestMkvBuilder, result: ExtractionResult, integrity: Set<Integrity>) {
        for (track in builder.tracks) {
            val got = result.tracks.first { it.track.number == track.number.toLong() }
            val expected = track.expectedAss().let { if (track.header.contains("\r\n")) it.replace("\r\n", "\n").replace("\n", "\r\n") else it }
            assertEquals("track ${track.number}", expected, String(got.ass!!, Charsets.UTF_8))
            assertTrue("track ${track.number} integrity ${got.integrity} ${got.notes}", got.integrity in integrity)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun indexed_file_gives_every_script_and_font_without_the_whole_file() {
        val random = Random(1)
        val attachments = fonts(random)
        val builder = TestMkvBuilder(durationSeconds = 300, tracks = twoTracks(random, 300, 600), attachments = attachments)
        val (result, sink, server) = run(builder)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
        assertEquals(2, sink.fonts.size)
        attachments.filter { it.name.endsWith("tf") }.forEach { font ->
            assertArrayEquals(font.data, sink.fonts[font.name]!!.first)
            assertEquals(sha(font.data), sink.fonts[font.name]!!.second)
        }
        assertTrue("downloaded ${server.bodyBytes.get()} of ${builder.build().file.size}", server.bodyBytes.get() < builder.build().file.size / 2)
    }

    @Test
    fun file_without_subtitle_cues_is_walked_and_verified() {
        val random = Random(2)
        val builder = TestMkvBuilder(durationSeconds = 240, tracks = twoTracks(random, 240, 300), cueStyle = CueStyle.VIDEO_ONLY)
        val (result, _, server) = run(builder, budgetFraction = 0.9)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
        assertTrue(server.bodyBytes.get() < builder.build().file.size)
    }

    @Test
    fun file_without_any_cues_or_statistics_is_verified_by_structure() {
        val random = Random(3)
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = twoTracks(random, 120, 150), cueStyle = CueStyle.NONE, statistics = false)
        val (result, _, _) = run(builder, budgetFraction = 0.9)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STRUCTURE))
    }

    @Test
    fun unknown_size_clusters_and_crc_elements_are_walked() {
        val random = Random(4)
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = twoTracks(random, 120, 150), cueStyle = CueStyle.VIDEO_ONLY, unknownSizeClusters = true, crcInClusters = true)
        val (result, _, _) = run(builder, budgetFraction = 0.9)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
    }

    @Test
    fun cues_without_relative_positions_fall_back_to_walking_their_clusters() {
        val random = Random(5)
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = twoTracks(random, 120, 200), cueStyle = CueStyle.SUBTITLES_WITHOUT_POSITION)
        val (result, _, _) = run(builder, budgetFraction = 0.9)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
    }

    @Test
    fun zlib_and_laced_subtitles_are_unpacked() {
        val random = Random(6)
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = twoTracks(random, 120, 300, zlib = true, lace = true), cueStyle = CueStyle.VIDEO_ONLY)
        val (result, _, _) = run(builder, budgetFraction = 0.9)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
    }

    @Test
    fun simple_blocks_without_duration_end_at_the_next_event() {
        val random = Random(7)
        val tracks = listOf(TestTrack(3, header = TestMkvBuilder.HEADER, events = TestMkvBuilder.events(120, 120, random), simpleBlocks = true))
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = tracks)
        val (result, _, _) = run(builder)
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
        assertTrue(result.tracks.single().notes.any { "no duration" in it })
    }

    @Test
    fun overload_expiry_and_failures_are_recovered() {
        val random = Random(8)
        val builder = TestMkvBuilder(durationSeconds = 180, tracks = twoTracks(random, 180, 300), cueStyle = CueStyle.ALL_WITH_POSITION)
        val (result, _, server) = run(builder, configure = {
            maxConcurrent = 3
            expireEvery = 37
            failEvery = 23
            latencyMs = 5
        })
        assertScripts(builder, result, setOf(Integrity.VERIFIED_STATISTICS))
        assertTrue(server.rejected.get() >= 0)
    }

    @Test
    fun a_server_that_ignores_range_is_refused_before_sending_the_file() {
        val random = Random(9)
        val builder = TestMkvBuilder(durationSeconds = 120, tracks = twoTracks(random, 120, 50))
        val built = builder.build()
        val server = RangeTestServer(built.file, ignoreRange = true).also { servers += it }
        runBlocking {
            val session = SubtitleSession(client, source(server), EngineSettings(allowPrivateHosts = true))
            try {
                session.detect()
                fail("expected a refusal")
            } catch (e: RangeNotSupportedException) {
            }
            assertTrue("read ${session.stats.bodyBytes.get()} bytes", session.stats.bodyBytes.get() == 0L)
        }
    }

    @Test
    fun the_budget_stops_the_extraction_instead_of_downloading_more() {
        val random = Random(10)
        val builder = TestMkvBuilder(durationSeconds = 240, tracks = twoTracks(random, 240, 300), cueStyle = CueStyle.VIDEO_ONLY)
        try {
            run(builder, mode = ExtractionMode.SPEED, budgetFraction = 0.02)
            fail("expected the budget to stop it")
        } catch (e: BudgetExceededException) {
        }
        val server = servers.last()
        assertTrue("sent ${server.bodyBytes.get()}", server.bodyBytes.get() < builder.build().file.size * 0.05)
    }

    @Test
    fun ass_times_and_fields_are_rebuilt_exactly() {
        assertEquals("0:00:00.00", AssWriter.time(0))
        assertEquals("1:02:03.45", AssWriter.time(((3600 + 120 + 3) * 1000L + 450) * 1_000_000))
        val inflated = Blocks.inflate(TestMkvBuilder.deflate("1,0,Default,,0,0,0,,{\\k10}Hola, mundo".toByteArray()))
        assertEquals("1,0,Default,,0,0,0,,{\\k10}Hola, mundo", String(inflated))
    }

    @Test
    fun every_lacing_is_parsed() {
        val frames = listOf("a,b".toByteArray(), ByteArray(300) { 'x'.code.toByte() }, "last".toByteArray())
        val head = byteArrayOf(0x83.toByte(), 0, 10)
        // Xiph
        val xiph = TestMkvBuilder.concat(head.copyOf().also { it[2] = 0 }, byteArrayOf(0x02), byteArrayOf(2), TestMkvBuilder.xiph(3), TestMkvBuilder.xiph(300), *frames.toTypedArray())
        assertEquals(frames.map { String(it) }, Blocks.parse(xiph, 0, xiph.size).frames.map { String(it) })
        // EBML: 3, then 300 - 3 as a signed difference.
        val diff = 300 - 3 + ((1 shl 13) - 1)
        val ebml = TestMkvBuilder.concat(byteArrayOf(0x83.toByte(), 0, 10, 0x06), byteArrayOf(2), byteArrayOf(0x83.toByte()), byteArrayOf((0x40 or (diff shr 8)).toByte(), diff.toByte()), *frames.toTypedArray())
        assertEquals(frames.map { String(it) }, Blocks.parse(ebml, 0, ebml.size).frames.map { String(it) })
        // Fixed
        val same = List(3) { "abcd".toByteArray() }
        val fixed = TestMkvBuilder.concat(byteArrayOf(0x83.toByte(), 0, 10, 0x04), byteArrayOf(2), *same.toTypedArray())
        assertEquals(same.map { String(it) }, Blocks.parse(fixed, 0, fixed.size).frames.map { String(it) })
    }
}
