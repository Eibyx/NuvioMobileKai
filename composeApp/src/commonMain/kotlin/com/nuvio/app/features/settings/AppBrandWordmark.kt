package com.nuvio.app.features.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.isDarkAppearance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.rememberResourceEnvironment

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
) {
    val resource = rememberAppBrandWordmarkResource(icon)
    val environment = rememberResourceEnvironment()

    Crossfade(
        targetState = resource,
        animationSpec = tween(durationMillis = 350),
        label = "AppBrandWordmarkCrossfade",
        modifier = modifier,
    ) { targetResource ->
        val bitmap by produceState(AppBrandWordmarkCache[targetResource], targetResource, environment) {
            if (value == null) value = AppBrandWordmarkCache.load(targetResource, environment)
        }
        val loaded = bitmap
        // The wordmark is decoded off the main thread (upstream); until then the resource painter.
        val painter = if (loaded != null) {
            remember(loaded) { BitmapPainter(loaded) }
        } else {
            painterResource(targetResource)
        }
        if (MaterialTheme.isDarkAppearance) {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
            )
        } else {
            LightWordmark(
                painter = painter,
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

@Composable
internal fun PreloadAppBrandWordmark() {
    val resource = rememberAppBrandWordmarkResource(icon = null)
    val environment = rememberResourceEnvironment()
    LaunchedEffect(resource, environment) {
        AppBrandWordmarkCache.load(resource, environment)
    }
}

@Composable
private fun rememberAppBrandWordmarkResource(icon: AppIconOption?): DrawableResource {
    val state by remember {
        AppIconRepository.ensureLoaded()
        AppIconRepository.state
    }.collectAsStateWithLifecycle()
    return icon?.wordmarkResource ?: MaterialTheme.appTheme.wordmarkResource(state.selected)
}

private object AppBrandWordmarkCache {
    private val bitmaps = MutableStateFlow<Map<DrawableResource, ImageBitmap>>(emptyMap())

    operator fun get(resource: DrawableResource): ImageBitmap? = bitmaps.value[resource]

    suspend fun load(resource: DrawableResource, environment: ResourceEnvironment): ImageBitmap {
        get(resource)?.let { return it }
        val bitmap = withContext(Dispatchers.Default) {
            getDrawableResourceBytes(environment, resource).decodeToImageBitmap()
        }
        bitmaps.update { cached -> cached + (resource to bitmap) }
        return bitmap
    }
}
