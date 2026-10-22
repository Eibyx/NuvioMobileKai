package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark status bar icons for light backgrounds, light ones otherwise. */
@Composable
internal expect fun PlatformStatusBarAppearance(darkIcons: Boolean)

/**
 * The window behind the app, the system navigation bar and, where the platform allows, the next
 * launch's splash screen, following the appearance: [background] is the theme's page colour.
 */
@Composable
internal expect fun PlatformWindowAppearance(mode: AppearanceMode, dark: Boolean, background: Color)
