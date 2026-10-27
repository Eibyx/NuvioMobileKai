package com.nuvio.app.core.ui

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerLayoutMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuvioWindowSizeTest {
    @Test
    fun phonesAreCompactEvenAtAHighDpi() {
        listOf(NuvioWindowSize(325.dp, 720.dp), NuvioWindowSize(360.dp, 800.dp), NuvioWindowSize(411.dp, 914.dp)).forEach { size ->
            assertEquals(NuvioWidthClass.Compact, size.widthClass, "$size")
            assertFalse(size.isTablet)
            assertFalse(size.isLargeScreen)
        }
    }

    @Test
    fun aPhoneSetToASmallestWidthOf600IsMediumInPortraitAndATabletBothWays() {
        val portrait = NuvioWindowSize(600.dp, 1333.dp)
        assertEquals(NuvioWidthClass.Medium, portrait.widthClass)
        assertEquals(NuvioHeightClass.Expanded, portrait.heightClass)
        assertTrue(portrait.isTablet)
        assertTrue(portrait.isLargeScreen)

        val landscape = NuvioWindowSize(1333.dp, 600.dp)
        assertEquals(NuvioWidthClass.Expanded, landscape.widthClass)
        assertEquals(NuvioHeightClass.Medium, landscape.heightClass)
        assertTrue(landscape.isTablet)
        assertTrue(landscape.isLargeScreen)
    }

    @Test
    fun aPhoneOnItsSideHasACompactHeight() {
        val size = NuvioWindowSize(914.dp, 411.dp)
        assertEquals(NuvioWidthClass.Expanded, size.widthClass)
        assertEquals(NuvioHeightClass.Compact, size.heightClass)
        assertFalse(size.isLargeScreen)
    }

    @Test
    fun theBoundariesAreMaterials() {
        assertEquals(NuvioWidthClass.Compact, NuvioWindowSize(599.dp, 900.dp).widthClass)
        assertEquals(NuvioWidthClass.Medium, NuvioWindowSize(600.dp, 900.dp).widthClass)
        assertEquals(NuvioWidthClass.Medium, NuvioWindowSize(839.dp, 900.dp).widthClass)
        assertEquals(NuvioWidthClass.Expanded, NuvioWindowSize(840.dp, 900.dp).widthClass)
        assertEquals(NuvioHeightClass.Compact, NuvioWindowSize(900.dp, 479.dp).heightClass)
        assertEquals(NuvioHeightClass.Medium, NuvioWindowSize(900.dp, 480.dp).heightClass)
    }

    @Test
    fun playerControlsFollowTheSizeClass() {
        val phoneLandscape = PlayerLayoutMetrics.fromWidth(768.dp)
        // A phone on its side, at any DPI, gets the phone-landscape controls.
        assertEquals(phoneLandscape, PlayerLayoutMetrics.fromWindow(914.dp, 411.dp))
        assertEquals(phoneLandscape, PlayerLayoutMetrics.fromWindow(720.dp, 325.dp))
        // Smallest width 600 on its side: the tablet controls.
        assertEquals(PlayerLayoutMetrics.fromWidth(1024.dp), PlayerLayoutMetrics.fromWindow(1333.dp, 600.dp))
        // Held upright: the compact ones.
        assertEquals(PlayerLayoutMetrics.fromWidth(411.dp), PlayerLayoutMetrics.fromWindow(411.dp, 914.dp))
    }
}
