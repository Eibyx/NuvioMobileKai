package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Position ↔ x on the player's progress bar. */
class PlayerProgressGeometryTest {
    private val twentyMinutes = 20 * 60 * 1000L
    private val width = 720f

    @Test
    fun fractionsOfTheEpisode() {
        assertEquals(0f, PlayerProgressGeometry.fraction(0L, twentyMinutes))
        assertEquals(0.5f, PlayerProgressGeometry.fraction(twentyMinutes / 2, twentyMinutes))
        assertEquals(1f, PlayerProgressGeometry.fraction(twentyMinutes, twentyMinutes))
        // Past the end or before the start stays on the bar; no duration yet is the start.
        assertEquals(1f, PlayerProgressGeometry.fraction(twentyMinutes + 5_000, twentyMinutes))
        assertEquals(0f, PlayerProgressGeometry.fraction(-1L, twentyMinutes))
        assertEquals(0f, PlayerProgressGeometry.fraction(10_000L, 0L))
        // The first second is above zero (drawn at least a pixel wide).
        assertTrue(PlayerProgressGeometry.fraction(1_000L, twentyMinutes) > 0f)
    }

    @Test
    fun xAndPositionAreInverse() {
        listOf(0L, 1_000L, 59_000L, twentyMinutes / 3, twentyMinutes / 2, twentyMinutes - 1_000L, twentyMinutes).forEach { position ->
            val x = PlayerProgressGeometry.xFor(PlayerProgressGeometry.fraction(position, twentyMinutes), width, rtl = false)
            val back = PlayerProgressGeometry.positionAt(x, width, twentyMinutes, rtl = false)
            // Within the time one pixel stands for.
            assertTrue(kotlin.math.abs(back - position) <= twentyMinutes / width.toLong(), "$position -> $x -> $back")
        }
        assertEquals(0f, PlayerProgressGeometry.xFor(0f, width, rtl = false))
        assertEquals(360f, PlayerProgressGeometry.xFor(0.5f, width, rtl = false))
        assertEquals(width, PlayerProgressGeometry.xFor(1f, width, rtl = false))
    }

    @Test
    fun touchesOutsideTheBarClampToItsEnds() {
        assertEquals(0L, PlayerProgressGeometry.positionAt(-40f, width, twentyMinutes, rtl = false))
        assertEquals(twentyMinutes, PlayerProgressGeometry.positionAt(width + 40f, width, twentyMinutes, rtl = false))
        assertEquals(0L, PlayerProgressGeometry.positionAt(300f, width, 0L, rtl = false))
        assertEquals(0f, PlayerProgressGeometry.fractionAt(10f, 0f, rtl = false))
    }

    @Test
    fun rightToLeftStartsOnTheRight() {
        assertEquals(width, PlayerProgressGeometry.xFor(0f, width, rtl = true))
        assertEquals(0f, PlayerProgressGeometry.xFor(1f, width, rtl = true))
        assertEquals(180f, PlayerProgressGeometry.xFor(0.75f, width, rtl = true))
        assertEquals(0L, PlayerProgressGeometry.positionAt(width, width, twentyMinutes, rtl = true))
        assertEquals(twentyMinutes, PlayerProgressGeometry.positionAt(0f, width, twentyMinutes, rtl = true))
        assertEquals(twentyMinutes / 4, PlayerProgressGeometry.positionAt(540f, width, twentyMinutes, rtl = true))
    }

    @Test
    fun crossingAChapterIsNoticed() {
        val chapters = listOf(
            PlayerChapter(startMs = 0L, title = "Prologue"),
            PlayerChapter(startMs = 90_000L, title = "Opening"),
            PlayerChapter(startMs = 180_000L, title = "Part A"),
        )
        assertTrue(PlayerProgressGeometry.crossesChapter(80_000L, 100_000L, chapters))
        assertTrue(PlayerProgressGeometry.crossesChapter(200_000L, 170_000L, chapters))
        assertTrue(PlayerProgressGeometry.crossesChapter(89_000L, 90_000L, chapters))
        assertFalse(PlayerProgressGeometry.crossesChapter(100_000L, 170_000L, chapters))
        assertFalse(PlayerProgressGeometry.crossesChapter(90_000L, 95_000L, chapters))
        // The first chapter's start is the episode's start, not a cut.
        assertFalse(PlayerProgressGeometry.crossesChapter(5_000L, 0L, chapters))
        assertFalse(PlayerProgressGeometry.crossesChapter(10_000L, 10_000L, chapters))
    }
}
