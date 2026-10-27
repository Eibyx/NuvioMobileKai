package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.NuvioWindowSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun homeSectionHorizontalPaddingForWidth(maxWidthDp: Float): Dp =
    when {
        maxWidthDp >= 1440f -> 32.dp
        maxWidthDp >= NuvioWindowSize.ExpandedWidth.value -> 28.dp
        maxWidthDp >= NuvioWindowSize.MediumWidth.value -> 24.dp
        else -> 16.dp
    }