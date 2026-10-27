package com.nuvio.app.features.player.seekpreview

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the preview card goes: over the finger, inside the screen. All in px. */
class SeekPreviewPlacementTest {
    private val box = 320f // 160 dp at xhdpi
    private val margin = 16f

    @Test
    fun centredOverTheFinger() {
        assertEquals(400f - 160f, seekPreviewLeft(anchorX = 400f, boxWidth = box, barLeftInWindow = 48f, windowWidth = 1830f, margin = margin))
    }

    @Test
    fun nearTheLeftEdgeItStopsAtTheScreenNotAtTheBar() {
        // The bar starts 48 px into the screen; the card may reach left of the bar, down to the margin.
        assertEquals(-32f, seekPreviewLeft(anchorX = 10f, boxWidth = box, barLeftInWindow = 48f, windowWidth = 1830f, margin = margin))
        assertEquals(margin, seekPreviewLeft(anchorX = 10f, boxWidth = box, barLeftInWindow = 0f, windowWidth = 1830f, margin = margin))
    }

    @Test
    fun nearTheRightEdgeItStopsAtTheScreen() {
        val left = seekPreviewLeft(anchorX = 1730f, boxWidth = box, barLeftInWindow = 48f, windowWidth = 1830f, margin = margin)
        assertEquals(1830f - margin - box - 48f, left)
    }

    @Test
    fun aScreenNarrowerThanTheCardCentresIt() {
        val left = seekPreviewLeft(anchorX = 100f, boxWidth = box, barLeftInWindow = 0f, windowWidth = 300f, margin = margin)
        assertEquals((margin + (300f - margin - box)) / 2f, left)
    }
}
