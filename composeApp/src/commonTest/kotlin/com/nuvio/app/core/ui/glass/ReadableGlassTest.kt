package com.nuvio.app.core.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.nuvioColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The glass of the episode cards, the episode finder bar and the stream cards must keep their text
 * readable over the worst artwork: a white picture in the dark appearance, a black one in the light.
 */
class ReadableGlassTest {
    private val appearances = listOf(true, false)

    @Test
    fun contrastMathMatchesWcag() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color.Gray, Color.Gray), 0.0001)
        // #767676 on white is the classic 4.54:1.
        assertEquals(4.54, contrastRatio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun detailCardGlassKeepsTextReadableOnTheWorstArtworkInEveryTheme() {
        AppTheme.entries.forEach { theme ->
            appearances.forEach { dark ->
                val colors = nuvioColorScheme(theme, dark)
                val base = glassBase(colors)
                val backdrop = worstGlassBackdrop(dark)
                val texts = detailCardGlassTexts(colors, dark)
                val alpha = minimumReadableAlpha(base, backdrop, texts)
                val label = "$theme ${if (dark) "dark" else "light"} alpha=$alpha"
                // Still glass: the secondary text is adjusted so 0.7 is enough.
                assertTrue(alpha <= GlassTargetAlpha, "$label is above $GlassTargetAlpha")
                texts.forEach { text ->
                    val ratio = glassContrast(base, alpha, backdrop, text.color)
                    assertTrue(ratio >= text.minimumContrast, "$label: ${text.color} reaches $ratio, needs ${text.minimumContrast}")
                }
                // The smallest alpha that does it: one step less fails.
                if (alpha > 0f) {
                    assertTrue(
                        texts.any { glassContrast(base, alpha - 0.01f, backdrop, it.color) < it.minimumContrast },
                        "$label is not the minimum",
                    )
                }
                // The finder bar's search field and chips sit on a surfaceVariant 45% fill over the glass.
                val glass = base.copy(alpha = alpha).compositeOver(backdrop)
                val control = colors.surfaceVariant.copy(alpha = 0.45f).compositeOver(glass)
                assertTrue(
                    contrastRatio(glassSecondaryText(colors, dark), control) >= NormalTextContrast,
                    "$label: finder field text reaches ${contrastRatio(glassSecondaryText(colors, dark), control)}",
                )
            }
        }
    }

    @Test
    fun detailControlGlassKeepsIconsAndLabelsReadable() {
        AppTheme.entries.forEach { theme ->
            appearances.forEach { dark ->
                val colors = nuvioColorScheme(theme, dark)
                val base = glassBase(colors)
                val backdrop = worstGlassBackdrop(dark)
                val texts = detailControlGlassTexts(colors)
                val alpha = minimumReadableAlpha(base, backdrop, texts)
                texts.forEach { assertTrue(glassContrast(base, alpha, backdrop, it.color) >= it.minimumContrast, "$theme dark=$dark") }
            }
        }
    }

    @Test
    fun streamGlassKeepsTextReadableOnTheWorstArtwork() {
        AppTheme.entries.forEach { theme ->
            appearances.forEach { dark ->
                val colors = nuvioColorScheme(theme, dark)
                val content = if (dark) Color(0xFFF5F7F8) else colors.onSurface
                val base = glassBase(colors)
                val backdrop = worstGlassBackdrop(dark)
                val texts = streamGlassTexts(colors, content)
                val alpha = minimumReadableAlpha(base, backdrop, texts)
                texts.forEach { text ->
                    assertTrue(glassContrast(base, alpha, backdrop, text.color) >= text.minimumContrast, "$theme dark=$dark")
                }
            }
        }
    }

    @Test
    fun theDefaultThemeNeedsNoNearlyOpaqueGlass() {
        appearances.forEach { dark ->
            val colors = nuvioColorScheme(AppTheme.WHITE, dark)
            val detail = minimumReadableAlpha(glassBase(colors), worstGlassBackdrop(dark), detailCardGlassTexts(colors, dark))
            val streams = minimumReadableAlpha(
                glassBase(colors),
                worstGlassBackdrop(dark),
                streamGlassTexts(colors, if (dark) Color(0xFFF5F7F8) else colors.onSurface),
            )
            println("Readable glass (${if (dark) "dark" else "light"}): detail cards and finder bar $detail, streams $streams")
            assertTrue(detail < 0.95f && streams < 0.95f)
        }
    }

    @Test
    fun secondaryTextStaysDimmerThanThePrimaryText() {
        AppTheme.entries.forEach { theme ->
            appearances.forEach { dark ->
                val colors = nuvioColorScheme(theme, dark)
                val glass = glassBase(colors).copy(alpha = GlassTargetAlpha).compositeOver(worstGlassBackdrop(dark))
                val secondary = glassSecondaryText(colors, dark)
                assertTrue(contrastRatio(secondary, glass) >= NormalTextContrast, "$theme dark=$dark")
                assertTrue(contrastRatio(secondary, glass) < contrastRatio(colors.onSurface, glass), "$theme dark=$dark: as strong as the title")
            }
        }
    }

    @Test
    fun secondaryTextOnTheCinematicVeilReadsWithoutAHeavierVeil() {
        AppTheme.entries.forEach { theme ->
            appearances.forEach { dark ->
                val colors = nuvioColorScheme(theme, dark)
                val veil = colors.background.copy(alpha = CinematicVeilAlpha).compositeOver(worstGlassBackdrop(dark))
                val secondary = veilSecondaryText(colors.onSurfaceVariant, colors.onSurface, colors.background, dark)
                val label = "$theme ${if (dark) "dark" else "light"}"
                assertTrue(contrastRatio(secondary, veil) >= NormalTextContrast, "$label: ${contrastRatio(secondary, veil)}")
                assertTrue(contrastRatio(secondary, veil) < contrastRatio(colors.onSurface, veil), "$label: as strong as the primary text")
                // Lighter in the dark appearance, darker in the light one.
                if (dark) assertTrue(relativeLuminance(secondary) >= relativeLuminance(colors.onSurfaceVariant), label)
                else assertTrue(relativeLuminance(secondary) <= relativeLuminance(colors.onSurfaceVariant), label)
            }
        }
        appearances.forEach { dark ->
            val colors = nuvioColorScheme(AppTheme.WHITE, dark)
            val veil = colors.background.copy(alpha = CinematicVeilAlpha).compositeOver(worstGlassBackdrop(dark))
            println(
                "Cinematic veil (${if (dark) "dark" else "light"}): secondary text " +
                    "${contrastRatio(colors.onSurfaceVariant, veil)} -> " +
                    "${contrastRatio(veilSecondaryText(colors.onSurfaceVariant, colors.onSurface, colors.background, dark), veil)}",
            )
        }
    }
}
