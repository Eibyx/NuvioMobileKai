package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Precision
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The detail hero and the small helper decodes of the same artwork (Cinematic's blurred
 * backdrop, Dominant Color's sample) must not share a memory cache entry: the small decode
 * replaced the hero's, and the hero disappeared and reloaded when it came back on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkAuxiliaryRequestsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fetches = AtomicInteger()

    /** Stands in for network + decoder: a 1920x1080 artwork, downsampled to the requested size. */
    private val artworkFetcher = Fetcher.Factory<Uri> { _, options, _ ->
        Fetcher {
            fetches.incrementAndGet()
            val requestedWidth = (options.size.width as? Dimension.Pixels)?.px ?: SourceWidth
            val width = requestedWidth.coerceAtMost(SourceWidth)
            val height = (width.toLong() * SourceHeight / SourceWidth).toInt().coerceAtLeast(1)
            ImageFetchResult(
                image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImage(),
                isSampled = width < SourceWidth,
                dataSource = DataSource.NETWORK,
            ) as FetchResult
        }
    }

    private val loader = ImageLoader.Builder(context)
        .components { add(artworkFetcher) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build() }
        .diskCache(null)
        .build()

    /** The detail hero's request as AsyncImage makes it: its laid-out size, inexact. */
    private fun heroRequest(url: String) = ImageRequest.Builder(context)
        .data(url)
        .size(1220, 1700)
        .precision(Precision.INEXACT)
        .build()

    private fun load(request: ImageRequest): SuccessResult = runBlocking {
        loader.execute(request) as SuccessResult
    }

    @Test
    fun theHeroStaysInMemoryAfterTheCinematicBackdropIsLoaded() {
        val url = "test://artwork/cinematic.jpg"
        val first = load(heroRequest(url))
        assertEquals(DataSource.NETWORK, first.dataSource)
        load(cinematicBackdropRequest(context, url))

        // Scrolled back up: the hero is recreated and must come from memory at full size.
        val again = load(heroRequest(url))
        assertEquals(DataSource.MEMORY_CACHE, again.dataSource)
        assertEquals(first.image.width, again.image.width)
        assertEquals(2, fetches.get())
    }

    @Test
    fun theHeroStaysInMemoryAfterTheDominantColorIsSampled() {
        val url = "test://artwork/dominant.jpg"
        val first = load(heroRequest(url))
        load(dominantColorSampleRequest(context, url))

        val again = load(heroRequest(url))
        assertEquals(DataSource.MEMORY_CACHE, again.dataSource)
        assertEquals(first.image.width, again.image.width)
    }

    @Test
    fun theHelperDecodesStillUseTheirOwnSize() {
        val url = "test://artwork/sizes.jpg"
        load(heroRequest(url))
        assertEquals(CinematicBackdropDecodeSize, load(cinematicBackdropRequest(context, url)).image.width)
        assertEquals(DominantColorSampleSize, load(dominantColorSampleRequest(context, url)).image.width)
    }

    /** The old requests (same URL, no extra): the small decode took the hero's entry. */
    @Test
    fun withoutTheExtraTheSmallDecodeReplacedTheHero() {
        val url = "test://artwork/old.jpg"
        load(heroRequest(url))
        load(
            ImageRequest.Builder(context)
                .data(url)
                .size(CinematicBackdropDecodeSize)
                .precision(Precision.EXACT)
                .build(),
        )
        assertNotEquals(DataSource.MEMORY_CACHE, load(heroRequest(url)).dataSource)
    }

    private companion object {
        const val SourceWidth = 1920
        const val SourceHeight = 1080
    }
}
