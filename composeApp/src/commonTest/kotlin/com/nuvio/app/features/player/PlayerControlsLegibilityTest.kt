package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.glass.contrastRatio
import com.nuvio.app.core.ui.glass.glassContrast
import com.nuvio.app.core.ui.glass.relativeLuminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player's bottom bar labels and times and its top bar icons sit on glass over the video. On a
 * fully white frame they must still read: 4.5:1 for text, 3:1 for icons.
 */
class PlayerControlsLegibilityTest {
    private val whiteFrame = Color.White

    @Test
    fun textAndIconsReadOnAFullyWhiteFrame() {
        val tint = PlayerGlassSurfaceTint
        val text = glassContrast(PlayerGlassSmoke, tint.alpha, whiteFrame, Color.White.copy(alpha = 0.9f))
        val icon = glassContrast(PlayerGlassSmoke, tint.alpha, whiteFrame, Color.White)
        assertTrue(text >= 4.5, "labels reach $text at alpha ${tint.alpha}")
        assertTrue(icon >= 3.0, "icons reach $icon at alpha ${tint.alpha}")
        // The fallback (no live blur) is the same glass.
        assertEquals(tint, PlayerGlassFallback)
    }

    @Test
    fun theGlassIsTheLeastNeeded() {
        val alpha = PlayerGlassSurfaceTint.alpha
        val less = glassContrast(PlayerGlassSmoke, alpha - 0.01f, whiteFrame, Color.White.copy(alpha = 0.9f))
        assertTrue(less < 4.5, "alpha ${alpha - 0.01f} already reads ($less): the glass is darker than it needs to be")
    }

    @Test
    fun thePlayerStaysDark() {
        // Over a black frame the glass adds almost nothing; over white it is a dark smoke.
        assertTrue(relativeLuminance(PlayerGlassSmoke) < 0.01)
        val overBlack = PlayerGlassSurfaceTint.compositeOver(Color.Black)
        assertTrue(contrastRatio(Color.White, overBlack) >= 15.0)
        val overWhite = PlayerGlassSurfaceTint.compositeOver(whiteFrame)
        assertTrue(relativeLuminance(overWhite) < 0.2, "over white the glass reads as dark, luminance ${relativeLuminance(overWhite)}")
    }
}
