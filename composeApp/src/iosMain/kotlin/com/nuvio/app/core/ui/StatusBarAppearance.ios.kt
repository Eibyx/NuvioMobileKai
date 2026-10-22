package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The iOS status bar style comes from the hosting view controller (ContentView.swift).
@Composable
internal actual fun PlatformStatusBarAppearance(darkIcons: Boolean) = Unit

// The iOS window shows the Compose content edge to edge; there is no system navigation bar.
@Composable
internal actual fun PlatformWindowAppearance(mode: AppearanceMode, dark: Boolean, background: Color) = Unit
