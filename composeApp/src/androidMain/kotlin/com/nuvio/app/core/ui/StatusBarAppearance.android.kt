package com.nuvio.app.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nuvio.app.R

@Composable
internal actual fun PlatformStatusBarAppearance(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        // Only the status bar; PlatformWindowAppearance sets the navigation bar.
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
    }
}

/** The dark navigation bar scrim MainActivity sets up with enableEdgeToEdge. */
internal const val DarkNavigationBarScrim: Int = 0xFF020404.toInt()

@Composable
internal actual fun PlatformWindowAppearance(mode: AppearanceMode, dark: Boolean, background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(mode, dark, background) {
        val activity = view.context.findActivity() ?: return@LaunchedEffect
        val window = activity.window
        // Dark keeps the resources it launched with; light uses the theme's page colour.
        val windowColor = if (dark) ContextCompat.getColor(activity, R.color.nuvio_background) else background.toArgb()
        window.setBackgroundDrawable(ColorDrawable(windowColor))
        AppearanceSettingsStorage.saveWindowBackground(windowColor)
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (dark) DarkNavigationBarScrim else background.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) applySplashTheme(activity, mode)
    }
}

/** The splash of the next launch: the manifest's dark one, or its light or follow-the-device variant. */
private fun applySplashTheme(activity: Activity, mode: AppearanceMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val themeId = runCatching {
        activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource
    }.getOrNull() ?: return
    val variants = SplashThemeVariants[themeId] ?: return
    val splashTheme = when (mode) {
        AppearanceMode.DARK -> Resources.ID_NULL
        AppearanceMode.LIGHT -> variants.first
        AppearanceMode.SYSTEM -> variants.second
    }
    runCatching { activity.splashScreen.setSplashScreenTheme(splashTheme) }
}

// Each launcher icon's splash style, with its light and follow-the-device variants.
private val SplashThemeVariants: Map<Int, Pair<Int, Int>> = mapOf(
    R.style.Theme_Nuvio_Splash to (R.style.Theme_Nuvio_Splash_Light to R.style.Theme_Nuvio_Splash_System),
    R.style.Theme_Nuvio_Splash_ArcticBlue to
        (R.style.Theme_Nuvio_Splash_ArcticBlue_Light to R.style.Theme_Nuvio_Splash_ArcticBlue_System),
    R.style.Theme_Nuvio_Splash_Emerald to
        (R.style.Theme_Nuvio_Splash_Emerald_Light to R.style.Theme_Nuvio_Splash_Emerald_System),
    R.style.Theme_Nuvio_Splash_RoseGold to
        (R.style.Theme_Nuvio_Splash_RoseGold_Light to R.style.Theme_Nuvio_Splash_RoseGold_System),
    R.style.Theme_Nuvio_Splash_Copper to
        (R.style.Theme_Nuvio_Splash_Copper_Light to R.style.Theme_Nuvio_Splash_Copper_System),
    R.style.Theme_Nuvio_Splash_Graphite to
        (R.style.Theme_Nuvio_Splash_Graphite_Light to R.style.Theme_Nuvio_Splash_Graphite_System),
)

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
