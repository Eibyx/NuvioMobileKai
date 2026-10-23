package com.nuvio.app.core.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.isDarkAppearance
import kotlin.math.pow

/**
 * True where the page shows artwork behind its content (the detail page's Cinematic and Dominant
 * colour backgrounds, the streams page's Cinematic one). Cards there use [ReadableGlass]; on a
 * plain page background they keep their solid fill.
 */
val LocalGlassOverArtwork = staticCompositionLocalOf { false }

/** WCAG minimums: normal text (dates, synopses, counters) and large or bold text (titles). */
const val NormalTextContrast = 4.5
const val LargeTextContrast = 3.0

/** A text colour drawn on the glass and the contrast it needs. */
@Immutable
data class GlassText(val color: Color, val minimumContrast: Double)

/** The tint and the no-blur fallback of a glass surface; both follow the same contrast rule. */
@Immutable
data class ReadableGlass(val tint: Color, val fallback: Color)

/** WCAG relative luminance of an sRGB colour (alpha ignored). */
fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG contrast ratio between two opaque colours. */
fun contrastRatio(first: Color, second: Color): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** What the reader sees: [tint] at [alpha] over [backdrop], then each text over that. */
fun glassContrast(tint: Color, alpha: Float, backdrop: Color, text: Color): Double {
    val surface = tint.copy(alpha = alpha).compositeOver(backdrop.copy(alpha = 1f))
    return contrastRatio(text.compositeOver(surface), surface)
}

/**
 * The smallest alpha of [tint] that keeps every [texts] readable over [backdrop], the worst
 * artwork that can sit behind the glass (white in the dark appearance, black in the light one).
 * Searched in steps of 0.01, so the result is the first alpha that passes.
 */
fun minimumReadableAlpha(tint: Color, backdrop: Color, texts: List<GlassText>): Float {
    val base = tint.copy(alpha = 1f)
    for (step in 0..100) {
        val alpha = step / 100f
        if (texts.all { glassContrast(base, alpha, backdrop, it.color) >= it.minimumContrast }) return alpha
    }
    return 1f
}

/** The artwork that makes glass hardest to read on: white in the dark appearance, black in the light one. */
fun worstGlassBackdrop(dark: Boolean): Color = if (dark) Color.White else Color.Black

/** The glass base colour: the theme's surface. */
fun glassBase(colors: ColorScheme): Color = colors.surface

/**
 * The episode cards' and the episode finder bar's glass: titles in onSurface, dates and synopses
 * in onSurfaceVariant. One value for both, so they look the same.
 */
fun detailCardGlassTexts(colors: ColorScheme): List<GlassText> = listOf(
    GlassText(colors.onSurface, LargeTextContrast),
    GlassText(colors.onSurfaceVariant, NormalTextContrast),
)

/** The detail page's round action buttons and the download button: icons and labels in onSurface. */
fun detailControlGlassTexts(colors: ColorScheme): List<GlassText> = listOf(
    GlassText(colors.onSurface, NormalTextContrast),
)

/** The stream cards' and filters' glass: names, descriptions and chip labels in [content]. */
fun streamGlassTexts(colors: ColorScheme, content: Color): List<GlassText> = listOf(
    GlassText(content, NormalTextContrast),
    GlassText(colors.onSurface, NormalTextContrast),
)

/**
 * Glass for [texts] in the current theme. Over artwork both the tint and the fallback get the
 * computed alpha; on a plain page the surface keeps [plainFill].
 */
@Composable
fun readableGlass(texts: List<GlassText>, plainFill: Color): ReadableGlass {
    if (!LocalGlassOverArtwork.current) return ReadableGlass(tint = plainFill, fallback = plainFill)
    val colors = MaterialTheme.colorScheme
    val dark = MaterialTheme.isDarkAppearance
    val base = glassBase(colors)
    val tint = remember(base, texts, dark) {
        base.copy(alpha = minimumReadableAlpha(base, worstGlassBackdrop(dark), texts))
    }
    return ReadableGlass(tint = tint, fallback = tint)
}

@Composable
fun detailCardGlass(plainFill: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)): ReadableGlass {
    val colors = MaterialTheme.colorScheme
    val texts = remember(colors.onSurface, colors.onSurfaceVariant) { detailCardGlassTexts(colors) }
    return readableGlass(texts, plainFill)
}

@Composable
fun detailControlGlass(plainFill: Color): ReadableGlass {
    val colors = MaterialTheme.colorScheme
    val texts = remember(colors.onSurface) { detailControlGlassTexts(colors) }
    return readableGlass(texts, plainFill)
}
