package com.nuvio.app.features.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.isDarkAppearance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
) {
    val state by remember {
        AppIconRepository.ensureLoaded()
        AppIconRepository.state
    }.collectAsStateWithLifecycle()
    val resource: DrawableResource = icon?.wordmarkResource
        ?: MaterialTheme.appTheme.wordmarkResource(state.selected)

    Crossfade(
        targetState = resource,
        animationSpec = tween(durationMillis = 350),
        label = "AppBrandWordmarkCrossfade",
        modifier = modifier,
    ) { targetResource ->
        if (MaterialTheme.isDarkAppearance) {
            Image(
                painter = painterResource(targetResource),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
            )
        } else {
            LightWordmark(
                painter = painterResource(targetResource),
                contentDescription = contentDescription,
                tintMark = targetResource == AppIconOption.GRAPHITE.wordmarkResource,
            )
        }
    }
}

// Every wordmark is the coloured mark on the left of this share of its width and white
// lettering on the right.
private const val WordmarkMarkFraction = 0.319f

/**
 * The wordmark on the light page: the mark keeps its colours and the white lettering is drawn in
 * the theme's text colour. [tintMark] also tints the mark, for the grey Graphite one.
 */
@Composable
private fun LightWordmark(
    painter: Painter,
    contentDescription: String?,
    tintMark: Boolean,
) {
    val textTint = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
    // Measured and laid out like the dark Image; the painter itself is drawn below in two parts.
    Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        alpha = 0f,
        modifier = Modifier.drawWithContent {
            val intrinsic = painter.intrinsicSize
            if (intrinsic.width <= 0f || intrinsic.height <= 0f) return@drawWithContent
            val scale = minOf(size.width / intrinsic.width, size.height / intrinsic.height)
            val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
            val split = drawn.width * WordmarkMarkFraction
            translate((size.width - drawn.width) / 2f, (size.height - drawn.height) / 2f) {
                clipRect(right = split) {
                    with(painter) { draw(drawn, colorFilter = if (tintMark) textTint else null) }
                }
                clipRect(left = split) {
                    with(painter) { draw(drawn, colorFilter = textTint) }
                }
            }
        },
    )
}
