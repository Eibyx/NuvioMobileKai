package com.nuvio.app.features.player.shaders

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.glass.GlassText
import com.nuvio.app.core.ui.glass.LargeTextContrast
import com.nuvio.app.core.ui.glass.NormalTextContrast
import com.nuvio.app.core.ui.glass.contrastRatio
import com.nuvio.app.core.ui.glass.glassContrast
import com.nuvio.app.core.ui.glass.worstGlassBackdrop
import com.nuvio.app.core.ui.nuvioThemeTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaderPanelTest {
    @Test
    fun panelGlassIsReadableOverTheWorstFrameInEveryTheme() {
        AppTheme.entries.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val colors = nuvioThemeTokens(theme, dark).colors
                val tint = shaderPanelTint(colors, dark)
                val backdrop = worstGlassBackdrop(dark)
                val panel = shaderPanelColors(colors, dark)
                val checks = shaderPanelTexts(colors) + listOf(
                    GlassText(panel.accent, LargeTextContrast),
                    GlassText(panel.warning, LargeTextContrast),
                )
                checks.forEach { text ->
                    val ratio = glassContrast(tint.copy(alpha = 1f), tint.alpha, backdrop, text.color)
                    assertTrue(ratio >= text.minimumContrast, "$theme dark=$dark alpha=${tint.alpha}: ${text.color} reaches $ratio, needs ${text.minimumContrast}")
                }
                // Text on the selected chip (accent fill).
                assertTrue(contrastRatio(panel.onAccent, panel.accent) >= NormalTextContrast, "$theme dark=$dark onAccent")
            }
        }
    }

    @Test
    fun combineTabRunsTheChainExactlyAsMade() {
        val settings = ShaderSettings(enabled = true, sharpen = SharpenExtra.Cas, color = ColorExtra.ChromaFromLuma)
        val inputs = ShaderRuntimeInputs(liveChain = listOf("krigbilateral", "artcnn_c4f16", "fsrcnnx_8"))
        val plan = ShaderController.plan(settings, inputs)
        // No extras, no SSimDownscaler added; only the first upscaler is forced.
        assertEquals(
            listOf(ChainEntry("krigbilateral"), ChainEntry("artcnn_c4f16", forced = true), ChainEntry("fsrcnnx_8")),
            plan.entries,
        )
        assertTrue(ShaderPresets.doublesTwice(inputs.liveChain!!))
        // Picking a preset leaves the Combine chain.
        val picked = ShaderController.plan(settings, inputs.copy(liveChain = null, presetOverride = ShaderPresets.FSR))
        assertEquals("fsr", picked.entries.first().shaderId)
    }

    @Test
    fun savedChainsAreExactToo() {
        val chain = ShaderChainDefinition("c1", "Mine", listOf("fsrcnnx_8", "ssimdownscaler"))
        val settings = ShaderSettings(enabled = true, chains = listOf(chain), sharpen = SharpenExtra.NvSharpen)
        val entries = ShaderPresets.resolve("chain:c1", settings)
        assertEquals(listOf(ChainEntry("fsrcnnx_8", true), ChainEntry("ssimdownscaler", true)), entries)
    }
}
