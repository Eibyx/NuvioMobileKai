package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.Canvas
import coil3.Image
import coil3.asImage
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackdropFocusStateTest {

    /** An image that cannot be turned into pixels, so the framing analysis fails. */
    private object UnreadableImage : Image {
        override val size: Long = 0
        override val width: Int = 0
        override val height: Int = 0
        override val shareable: Boolean = true
        override fun draw(canvas: Canvas) = Unit
    }

    @Test
    fun aFailedAnalysisIsNotRepeatedWhenTheImageComesBack(): Unit = runBlocking {
        val url = "https://example.test/failing-backdrop.jpg"
        val first = BackdropFocusState(url, BackdropFocusCache.get(url))
        assertFalse(first.isResolved)
        first.onImageLoaded(UnreadableImage)
        first.process()
        assertTrue(first.isResolved)

        // Scrolled away and back: the hero is recreated and must show at once.
        val again = BackdropFocusState(url, BackdropFocusCache.get(url))
        assertTrue(again.isResolved)
        assertEquals(0f, assertNotNull(again.profile).horizontalBias(0.5f))
    }

    @Test
    fun aLargeBackdropIsAnalyzedAndCached(): Unit = runBlocking {
        val url = "https://example.test/large-backdrop.jpg"
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val state = BackdropFocusState(url, null)
        state.onImageLoaded(bitmap.asImage())
        state.process()
        assertTrue(state.isResolved)
        assertNotNull(state.profile)
        assertTrue(BackdropFocusState(url, BackdropFocusCache.get(url)).isResolved)
    }

    @Test
    fun aSlowAnalysisKeepsTheFramingTheImageWasShownWith(): Unit = runBlocking {
        val url = "https://example.test/slow-backdrop.jpg"
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val state = BackdropFocusState(url, null)
        state.onImageLoaded(bitmap.asImage())
        state.stopHiding()
        state.process()
        assertTrue(state.isResolved)
        // Shown centered after the time limit: the late result is kept for next time only.
        assertEquals(null, state.profile)
        assertNotNull(BackdropFocusCache.get(url))
    }
}
