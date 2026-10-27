package com.nuvio.app.features.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.player.seekpreview.SeekPreviewController
import com.nuvio.app.features.player.seekpreview.SeekPreviewOverlay
import kotlin.math.roundToLong
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_seek_position
import org.jetbrains.compose.resources.stringResource

/** The player's progress bar, the same in every layout (new, classic, locked) and engine. */
internal object PlayerProgressBarSpec {
    val TouchHeight = 48.dp
    val IdleThickness = 4.dp
    val ActiveThickness = 8.dp
    val ThumbDiameter = 16.dp
    val ChapterGap = 2.dp
    const val AnimationMs = 150
    const val PlayedAlpha = 1f
    const val BufferedAlpha = 0.35f
    const val RemainingAlpha = 0.20f
}

/** Position ↔ x on a bar [width] wide; in RTL the start is on the right. */
internal object PlayerProgressGeometry {
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

    fun xFor(fraction: Float, width: Float, rtl: Boolean): Float {
        val x = fraction.coerceIn(0f, 1f) * width
        return if (rtl) width - x else x
    }

    fun fractionAt(x: Float, width: Float, rtl: Boolean): Float {
        if (width <= 0f) return 0f
        val f = (x / width).coerceIn(0f, 1f)
        return if (rtl) 1f - f else f
    }

    fun positionAt(x: Float, width: Float, durationMs: Long, rtl: Boolean): Long =
        if (durationMs <= 0L) 0L
        else (fractionAt(x, width, rtl) * durationMs).roundToLong().coerceIn(0L, durationMs)

    /** True when moving from [fromMs] to [toMs] passes the start of a chapter (other than the first). */
    fun crossesChapter(fromMs: Long, toMs: Long, chapters: List<PlayerChapter>): Boolean {
        if (fromMs == toMs) return false
        val low = minOf(fromMs, toMs)
        val high = maxOf(fromMs, toMs)
        return chapters.drop(1).any { it.startMs in (low + 1)..high }
    }
}

/**
 * - Idle: a 4 dp line with round ends. Played in the theme accent from the first second, loaded in
 *   35 % white, the rest in 20 % white. No dot at the end.
 * - Touched: the line grows to 8 dp and a 16 dp circle sits at the position (≈150 ms).
 * - 48 dp touch area: drag from anywhere, or tap to jump. A soft tick when crossing a chapter.
 * - Chapters are 2 dp cuts in the line.
 *
 * [lineCenter] is where the line sits from the top of the 48 dp area. [pressedPreview] draws the
 * touched look without a finger (screenshots).
 */
@Composable
internal fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chapters: List<PlayerChapter> = emptyList(),
    seekPreview: SeekPreviewController? = null,
    onTouchChange: (Boolean) -> Unit = {},
    lineCenter: Dp = PlayerProgressBarSpec.TouchHeight / 2,
    pressedPreview: Boolean = false,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val interactive = enabled && duration > 0L
    val accent = MaterialTheme.themePalette.secondary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val description = stringResource(Res.string.player_seek_position)

    var touching by remember { mutableStateOf(false) }
    val active = (touching && interactive) || pressedPreview
    ReportTimelineTouch(touching && interactive, onTouchChange)

    val thickness by animateDpAsState(
        targetValue = if (active) PlayerProgressBarSpec.ActiveThickness else PlayerProgressBarSpec.IdleThickness,
        animationSpec = tween(PlayerProgressBarSpec.AnimationMs),
        label = "progressThickness",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(PlayerProgressBarSpec.AnimationMs),
        label = "progressThumb",
    )

    val latestChapters by rememberUpdatedState(chapters)
    val latestOnScrubChange by rememberUpdatedState(onScrubChange)
    val latestOnScrubFinished by rememberUpdatedState(onScrubFinished)

    val shown = positionMs.coerceIn(0L, duration)
    val played = PlayerProgressGeometry.fraction(shown, duration)
    val buffered = maxOf(played, PlayerProgressGeometry.fraction(bufferedPositionMs, duration))
    val chapterFractions = remember(chapters, duration) { chapters.chapterMarkFractions(duration) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PlayerProgressBarSpec.TouchHeight)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = shown.toFloat(),
                    range = 0f..duration.coerceAtLeast(1L).toFloat(),
                )
                if (interactive) {
                    setProgress { target ->
                        val to = target.toLong().coerceIn(0L, duration)
                        latestOnScrubChange(to)
                        latestOnScrubFinished(to)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(duration, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        touching = true
                        var last = PlayerProgressGeometry.positionAt(down.position.x, size.width.toFloat(), duration, rtl)
                        latestOnScrubChange(last)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                change.consume()
                                val next = PlayerProgressGeometry.positionAt(change.position.x, size.width.toFloat(), duration, rtl)
                                if (next != last) {
                                    if (PlayerProgressGeometry.crossesChapter(last, next, latestChapters)) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    last = next
                                    latestOnScrubChange(next)
                                }
                            }
                            latestOnScrubFinished(last)
                        } finally {
                            touching = false
                        }
                    }
                },
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Offscreen, so the chapter cuts clear the line and nothing behind it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val width = size.width
            val line = thickness.toPx()
            val top = lineCenter.toPx() - line / 2f
            val clip = Path().apply {
                addRoundRect(RoundRect(0f, top, width, top + line, CornerRadius(line / 2f)))
            }
            fun span(from: Float, to: Float, color: Color, minWidth: Float = 0f) {
                if (to <= from) return
                val a = PlayerProgressGeometry.xFor(from, width, rtl)
                val b = PlayerProgressGeometry.xFor(to, width, rtl)
                var left = minOf(a, b)
                var right = maxOf(a, b)
                if (right - left < minWidth) {
                    if (rtl) left = right - minWidth else right = left + minWidth
                }
                drawRect(color, Offset(left, top), Size(right - left, line))
            }
            clipPath(clip) {
                // Side by side, not stacked, so each part shows its own alpha.
                span(buffered, 1f, Color.White.copy(alpha = PlayerProgressBarSpec.RemainingAlpha))
                span(played, buffered, Color.White.copy(alpha = PlayerProgressBarSpec.BufferedAlpha))
                // At least a pixel, so the first second shows (1 s of 20 min is under a pixel).
                span(0f, played, accent.copy(alpha = PlayerProgressBarSpec.PlayedAlpha), minWidth = 1.dp.toPx())
            }
            val gap = PlayerProgressBarSpec.ChapterGap.toPx()
            chapterFractions.forEach { f ->
                val x = PlayerProgressGeometry.xFor(f, width, rtl)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(x - gap / 2f, top - 1f),
                    size = Size(gap, line + 2f),
                    blendMode = BlendMode.Clear,
                )
            }
            if (thumbScale > 0f) {
                val radius = PlayerProgressBarSpec.ThumbDiameter.toPx() / 2f * thumbScale
                drawCircle(
                    color = accent,
                    radius = radius,
                    center = Offset(PlayerProgressGeometry.xFor(played, width, rtl), lineCenter.toPx()),
                )
            }
        }
        SeekPreviewOverlay(
            controller = seekPreview,
            active = active && !pressedPreview,
            positionMs = shown,
            durationMs = duration,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
