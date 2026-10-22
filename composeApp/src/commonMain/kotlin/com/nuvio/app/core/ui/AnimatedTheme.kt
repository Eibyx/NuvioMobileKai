package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.delay

/*
 * Animated gradient themes, ported from yesnt10's Enhanced fork (core/ui/AnimatedTheme.kt).
 *
 * Their palettes are ordinary accent gradients, so every surface luqman's theme system paints with
 * accentBrush() picks them up. While motion runs, accentBrush() samples the looped palette at a
 * moving phase. Only the composables and draw blocks that read the brush update, not the whole
 * tree, because the phase is read where the brush is built.
 */

/** Phase of the flowing accent gradient, from 0 to 1. */
internal object AccentGradientMotion {
    private const val LOOP_MILLIS = 12_000L
    private const val FRAME_INTERVAL_MILLIS = 33L

    private val phaseState = mutableFloatStateOf(0f)

    val phase: Float
        get() = phaseState.floatValue

    /** Moves the phase while in composition; several callers stay in step since it follows the frame clock. */
    @Composable
    fun Run() {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { frameMillis ->
                    phaseState.floatValue = (frameMillis % LOOP_MILLIS).toFloat() / LOOP_MILLIS
                }
                delay(FRAME_INTERVAL_MILLIS)
            }
        }
    }
}

/** The colours [accentBrush] draws: [ThemeColorPalette.accentGradient], flowing for animated themes. */
internal fun ThemeColorPalette.currentAccentGradient(): List<Color> =
    if (animatedGradient && accentGradient.size >= 2) {
        flowingColors(accentGradient, AccentGradientMotion.phase)
    } else {
        accentGradient
    }

private const val FLOW_SAMPLES = 8
private const val FLOW_SPAN = 0.6f

/** A window of [FLOW_SPAN] of the looped palette, starting at [phase]. */
private fun flowingColors(colors: List<Color>, phase: Float): List<Color> =
    List(FLOW_SAMPLES) { index ->
        sampleLoop(colors, phase + FLOW_SPAN * index / (FLOW_SAMPLES - 1))
    }

private fun sampleLoop(colors: List<Color>, phase: Float): Color {
    if (colors.size == 1) return colors.first()
    val position = normalizedPhase(phase) * colors.size
    val index = floor(position).toInt().coerceIn(0, colors.lastIndex)
    val nextIndex = (index + 1) % colors.size
    return smoothColorLerp(colors[index], colors[nextIndex], smoothStep(position - index))
}

private fun smoothStep(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

/** Blends in linear light so the colours between two stops do not go muddy. */
private fun smoothColorLerp(start: Color, end: Color, fraction: Float): Color {
    val amount = fraction.coerceIn(0f, 1f)
    fun channel(first: Float, second: Float): Float {
        val firstLinear = first.coerceIn(0f, 1f).pow(2.2f)
        val secondLinear = second.coerceIn(0f, 1f).pow(2.2f)
        return (firstLinear + (secondLinear - firstLinear) * amount)
            .coerceIn(0f, 1f)
            .pow(1f / 2.2f)
    }
    return Color(
        red = channel(start.red, end.red),
        green = channel(start.green, end.green),
        blue = channel(start.blue, end.blue),
        alpha = start.alpha + (end.alpha - start.alpha) * amount,
    )
}

private fun normalizedPhase(value: Float): Float = value - floor(value)
