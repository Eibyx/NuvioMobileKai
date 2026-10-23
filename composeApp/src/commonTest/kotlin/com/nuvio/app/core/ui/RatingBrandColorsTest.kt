package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rating and brand colours used as text or icons must reach 3:1 on the theme background. */
class RatingBrandColorsTest {
    private val brands = mapOf(
        "IMDb" to Color(0xFFF5C518),
        "TMDB" to Color(0xFF01B4E4),
        "Trakt" to Color(0xFFED1C24),
        "Letterboxd" to Color(0xFF00E054),
        "MyAnimeList" to Color(0xFF2E51A2),
        "Rotten Tomatoes" to Color(0xFFFA320A),
        "Metacritic" to Color(0xFFFFCC33),
        "AniList" to Color(0xFF02A9FF),
        "Addon" to Color(0xFF71BDE8),
    )

    @Test
    fun brandsAreLightenedOnEveryDarkBackground() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { amoled ->
                val background = nuvioColorScheme(theme, dark = true, amoled = amoled).background
                brands.forEach { (name, color) ->
                    val shown = color.lightenedFor(background, ACCENT_MIN_CONTRAST)
                    val ratio = shown.contrastWith(background)
                    assertTrue(ratio >= ACCENT_MIN_CONTRAST, "$name on $theme amoled=$amoled: $ratio")
                    // Untouched when it already reads.
                    if (color.contrastWith(background) >= ACCENT_MIN_CONTRAST) assertEquals(color, shown, name)
                }
            }
        }
    }

    @Test
    fun myAnimeListBlueNeededLightening() {
        val background = nuvioColorScheme(AppTheme.WHITE, dark = true).background
        val mal = Color(0xFF2E51A2)
        assertTrue(mal.contrastWith(background) < ACCENT_MIN_CONTRAST)
        val shown = mal.lightenedFor(background, ACCENT_MIN_CONTRAST)
        println("MyAnimeList on dark: ${mal.contrastWith(background)} -> ${shown.contrastWith(background)}")
        // Still blue: only as much white as needed.
        assertTrue(shown.blue > shown.red && shown.blue > shown.green)
    }

    @Test
    fun brandsAreDarkenedOnWhite() {
        brands.forEach { (name, color) ->
            assertTrue(color.darkenedForWhite(ACCENT_MIN_CONTRAST).contrastWith(Color.White) >= ACCENT_MIN_CONTRAST, name)
        }
    }
}
