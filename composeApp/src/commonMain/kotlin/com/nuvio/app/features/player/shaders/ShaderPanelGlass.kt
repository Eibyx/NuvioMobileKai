package com.nuvio.app.features.player.shaders

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.NuvioColorTokens
import com.nuvio.app.core.ui.glass.GlassText
import com.nuvio.app.core.ui.glass.LargeTextContrast
import com.nuvio.app.core.ui.glass.NormalTextContrast
import com.nuvio.app.core.ui.glass.contrastRatio
import com.nuvio.app.core.ui.glass.minimumReadableAlpha
import com.nuvio.app.core.ui.glass.raisedTowards
import com.nuvio.app.core.ui.glass.worstGlassBackdrop

/** The text on the shader panel's glass: titles and secondary text, 4.5:1. */
fun shaderPanelTexts(colors: NuvioColorTokens): List<GlassText> = listOf(
    GlassText(colors.textPrimary, NormalTextContrast),
    GlassText(colors.textSecondary, NormalTextContrast),
)

/**
 * The shader panel's glass: the elevated surface at the lowest alpha that keeps its text readable
 * over the worst video frame (pure white in the dark appearance, black in the light one).
 */
fun shaderPanelTint(colors: NuvioColorTokens, dark: Boolean): Color {
    val base = colors.surfaceElevated.copy(alpha = 1f)
    return base.copy(alpha = minimumReadableAlpha(base, worstGlassBackdrop(dark), shaderPanelTexts(colors)))
}

/**
 * The panel's colors: accent (switches, checkboxes, the selected preset) and warning icons moved
 * towards the main text until they reach 3:1 on the glass over the worst frame, and the color on
 * the accent picked for 4.5:1. Some themes' accents (Violet) are too dark on their own surface.
 */
fun shaderPanelColors(colors: NuvioColorTokens, dark: Boolean): NuvioColorTokens {
    val glass = shaderPanelTint(colors, dark).compositeOver(worstGlassBackdrop(dark))
    val accent = colors.accent.copy(alpha = 1f).raisedTowards(colors.textPrimary, glass, LargeTextContrast)
    val warning = colors.warning.copy(alpha = 1f).raisedTowards(colors.textPrimary, glass, LargeTextContrast)
    val onAccent = listOf(colors.onAccent, Color.Black, Color.White)
        .firstOrNull { contrastRatio(it.compositeOver(accent), accent) >= NormalTextContrast }
        ?: listOf(Color.Black, Color.White).maxBy { contrastRatio(it, accent) }
    return colors.copy(accent = accent, warning = warning, onAccent = onAccent)
}
