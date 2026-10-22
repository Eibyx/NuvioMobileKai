package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

internal val LightBackgroundBase = Color(0xFFF2F3F9)
internal val LightCardBase = Color(0xFFEBEDF6)
internal val LightTextPrimary = Color(0xFF15181C)
internal val LightTextSecondary = Color(0xFF3E454D)
internal val LightTextMuted = Color(0xFF5D646C)
internal val LightOutline = Color(0xFFD3D8DE)

// Accents are drawn on near-white surfaces, so they need more contrast than on the dark ones.
private const val ACCENT_MIN_CONTRAST = 3f
// Greys and whites (the White and Graphite themes) read as an accent only when nearly black.
private const val NEUTRAL_ACCENT_MIN_CONTRAST = 12f
private const val NEUTRAL_SATURATION = 0.12f

/**
 * The light version of a theme palette: the same accent (darkened just enough to stand out on
 * white, keeping the shape of luqman's accent gradient) over light backgrounds tinted by it.
 */
internal fun ThemeColorPalette.toLightPalette(): ThemeColorPalette {
    val minContrast = if (secondary.saturation() < NEUTRAL_SATURATION) NEUTRAL_ACCENT_MIN_CONTRAST else ACCENT_MIN_CONTRAST
    val accent = secondary.darkenedForWhite(minContrast)
    val accentVariant = secondaryVariant.darkenedForWhite(minContrast)
    // One darkening amount for every stop, set by the lightest, so the gradient keeps its shape.
    val gradientDarkening = accentGradient.maxOf { it.darkeningForWhite(minContrast) }
    return copy(
        secondary = accent,
        secondaryVariant = accentVariant,
        accentGradient = accentGradient.map { lerp(it, Color.Black, gradientDarkening) },
        nativeAccentHex = formatHexColor(accent.toArgb() and 0xFFFFFF),
        onSecondary = readableOn(accent),
        onSecondaryVariant = readableOn(accentVariant),
        focusRing = accent,
        focusBackground = lerp(LightBackgroundBase, accent, 0.14f),
        background = lerp(LightBackgroundBase, secondary, 0.025f),
        backgroundElevated = Color.White,
        backgroundCard = lerp(LightCardBase, secondary, 0.04f),
    )
}

/**
 * A brand or rating colour used as text or an icon on the theme background: unchanged when dark,
 * darkened just enough to read on white when light (IMDb yellow, Letterboxd green and the like).
 */
@Composable
@ReadOnlyComposable
fun Color.readableOnBackground(): Color =
    if (LocalNuvioAppearance.current.isDark) this else darkenedForWhite(ACCENT_MIN_CONTRAST)

private fun Color.darkenedForWhite(minContrast: Float): Color =
    lerp(this, Color.Black, darkeningForWhite(minContrast))

/** How far towards black this colour must move to reach [minContrast] against white. */
private fun Color.darkeningForWhite(minContrast: Float): Float {
    if (contrastWithWhite() >= minContrast) return 0f
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (lerp(this, Color.Black, mid).contrastWithWhite() >= minContrast) high = mid else low = mid
    }
    return high
}

private fun Color.contrastWithWhite(): Float = 1.05f / (luminance() + 0.05f)

private fun readableOn(background: Color): Color =
    if (background.luminance() > 0.4f) Color(0xFF111111) else Color.White

private fun Color.saturation(): Float {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    return if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
}
