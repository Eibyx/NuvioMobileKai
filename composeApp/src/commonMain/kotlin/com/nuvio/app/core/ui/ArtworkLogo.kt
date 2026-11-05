package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** How large title logos are drawn, as a percentage of their usual size (Settings > Layout). */
object ArtworkLogoScale {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 10

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}

/** A title logo, drawn exactly as it comes, in the light and the dark appearance alike. */
@Composable
fun ArtworkLogoImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    onError: () -> Unit = {},
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        onError = { onError() },
    )
}
