package com.nuvio.app.features.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.player.PlayerProgressGeometry
import com.nuvio.app.features.player.formatPlaybackTime
import kotlin.math.roundToInt

internal val SeekPreviewWidth = 160.dp
internal val SeekPreviewHeight = 90.dp
private val SeekPreviewShape = RoundedCornerShape(12.dp)
private val ScreenMargin = 8.dp
private const val FadeInMs = 90
private const val FadeOutMs = 220

/**
 * Left edge of the preview, in the bar's own coordinates: centred on [anchorX] (the finger), but
 * never closer than [margin] to the window's edges. [barLeftInWindow] is where the bar starts.
 */
internal fun seekPreviewLeft(
    anchorX: Float,
    boxWidth: Float,
    barLeftInWindow: Float,
    windowWidth: Float,
    margin: Float,
): Float {
    val wanted = anchorX - boxWidth / 2f
    val minLeft = margin - barLeftInWindow
    val maxLeft = windowWidth - margin - boxWidth - barLeftInWindow
    return if (maxLeft < minLeft) (minLeft + maxLeft) / 2f else wanted.coerceIn(minLeft, maxLeft)
}

/**
 * Above the finger while the progress bar is dragged: a 16:9 card with the scene, the time and the
 * chapter. The time is there at once; the picture follows, and until it does the last one stays.
 * Without pictures for this stream only the time (and chapter) shows. It fades out on release.
 */
@Composable
internal fun SeekPreviewOverlay(
    controller: SeekPreviewController?,
    active: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    chapterTitle: String? = null,
    gap: Dp = 8.dp,
) {
    if (controller == null || durationMs <= 0L) return

    LaunchedEffect(controller, active, positionMs, durationMs) {
        if (active) controller.request(positionMs, durationMs)
    }
    LaunchedEffect(controller, active) {
        if (!active) controller.endScrub()
    }
    LaunchedEffect(controller) {
        controller.warmUp(positionMs, durationMs)
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    var barLeftInWindow by remember { mutableFloatStateOf(0f) }

    Layout(
        modifier = modifier.onGloballyPositioned { barLeftInWindow = it.positionInWindow().x },
        content = {
            AnimatedVisibility(
                visible = active,
                enter = fadeIn(tween(FadeInMs)),
                exit = fadeOut(tween(FadeOutMs)),
            ) {
                SeekPreviewCard(
                    frame = controller.frame,
                    pictures = !controller.isUnavailable,
                    time = formatPlaybackTime(positionMs),
                    chapterTitle = chapterTitle,
                )
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(Constraints())
        val width = constraints.maxWidth
        layout(width, constraints.maxHeight) {
            if (placeable == null) return@layout
            val fraction = PlayerProgressGeometry.fraction(positionMs, durationMs)
            val anchor = PlayerProgressGeometry.xFor(fraction, width.toFloat(), rtl)
            val left = seekPreviewLeft(
                anchorX = anchor,
                boxWidth = placeable.width.toFloat(),
                barLeftInWindow = barLeftInWindow,
                windowWidth = if (windowWidth > 0f) windowWidth else barLeftInWindow + width,
                margin = ScreenMargin.toPx(),
            )
            placeable.place(left.roundToInt(), -placeable.height - gap.roundToPx())
        }
    }
}

@Composable
internal fun SeekPreviewCard(
    frame: SeekPreviewFrame?,
    pictures: Boolean,
    time: String,
    chapterTitle: String?,
) {
    val timeStyle = MaterialTheme.nuvioTypeScale.labelSm.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val chapterStyle = MaterialTheme.nuvioTypeScale.labelSm.copy(fontSize = 11.sp)
    if (!pictures) {
        // No pictures for this stream: the time (and the chapter) alone.
        Column(
            modifier = Modifier
                .widthIn(max = SeekPreviewWidth)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(time, style = timeStyle, color = Color.White, maxLines = 1)
            if (!chapterTitle.isNullOrBlank()) {
                Text(chapterTitle, style = chapterStyle, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Box(
        modifier = Modifier
            .size(width = SeekPreviewWidth, height = SeekPreviewHeight)
            .clip(SeekPreviewShape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.55f), SeekPreviewShape),
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                .padding(horizontal = 8.dp)
                .padding(top = 10.dp, bottom = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (!chapterTitle.isNullOrBlank()) {
                Text(
                    chapterTitle,
                    style = chapterStyle,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Text(time, style = timeStyle, color = Color.White, maxLines = 1)
        }
    }
}
