package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderScaleConditionTest {
    private val anime4kUpscale = "OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *"
    private val artCnn = "OUTPUT.w LUMA.w / 1.3 > OUTPUT.h LUMA.h / 1.3 > *"
    private val ravu = "HOOKED.w OUTPUT.w / 0.707106 < HOOKED.h OUTPUT.h / 0.707106 < *"
    private val autoDownscale = "OUTPUT.w NATIVE.w / 2.0 < OUTPUT.h NATIVE.h / 2.0 < * OUTPUT.w NATIVE.w / 1.2 > OUTPUT.h NATIVE.h / 1.2 > * *"
    private val fsrArea = "OUTPUT.w OUTPUT.h * LUMA.w LUMA.h * / 1.0 >"
    private val nvSharpen = "OUTPUT.w OUTPUT.h * LUMA.w LUMA.h * / 1.0 > ! OUTPUT.w OUTPUT.h * LUMA.w LUMA.h * / 1.0 < ! *"
    private val chroma = "CHROMA.w LUMA.w <"

    /** The POCO X7 Pro's 2712x1220 screen playing a video of [width]x[height]. */
    private fun sizes(width: Int, height: Int): Map<String, Double> {
        val factor = minOf(2712.0 / width, 1220.0 / height)
        val outW = width * factor
        val outH = height * factor
        return listOf("MAIN", "LUMA", "HOOKED", "NATIVE").flatMap { listOf("$it.w" to width.toDouble(), "$it.h" to height.toDouble()) }
            .toMap() + mapOf("OUTPUT.w" to outW, "OUTPUT.h" to outH, "CHROMA.w" to width / 2.0, "CHROMA.h" to height / 2.0)
    }

    private fun runs(expression: String, width: Int, height: Int) =
        ShaderScaleCondition.evaluate(expression, sizes(width, height)) == true

    @Test
    fun upscaleThresholdsAreRelaxedToOne() {
        assertEquals("OUTPUT.w MAIN.w / 0.999 > OUTPUT.h MAIN.h / 0.999 > *", ShaderScaleCondition.relaxExpression(anime4kUpscale))
        assertEquals("OUTPUT.w LUMA.w / 0.999 > OUTPUT.h LUMA.h / 0.999 > *", ShaderScaleCondition.relaxExpression(artCnn))
        assertEquals("HOOKED.w OUTPUT.w / 1.001 < HOOKED.h OUTPUT.h / 1.001 < *", ShaderScaleCondition.relaxExpression(ravu))
    }

    @Test
    fun otherConditionsStayAsTheyAre() {
        listOf(fsrArea, nvSharpen, chroma, "NATIVE_CROPPED.h POSTKERNEL.h >", "HOOKED.w OUTPUT.w < HOOKED.h OUTPUT.h < *").forEach {
            assertEquals(it, ShaderScaleCondition.relaxExpression(it), it)
        }
    }

    @Test
    fun onlyTheWhenLineChanges() {
        val shader = """
            //!DESC Anime4K-v4.0-Upscale-CNN-x2-(M)-Conv-4x3x3x3
            //!HOOK MAIN
            //!WHEN $anime4kUpscale
            vec4 hook() { return vec4(1.200); }
        """.trimIndent()
        val forced = ShaderScaleCondition.relaxUpscaleThresholds(shader)
        assertEquals(shader.replace("/ 1.200 >", "/ 0.999 >"), forced)
        assertTrue(forced.contains("vec4(1.200)"))
        assertTrue(ShaderScaleCondition.hasUpscaleThreshold(shader))
        assertFalse(ShaderScaleCondition.hasUpscaleThreshold(forced))
    }

    @Test
    fun forcedVariantRunsAt1080pOnThePhoneButNeverAt4k() {
        // 1080p on a 1220 px tall screen: factor 1.13, below every original threshold.
        listOf(anime4kUpscale, artCnn, ravu).forEach { original ->
            val forced = ShaderScaleCondition.relaxExpression(original)
            assertFalse(runs(original, 1920, 1080), original)
            assertTrue(runs(forced, 1920, 1080), forced)
            // 720p already runs without forcing.
            assertTrue(runs(original, 1280, 720), original)
            // 4K is larger than the screen: neither variant runs.
            assertFalse(runs(original, 3840, 2160), original)
            assertFalse(runs(forced, 3840, 2160), forced)
            // A video exactly the screen's size (factor 1.0) runs forced.
            assertTrue(runs(forced, 2712, 1220), forced)
        }
    }

    @Test
    fun evaluatorFollowsMpv() {
        assertEquals(true, ShaderScaleCondition.evaluate("2 1 >", emptyMap()))
        assertEquals(false, ShaderScaleCondition.evaluate("2 1 > !", emptyMap()))
        assertEquals(true, ShaderScaleCondition.evaluate("6 3 / 2 =", emptyMap()))
        assertEquals(null, ShaderScaleCondition.evaluate("UNKNOWN.w 1 >", emptyMap()))
        assertEquals(null, ShaderScaleCondition.evaluate("1 >", emptyMap()))
        assertTrue(runs(chroma, 1920, 1080))
    }
}
