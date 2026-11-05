package com.nuvio.app.core.poster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkQualityUrlTest {
    private val tmdb = "https://image.tmdb.org/t/p/"

    @Test
    fun portraitHeroBackdropGetsTheOriginal() {
        // A phone in portrait: 1080 × 1436 px hero cropped from a 16:9 backdrop.
        assertEquals("${tmdb}original/a.jpg", upgradeArtworkUrl("${tmdb}w1280/a.jpg", 1080, 1436))
    }

    @Test
    fun backdropThatAlreadyCoversTheScreenIsKept() {
        assertNull(upgradeArtworkUrl("${tmdb}w1280/a.jpg", 1000, 560))
    }

    @Test
    fun posterInARowKeepsItsSize() {
        // A 110 dp card at 3x density.
        assertNull(upgradeArtworkUrl("${tmdb}w500/p.jpg", 330, 495))
    }

    @Test
    fun largePosterStepsUpOneSize() {
        assertEquals("${tmdb}w780/p.jpg", upgradeArtworkUrl("${tmdb}w500/p.jpg", 700, 1050))
    }

    @Test
    fun logosGoByWidthAndSkipToTheOriginal() {
        assertNull(upgradeArtworkUrl("${tmdb}w500/l.png", 480, 200))
        assertEquals("${tmdb}original/l.png", upgradeArtworkUrl("${tmdb}w500/l.png", 900, 300))
    }

    @Test
    fun originalAndOtherHostsAreLeftAlone() {
        assertNull(upgradeArtworkUrl("${tmdb}original/a.jpg", 4000, 3000))
        assertNull(upgradeArtworkUrl("https://example.com/w500/a.jpg", 4000, 3000))
        assertNull(upgradeArtworkUrl("https://images.metahub.space/background/medium/tt1/img", 4000, 3000))
    }

    @Test
    fun metahubPostersStepUp() {
        val medium = "https://images.metahub.space/poster/medium/tt1/img"
        assertNull(upgradeArtworkUrl(medium, 330, 495))
        assertEquals("https://images.metahub.space/poster/large/tt1/img", upgradeArtworkUrl(medium, 700, 1050))
        assertEquals(
            "https://images.metahub.space/poster/medium/tt1/img",
            upgradeArtworkUrl("https://images.metahub.space/poster/small/tt1/img", 450, 600),
        )
    }
}
