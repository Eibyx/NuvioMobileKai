package com.nuvio.app.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import kotlin.math.max
import kotlin.math.min

/** The loaded image as a bitmap whose pixels can be read, or null where that is not possible. */
internal expect fun readableLoadedImageBitmap(result: SuccessResult): ImageBitmap?

/**
 * A title logo over the page background. Logos are mostly drawn for dark artwork, so in the light
 * appearance a logo that is nearly all white or grey is drawn in the theme's text colour instead;
 * coloured logos keep their colours.
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
    val light = !MaterialTheme.isDarkAppearance
    var paleLogo by remember(model) { mutableStateOf(false) }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        colorFilter = if (light && paleLogo) ColorFilter.tint(MaterialTheme.colorScheme.onBackground) else null,
        onSuccess = { state ->
            if (light) paleLogo = readableLoadedImageBitmap(state.result)?.isPaleNeutral() == true
        },
        onError = { onError() },
    )
}

private const val SAMPLE_GRID = 40
private const val PALE_MIN_LUMINANCE = 0.72f
private const val NEUTRAL_MAX_SATURATION = 0.22f

/** Whether the opaque pixels are, on average, light and colourless. */
internal fun ImageBitmap.isPaleNeutral(): Boolean {
    if (width <= 0 || height <= 0) return false
    val pixels = IntArray(width * height)
    runCatching { readPixels(pixels) }.getOrElse { return false }
    val stepX = max(1, width / SAMPLE_GRID)
    val stepY = max(1, height / SAMPLE_GRID)
    var count = 0
    var luminance = 0f
    var saturation = 0f
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = pixels[y * width + x]
            if ((pixel ushr 24) >= 128) {
                val red = (pixel shr 16 and 0xFF) / 255f
                val green = (pixel shr 8 and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                luminance += 0.2126f * red + 0.7152f * green + 0.0722f * blue
                saturation += if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
                count++
            }
            x += stepX
        }
        y += stepY
    }
    if (count < 8) return false
    return luminance / count >= PALE_MIN_LUMINANCE && saturation / count <= NEUTRAL_MAX_SATURATION
}
