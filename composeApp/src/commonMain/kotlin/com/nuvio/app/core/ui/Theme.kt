package com.nuvio.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.jetbrains_sans_bold
import nuvio.composeapp.generated.resources.jetbrains_sans_regular
import nuvio.composeapp.generated.resources.jetbrains_sans_semibold
import org.jetbrains.compose.resources.Font

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }
val LocalThemePalette = staticCompositionLocalOf { ThemeColors.White }

val MaterialTheme.themePalette: ThemeColorPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalThemePalette.current

val MaterialTheme.appTheme: AppTheme
    @Composable
    @ReadOnlyComposable
    get() = LocalAppTheme.current

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF111111) else Color(0xFFF5F7F8)

private fun buildLightColorScheme(palette: ThemeColorPalette) = lightColorScheme(
    primary = palette.secondary,
    onPrimary = palette.onSecondary,
    primaryContainer = palette.focusBackground,
    onPrimaryContainer = contentColorFor(palette.focusBackground),
    secondary = palette.secondaryVariant,
    onSecondary = palette.onSecondaryVariant,
    background = palette.background,
    onBackground = LightTextPrimary,
    surface = palette.backgroundElevated,
    onSurface = LightTextPrimary,
    surfaceVariant = palette.backgroundCard,
    onSurfaceVariant = LightTextMuted,
    outline = LightOutline,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private fun buildColorScheme(palette: ThemeColorPalette, amoled: Boolean = false) = darkColorScheme(
    primary = palette.secondary,
    onPrimary = palette.onSecondary,
    primaryContainer = palette.focusBackground,
    onPrimaryContainer = contentColorFor(palette.focusBackground),
    secondary = palette.secondaryVariant,
    onSecondary = palette.onSecondaryVariant,
    background = if (amoled) Color.Black else palette.background,
    onBackground = Color(0xFFF5F7F8),
    surface = palette.backgroundElevated,
    onSurface = Color(0xFFF5F7F8),
    surfaceVariant = palette.backgroundCard,
    onSurfaceVariant = Color(0xFF969CA3),
    outline = Color(0xFF252A2A),
    error = Color(0xFFE36A8A),
    onError = Color(0xFFFCE5EC),
)

private val JetBrainsSans: FontFamily
    @Composable
    get() = FontFamily(
        Font(Res.font.jetbrains_sans_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_regular, FontWeight.Normal, FontStyle.Normal),
    )

private fun nuvioTypography(fontFamily: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.pageDisplay,
        lineHeight = NuvioTokens.LineHeight.pageDisplay,
        fontWeight = FontWeight.Bold,
        letterSpacing = NuvioTokens.LetterSpacing.pageDisplay,
    ),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.headline,
        lineHeight = NuvioTokens.LineHeight.headline,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = NuvioTokens.LetterSpacing.headline,
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.titleSm,
        lineHeight = NuvioTokens.LineHeight.materialTitleLarge,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyLg,
        lineHeight = NuvioTokens.LineHeight.bodyMd,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyApp,
        lineHeight = NuvioTokens.LineHeight.bodyApp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyMd,
        lineHeight = NuvioTokens.LineHeight.bodyMd,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyMd,
        lineHeight = NuvioTokens.LineHeight.bodySm,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.labelSm,
        lineHeight = NuvioTokens.LineHeight.labelXs,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = NuvioTokens.LetterSpacing.label,
    ),
)

private fun nuvioTypeTokens(fontFamily: FontFamily): NuvioTypeScale = NuvioTypeScale(
    labelXs = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.labelXs,
        lineHeight = NuvioTokens.LineHeight.labelXs,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSm = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.labelSm,
        lineHeight = NuvioTokens.LineHeight.labelSm,
        fontWeight = FontWeight.SemiBold,
    ),
    bodySm = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodySm,
        lineHeight = NuvioTokens.LineHeight.bodySm,
        fontWeight = FontWeight.Normal,
    ),
    bodyMd = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyMd,
        lineHeight = NuvioTokens.LineHeight.bodyMd,
        fontWeight = FontWeight.Normal,
    ),
    bodyLg = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.bodyLg,
        lineHeight = NuvioTokens.LineHeight.bodyLg,
        fontWeight = FontWeight.Normal,
    ),
    titleSm = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.titleSm,
        lineHeight = NuvioTokens.LineHeight.titleSm,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMd = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.titleMd,
        lineHeight = NuvioTokens.LineHeight.titleMd,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLg = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.titleLg,
        lineHeight = NuvioTokens.LineHeight.titleLg,
        fontWeight = FontWeight.SemiBold,
    ),
    displaySm = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.displaySm,
        lineHeight = NuvioTokens.LineHeight.displaySm,
        fontWeight = FontWeight.Bold,
    ),
    displayMd = TextStyle(
        fontFamily = fontFamily,
        fontSize = NuvioTokens.Type.displayMd,
        lineHeight = NuvioTokens.LineHeight.displayMd,
        fontWeight = FontWeight.Bold,
    ),
)

