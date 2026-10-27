package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeBitmap(override val width: Int = 320, override val height: Int = 180) : ImageBitmap {
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = false
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
    override fun prepareToDraw() = Unit
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
}

/** The native thumbnailer as the controller sees it: asked positions, and a way to answer. */
private class FakeFeed : SeekPreviewFeed {
    val wanted = mutableListOf<Long>()
    var prepared = 0
    var closed = false
    lateinit var answer: (Long, ImageBitmap?, String?) -> Unit
    override fun want(positionMs: Long, showingMs: Long?) {
        wanted += positionMs
    }
    override fun prepare() {
        prepared++
    }
    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewControllerTest {
    private val episode = 24 * 60 * 1000L
    private val feeds = mutableListOf<FakeFeed>()

    private fun TestScope.native(): SeekPreviewController = SeekPreviewController(
        url = "https://cdn.example.com/dl/episode.mkv?token=secret",
        headers = mapOf("Authorization" to "Bearer secret"),
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        decodeDispatcher = StandardTestDispatcher(testScheduler),
        openFeed = { onThumbnail -> FakeFeed().also { it.answer = onThumbnail; feeds += it } },
        openSource = { error("ExoPlayer is only for HLS") },
        timeSource = testScheduler.timeSource,
    )

    @Test
    fun theMethodDependsOnTheStreamNotOnThePlayer() {
        assertEquals(SeekPreviewMethod.Native, seekPreviewMethodFor("https://torbox.example/dl/Show.S01E04.1080p.mkv?token=x"))
        assertEquals(SeekPreviewMethod.Native, seekPreviewMethodFor("http://192.168.1.5:8096/videos/1/stream.mp4"))
        assertEquals(SeekPreviewMethod.Native, seekPreviewMethodFor("file:///storage/emulated/0/Download/movie.mkv"))
        assertEquals(SeekPreviewMethod.ExoHls, seekPreviewMethodFor("https://cdn.example.com/hls/master.m3u8?sig=abc"))
        assertEquals(SeekPreviewMethod.ExoHls, seekPreviewMethodFor("https://cdn.example.com/live.M3U8"))
        assertEquals(SeekPreviewMethod.None, seekPreviewMethodFor("content://media/external/video/media/12"))
        assertEquals(SeekPreviewMethod.None, seekPreviewMethodFor("magnet:?xt=urn:btih:abcdef"))
    }

    @Test
    fun thePictureArrivesAndTheOldOneStaysUntilTheNextOne() = runTest {
        val controller = native()
        controller.request(60_000L, episode)
        runCurrent()
        val feed = feeds.single()
        assertEquals(listOf(60_000L), feed.wanted)
        assertNull(controller.frame)

        val first = FakeBitmap()
        feed.answer(60_000L, first, null)
        assertSame(first, controller.frame?.bitmap)

        // Moving on: the first picture stays while the next is made.
        advanceTimeBy(100L)
        controller.request(300_000L, episode)
        runCurrent()
        assertSame(first, controller.frame?.bitmap)
        val second = FakeBitmap()
        feed.answer(300_000L, second, null)
        assertSame(second, controller.frame?.bitmap)
        assertFalse(controller.isUnavailable)
        controller.dispose()
    }

    @Test
    fun aFastDragOnlyAsksForTheLatestPosition() = runTest {
        val controller = native()
        controller.request(10_000L, episode)
        runCurrent()
        // Ten positions inside 80 ms: only the last one is asked for afterwards.
        repeat(10) { step -> controller.request(20_000L + step * 30_000L, episode) }
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
        val feed = feeds.single()
        assertEquals(listOf(10_000L, 290_000L), feed.wanted)
        controller.dispose()
    }

    @Test
    fun aKnownThumbnailShowsAtOnceWithoutAskingAgain() = runTest {
        val controller = native()
        controller.request(120_000L, episode)
        runCurrent()
        val feed = feeds.single()
        val picture = FakeBitmap()
        feed.answer(120_000L, picture, null)
        controller.endScrub()
        controller.request(120_100L, episode) // the same bucket (1.44 s in a 24 min episode)
        runCurrent()
        assertSame(picture, controller.frame?.bitmap)
        assertEquals(listOf(120_000L), feed.wanted)
        controller.dispose()
    }

    @Test
    fun theConnectionClosesAWhileAfterReleaseAndReopens() = runTest {
        val controller = native()
        controller.request(60_000L, episode)
        runCurrent()
        feeds.single().answer(60_000L, FakeBitmap(), null)
        controller.endScrub()
        advanceTimeBy(SeekPreviewIdleReleaseMs - 1_000L)
        assertFalse(feeds.single().closed)
        advanceTimeBy(2_000L)
        assertTrue(feeds.single().closed)

        controller.request(600_000L, episode)
        runCurrent()
        assertEquals(2, feeds.size)
        assertEquals(listOf(600_000L), feeds.last().wanted)
        controller.dispose()
    }

    @Test
    fun withoutPicturesOnlyTheTimeIsLeft() = runTest {
        val controller = native()
        controller.request(60_000L, episode)
        runCurrent()
        val feed = feeds.single()
        feed.answer(-1L, null, "HTTP 403 for https://cdn.example.com/dl/episode.mkv?token=secret")
        assertTrue(controller.isUnavailable)
        assertNull(controller.frame)
        assertTrue(feed.closed)
        // Nothing more is asked for this stream.
        controller.request(90_000L, episode)
        runCurrent()
        assertEquals(1, feeds.size)
        controller.dispose()
    }

    @Test
    fun noPictureInTimeTurnsPicturesOff() = runTest {
        val controller = native()
        controller.request(60_000L, episode)
        runCurrent()
        advanceTimeBy(SeekPreviewFirstImageTimeoutMs + 100L)
        assertTrue(controller.isUnavailable)
        controller.dispose()
    }

    @Test
    fun aStreamWithoutAMethodStartsWithTheTimeOnly() = runTest {
        val controller = SeekPreviewController(
            url = "content://media/external/video/media/12",
            headers = emptyMap(),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            openFeed = { error("not for this stream") },
            openSource = { error("not for this stream") },
        )
        assertTrue(controller.isUnavailable)
        controller.request(60_000L, episode)
        runCurrent()
        assertNull(controller.frame)
        controller.dispose()
    }

    @Test
    fun theMemoryCacheIsBounded() {
        val cache = SeekPreviewFrameCache(maxBytes = 1024L * 1024L)
        repeat(50) { index -> cache.put(index * 2_000L, FakeBitmap(320, 180)) } // 230 KB each
        assertTrue(cache.totalBytes <= 1024L * 1024L, "${cache.totalBytes} bytes")
        assertEquals(4, cache.size)
        // The newest stay, the oldest went.
        assertNotNull(cache[98_000L])
        assertNull(cache[0L])
        assertEquals(98_000L, cache.nearest(99_000L)?.first)
    }

    @Test
    fun theLogNeverCarriesAddressesOrTokens() {
        val line = seekPreviewErrorForLog("HTTP 403 for https://cdn.example.com/dl/episode.mkv?token=secret&x=1 (auth=abc)")
        assertFalse("secret" in line, line)
        assertFalse("cdn.example.com" in line, line)
        assertFalse("abc" in line, line)
        assertTrue("HTTP 403" in line, line)
        assertEquals("unknown error", seekPreviewErrorForLog(null))
    }
}
