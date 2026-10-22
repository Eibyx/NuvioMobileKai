package com.nuvio.app.core.ui

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

fun nuvioOverlayGradientBrush(): Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color(0xFF21113B),
        0.12f to Color(0xFF21113B),
        0.24f to Color(0xFF1A0E2F),
        0.34f to Color(0xFF130A23),
        0.44f to Color(0xFF0A060F),
        0.58f to Color(0xFF050408),
        0.64f to Color.Black,
        1f to Color.Black,
    ),
    start = Offset(0f, 0f),
    end = Offset(1000f, 1600f),
)

private val DarkSurfaceEdge = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)),
)

private val LightSurfaceEdge = Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.05f)),
)

/** The top-lit edge of sheets, dialogs and menus; a soft dark outline on light surfaces. */
internal val SurfaceEdge: Brush
    @Composable
    @ReadOnlyComposable
    get() = if (LocalNuvioAppearance.current.isDark) DarkSurfaceEdge else LightSurfaceEdge