// The system can scale text up to 200%; past this, fixed-height cards and the player chrome clip.
private const val MAX_SYSTEM_FONT_SCALE = 1.3f

private val NuvioRippleConfiguration = RippleConfiguration(
    color = Color.Black,
)

@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appearance: AppearanceMode = AppearanceMode.DARK,
    amoled: Boolean = false,
    customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    appFont: AppFont = AppFont.NUVIO,
    useSystemFontScale: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkPalette = remember(appTheme, customThemeColors) {
        ThemeColors.getColorPalette(appTheme, customThemeColors)
    }
    val dark = when (appearance) {
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = remember(darkPalette, dark) { if (dark) darkPalette else darkPalette.toLightPalette() }
    val colorScheme = if (dark) buildColorScheme(palette, amoled = amoled) else buildLightColorScheme(palette)
    val tokens = defaultNuvioThemeTokens(palette, amoled = amoled, colorScheme = colorScheme, dark = dark)

    val fontFamily = when (appFont) {
        AppFont.NUVIO -> JetBrainsSans
        AppFont.SYSTEM -> FontFamily.Default
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = if (useSystemFontScale) density.fontScale.coerceAtMost(MAX_SYSTEM_FONT_SCALE) else 1f,
        ),
        LocalNuvioThemeTokens provides tokens,
        LocalNuvioTypeScale provides nuvioTypeTokens(fontFamily),
        LocalRippleConfiguration provides NuvioRippleConfiguration,
        LocalAppTheme provides appTheme,
        LocalThemePalette provides palette,
        LocalNuvioAppearance provides NuvioAppearance(isDark = dark, darkPalette = darkPalette, amoled = amoled),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = nuvioTypography(fontFamily),
        ) {
            if (palette.animatedGradient) AccentGradientMotion.Run()
            StatusBarIcons(appDark = dark)
            SkeletonAnimationProvider(content = content)
        }
    }
}

/** Whether the app is drawing light or dark, and the dark palette that [NuvioDarkSurface] restores. */
internal data class NuvioAppearance(
    val isDark: Boolean,
    val darkPalette: ThemeColorPalette,
    val amoled: Boolean,
)

internal val LocalNuvioAppearance = staticCompositionLocalOf {
    NuvioAppearance(isDark = true, darkPalette = ThemeColors.White, amoled = false)
}

val MaterialTheme.isDarkAppearance: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalNuvioAppearance.current.isDark

/**
 * Strong text and icons on the theme's surfaces: the pure white the dark design was drawn with,
 * and the theme's text colour in the light appearance. For screens that used Color.White.
 */
val MaterialTheme.contentOnSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isDarkAppearance) Color.White else colorScheme.onSurface

/** A tint for borders, tracks and fills on the theme's surfaces: white on dark, black on light. */
@Composable
@ReadOnlyComposable
fun MaterialTheme.surfaceOverlay(alpha: Float): Color =
    (if (isDarkAppearance) Color.White else Color.Black).copy(alpha = alpha)

// Its own composable, so a dark surface coming and going recomposes only this.
@Composable
private fun StatusBarIcons(appDark: Boolean) {
    PlatformStatusBarAppearance(darkIcons = !appDark && DarkSurfaceStatusBar.requests == 0)
}

/** Dark surfaces under the status bar while the app is light; while any is shown its icons stay light. */
private object DarkSurfaceStatusBar {
    private val requestCount = mutableIntStateOf(0)
    val requests: Int get() = requestCount.intValue

    fun add() { requestCount.intValue += 1 }
    fun remove() { requestCount.intValue -= 1 }
}

/**
 * Draws [content] with the dark theme even while the app is light. The player and the artwork
 * heroes use it: their text and gradients are made for dark artwork.
 * [underStatusBar] keeps the status bar icons light while the surface is composed.
 */
@Composable
fun NuvioDarkSurface(
    underStatusBar: Boolean = false,
    content: @Composable () -> Unit,
) {
    val appearance = LocalNuvioAppearance.current
    if (appearance.isDark) {
        content()
        return
    }
    if (underStatusBar) {
        DisposableEffect(Unit) {
            DarkSurfaceStatusBar.add()
            onDispose { DarkSurfaceStatusBar.remove() }
        }
    }
    val palette = appearance.darkPalette
    val colorScheme = remember(palette, appearance.amoled) { buildColorScheme(palette, amoled = appearance.amoled) }
    val tokens = remember(palette, appearance.amoled, colorScheme) {
        defaultNuvioThemeTokens(palette, amoled = appearance.amoled, colorScheme = colorScheme, dark = true)
    }
    CompositionLocalProvider(
        LocalNuvioThemeTokens provides tokens,
        LocalThemePalette provides palette,
        LocalNuvioAppearance provides appearance.copy(isDark = true),
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
