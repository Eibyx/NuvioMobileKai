package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.glass.CinematicVeilAlpha
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.settings.ThemeSettingsRepository

val LocalDynamicArtworkBackgroundActive = staticCompositionLocalOf { false }

/** True under [DynamicArtworkBackground] in [ArtworkBackgroundStyle.Glass]: the blurred artwork lies behind. */
val LocalArtworkGlassBackgroundActive = staticCompositionLocalOf { false }

/** What Home draws behind its content (Settings > Layout > Background). */
enum class ArtworkBackgroundStyle {
    /** The plain page colour. */
    Normal,
    /** A wash in the colours of the featured artwork. */
    DynamicColor,
    /** The featured artwork itself, blurred under a veil, like the detail page's cinematic background. */
    Glass,
    ;

    companion object {
        fun from(dynamicColor: Boolean, glass: Boolean): ArtworkBackgroundStyle = when {
            glass -> Glass
            dynamicColor -> DynamicColor
            else -> Normal
        }
    }
}

val LocalDynamicArtworkBackgroundColor = staticCompositionLocalOf<State<Color>?> { null }

private const val DOMINANT_BLEND_FRACTION = 0.42f

private const val COLOR_TRANSITION_MS = 600

@Composable
fun rememberDynamicArtworkBackgroundEnabled(): Boolean {
    val enabled by ThemeSettingsRepository.dynamicArtworkBackgroundEnabled.collectAsState()
    return enabled
}

@Composable
fun rememberArtworkBackgroundStyle(): ArtworkBackgroundStyle {
    val style by ThemeSettingsRepository.artworkBackgroundStyle.collectAsState()
    return style
}

@Composable
fun DynamicArtworkBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val style = rememberArtworkBackgroundStyle()
    if (style == ArtworkBackgroundStyle.Normal) {
        CompositionLocalProvider(LocalDynamicArtworkBackgroundActive provides false) {
            content()
        }
        return
    }
    if (style == ArtworkBackgroundStyle.Glass) {
        ArtworkGlassBackground(artworkUrl = artworkUrl, modifier = modifier, content = content)
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val baseColor = colorScheme.background
    val tint = rememberArtworkTint(artworkUrl)
    var lastTint by remember { mutableStateOf<Color?>(null) }
    val currentTint: Color? = when (tint) {
        ArtworkTint.Pending -> lastTint
        ArtworkTint.None -> null
        is ArtworkTint.Tinted -> tint.value
    }
    if (tint != ArtworkTint.Pending) lastTint = currentTint
    val targetColor = currentTint
        ?.let { baseColor.blendTowards(it, DOMINANT_BLEND_FRACTION) }
        ?: baseColor

    val washColor = animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS, easing = LinearOutSlowInEasing),
        label = "dynamic_artwork_wash_color",
    )

    Box(modifier = modifier.fillMaxSize().drawBehind { drawRect(washColor.value) }) {
        CompositionLocalProvider(
            LocalDynamicArtworkBackgroundActive provides true,
            LocalDynamicArtworkBackgroundColor provides washColor,
        ) {
            content()
        }
    }
}

private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = alpha + (target.alpha - alpha) * clamped,
    )
}

/** The artwork blurred behind everything, under the same veil as the detail page's cinematic background. */
@Composable
private fun ArtworkGlassBackground(
    artworkUrl: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxSize().background(baseColor)) {
        Crossfade(
            targetState = artworkUrl,
            modifier = Modifier.matchParentSize(),
            animationSpec = tween(durationMillis = COLOR_TRANSITION_MS),
            label = "artwork_glass_background",
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(16.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(modifier = Modifier.matchParentSize().background(baseColor.copy(alpha = CinematicVeilAlpha)))
        CompositionLocalProvider(
            LocalDynamicArtworkBackgroundActive provides true,
            LocalArtworkGlassBackgroundActive provides true,
        ) {
            content()
        }
    }
}

private val ArtworkFadeOutStops: Array<Pair<Float, Color>> = arrayOf(
    0.00f to Color.Black,
    0.50f to Color.Black,
    0.58f to Color.Black.copy(alpha = 0.88f),
    0.66f to Color.Black.copy(alpha = 0.64f),
    0.74f to Color.Black.copy(alpha = 0.34f),
    0.82f to Color.Black.copy(alpha = 0.12f),
    0.90f to Color.Black.copy(alpha = 0.03f),
    1.00f to Color.Transparent,
)

/**
 * Fades what is drawn out to transparent over its lower half, on the same curve as the light hero's
 * fade, so artwork melts into a background drawn behind it (the blurred, veiled artwork).
 */
fun Modifier.fadeOutTowardsBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(brush = Brush.verticalGradient(colorStops = ArtworkFadeOutStops), blendMode = BlendMode.DstIn)
    }
