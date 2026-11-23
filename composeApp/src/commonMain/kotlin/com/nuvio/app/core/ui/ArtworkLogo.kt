package com.nuvio.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.kyant.backdrop.isRenderEffectSupported
import com.nuvio.app.core.ui.background.softwareBitmap
import com.nuvio.app.features.details.components.loadedBackdropImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How large title logos are drawn, as a percentage of their usual size (Settings > Layout). */
object ArtworkLogoScale {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 10

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}

/**
 * A title logo in its own colours. When it would barely stand out from the page (a white logo in
 * the light appearance, a black one in the dark), a soft halo of the opposite tone goes behind it;
 * logos that already read well are drawn exactly as they come.
 */
@Composable
fun ArtworkLogoImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    onError: () -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    // Pixels are read to measure the logo, so it is decoded in memory the CPU can read.
    val request = remember(model, platformContext) {
        when (model) {
            is String -> ImageRequest.Builder(platformContext).data(model).softwareBitmap().build()
            else -> model
        }
    }
    val painter = rememberAsyncImagePainter(model = request, contentScale = contentScale)
    val state by painter.state.collectAsState()
    LaunchedEffect(state) {
        if (state is AsyncImagePainter.State.Error) onError()
    }

    val key = (model as? String) ?: model?.toString()
    var luminance by remember(key) { mutableStateOf(key?.let { logoLuminanceCache[it] }) }
    val success = state as? AsyncImagePainter.State.Success
    LaunchedEffect(success, key) {
        if (success == null || key == null || luminance != null) return@LaunchedEffect
        luminance = withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = loadedBackdropImageBitmap(success.result) ?: return@runCatching null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.readPixels(pixels)
                logoMeanLuminance(pixels)
            }.getOrNull()
        }?.also { value ->
            if (logoLuminanceCache.size > 256) logoLuminanceCache.remove(logoLuminanceCache.keys.first())
            logoLuminanceCache[key] = value
        }
    }

    val dark = MaterialTheme.isDarkAppearance
    val halo = luminance?.let { logoHaloColor(it, darkAppearance = dark) }
        ?.takeIf { isRenderEffectSupported() }

    Box(modifier = modifier, contentAlignment = alignment) {
        if (halo != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .blur(LogoHaloRadius, BlurredEdgeTreatment.Unbounded)
                    .alpha(if (dark) 0.7f else 0.55f),
                alignment = alignment,
                contentScale = contentScale,
                colorFilter = ColorFilter.tint(halo, BlendMode.SrcIn),
            )
        }
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            alignment = alignment,
            contentScale = contentScale,
        )
    }
}

private val LogoHaloRadius = 7.dp

private val logoLuminanceCache = mutableMapOf<String, Double>()

/** Mean relative luminance (0 black, 1 white) of the logo's opaque pixels; null when it has none. */
internal fun logoMeanLuminance(pixels: IntArray, step: Int = 5): Double? {
    var sum = 0.0
    var count = 0
    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        if ((pixel ushr 24) >= 160) {
            sum += relativeLuminance((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
            count++
        }
        index += step
    }
    return if (count == 0) null else sum / count
}

private fun relativeLuminance(red: Int, green: Int, blue: Int): Double {
    fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((c + 0.055) / 1.055))
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/**
 * The halo behind a logo of [luminance], or null when the logo already stands out: dark behind a
 * light logo on the light page, light behind a dark logo on the dark page.
 */
internal fun logoHaloColor(luminance: Double, darkAppearance: Boolean): Color? {
    val page = if (darkAppearance) 0.01 else 0.90
    val lighter = maxOf(luminance, page)
    val darker = minOf(luminance, page)
    val contrast = (lighter + 0.05) / (darker + 0.05)
    val needed = if (darkAppearance) 2.5 else 3.0
    if (contrast >= needed) return null
    return if (darkAppearance) Color.White else Color.Black
}
