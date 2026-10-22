package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/** Dark status bar icons for light backgrounds, light ones otherwise. */
@Composable
internal expect fun PlatformStatusBarAppearance(darkIcons: Boolean)
