package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkLogoTest {
    /** The matrix applied to 0–255 channels, clamped as the renderer does. */
    private fun apply(red: Int, green: Int, blue: Int): Triple<Int, Int, Int> {
        val m = lightnessInvertMatrix().values
        fun row(i: Int) = (m[i * 5] * red + m[i * 5 + 1] * green + m[i * 5 + 2] * blue + m[i * 5 + 4])
            .toInt().coerceIn(0, 255)
        return Triple(row(0), row(1), row(2))
    }

    @Test
    fun whiteLettersTurnDarkAndBlackOutlinesTurnLight() {
        assertEquals(Triple(0, 0, 0), apply(255, 255, 255))
        assertEquals(Triple(255, 255, 255), apply(0, 0, 0))
    }

    @Test
    fun aPinkWordStaysPink() {
        // The pink "Dress-Up" of My Dress-Up Darling: red stays well above green, so it is still pink.
        val (r, g, b) = apply(240, 110, 170)
        assertTrue(r - g >= 120, "red and green should stay as far apart as before: r=$r g=$g")
        assertTrue(r > b && b > g, "pink keeps its order of channels: r=$r g=$g b=$b")
    }
}
