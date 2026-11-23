package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkLogoHaloTest {

    private fun solid(red: Int, green: Int, blue: Int) =
        IntArray(100) { (255 shl 24) or (red shl 16) or (green shl 8) or blue }

    @Test
    fun white_logo_gets_a_dark_halo_only_in_the_light_appearance() {
        val white = logoMeanLuminance(solid(255, 255, 255))!!
        assertEquals(Color.Black, logoHaloColor(white, darkAppearance = false))
        assertNull(logoHaloColor(white, darkAppearance = true))
    }

    @Test
    fun black_logo_gets_a_light_halo_only_in_the_dark_appearance() {
        val black = logoMeanLuminance(solid(10, 10, 10))!!
        assertEquals(Color.White, logoHaloColor(black, darkAppearance = true))
        assertNull(logoHaloColor(black, darkAppearance = false))
    }

    @Test
    fun a_strong_red_logo_stays_as_it_is_in_both_appearances() {
        val red = logoMeanLuminance(solid(220, 30, 40))!!
        assertNull(logoHaloColor(red, darkAppearance = false))
        assertNull(logoHaloColor(red, darkAppearance = true))
    }

    @Test
    fun transparent_pixels_are_ignored() {
        assertNull(logoMeanLuminance(IntArray(50)))
    }
}
