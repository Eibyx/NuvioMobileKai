package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width classes, as Material defines them: phones, large phones / small tablets, tablets. */
enum class NuvioWidthClass { Compact, Medium, Expanded }

/** Height classes: a phone on its side is Compact. */
enum class NuvioHeightClass { Compact, Medium, Expanded }

/**
 * The one size classification for every layout decision. A phone set to a smallest width of 600
 * (or a high DPI) is Medium in portrait and gets the same layout everywhere, instead of tablet
 * details next to a phone navigation bar.
 */
@Immutable
data class NuvioWindowSize(val width: Dp, val height: Dp) {
    val widthClass: NuvioWidthClass = when {
        width < MediumWidth -> NuvioWidthClass.Compact
        width < ExpandedWidth -> NuvioWidthClass.Medium
        else -> NuvioWidthClass.Expanded
    }

    val heightClass: NuvioHeightClass = when {
        height < MediumHeight -> NuvioHeightClass.Compact
        height < ExpandedHeight -> NuvioHeightClass.Medium
        else -> NuvioHeightClass.Expanded
    }

    /** Medium or Expanded width: tablet navigation, wider paddings, more columns. */
    val isTablet: Boolean get() = widthClass != NuvioWidthClass.Compact

    /**
     * At least 600 dp both ways (a tablet, or a phone set to a smallest width of 600), held either
     * way round: layouts that place panes side by side (details, library) and must not flip when
     * the device turns.
     */
    val isLargeScreen: Boolean get() = minOf(width, height) >= MediumWidth

    val isExpanded: Boolean get() = widthClass == NuvioWidthClass.Expanded

    val isLandscape: Boolean get() = width > height

    companion object {
        val MediumWidth = 600.dp
        val ExpandedWidth = 840.dp
        val MediumHeight = 480.dp
        val ExpandedHeight = 900.dp

        /** Readable line length for text blocks and forms on wide screens. */
        val MaxTextWidth = 720.dp
        /** Lists and settings pages: wider than text, never edge to edge on a tablet. */
        val MaxContentWidth = 840.dp
    }
}

/** The size of the app's window, from the platform window rather than one pane's constraints. */
@Composable
fun rememberNuvioWindowSize(): NuvioWindowSize {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { NuvioWindowSize(size.width.toDp(), size.height.toDp()) }
}
