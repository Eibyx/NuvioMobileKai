package com.nuvio.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.AppearanceMode
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerProgressBar
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * The player's progress bar, 360 dp wide over black, into composeApp/build/screenshots/progress-bar:
 * at 0:01, halfway, at the end, with chapters and while dragging. Each image is also checked
 * pixel by pixel (xhdpi: 1 dp = 2 px; the line's centre is 24 dp down the 48 dp touch area).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class PlayerProgressBarScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outputDir = File("build/screenshots/progress-bar").apply { mkdirs() }
    private val duration = 20 * 60 * 1000L
    private var accent = Color.Unspecified

    private fun render(
        name: String,
        positionMs: Long,
        bufferedMs: Long = positionMs,
        chapters: List<PlayerChapter> = emptyList(),
        dragging: Boolean = false,
    ): Bitmap {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                NuvioTheme(appTheme = AppTheme.CRIMSON, appearance = AppearanceMode.DARK) {
                    accent = androidx.compose.material3.MaterialTheme.themePalette.secondary
                    Box(Modifier.width(360.dp).background(Color.Black)) {
                        PlayerProgressBar(
                            positionMs = positionMs,
                            durationMs = duration,
                            bufferedPositionMs = bufferedMs,
                            onScrubChange = {},
                            onScrubFinished = {},
                            chapters = chapters,
                            pressedPreview = dragging,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(400L)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private val centre = 48 // px: 24 dp
    private fun Bitmap.at(xDp: Float, dyDp: Float = 0f): Color =
        Color(getPixel((xDp * 2).toInt().coerceIn(0, width - 1), centre + (dyDp * 2).toInt()))

    private fun Color.isAccent() = distance(this, accent) < 0.12f
    private fun Color.isBackground() = distance(this, Color.Black) < 0.06f
    private fun distance(a: Color, b: Color) =
        maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
    private fun Color.isWhiteAlpha(alpha: Float) = kotlin.math.abs(red - alpha) < 0.05f && kotlin.math.abs(blue - alpha) < 0.05f

    @Test
    fun atOneSecondThePlayedPartAlreadyShows() {
        val bitmap = render("01_one_second", positionMs = 1_000L, bufferedMs = 60_000L)
        assertTrue(bitmap.at(0.5f).isAccent(), "no accent at the start: ${bitmap.at(0.5f)}")
        // Then the loaded part (35 % white) and the rest (20 % white).
        assertTrue(bitmap.at(10f).isWhiteAlpha(0.35f), "loaded: ${bitmap.at(10f)}")
        assertTrue(bitmap.at(200f).isWhiteAlpha(0.20f), "rest: ${bitmap.at(200f)}")
        // 4 dp at rest: 3 dp above the centre is outside the line.
        assertTrue(bitmap.at(100f, dyDp = -3f).isBackground())
    }

    @Test
    fun halfway() {
        val bitmap = render("02_half", positionMs = duration / 2, bufferedMs = duration / 2 + 120_000L)
        assertTrue(bitmap.at(170f).isAccent())
        assertTrue(bitmap.at(186f).isWhiteAlpha(0.35f))
        assertTrue(bitmap.at(300f).isWhiteAlpha(0.20f))
        // A plain line, no handle while nobody touches it.
        assertTrue(bitmap.at(180f, dyDp = -5f).isBackground())
    }

    @Test
    fun atTheEndThereIsNoDot() {
        val bitmap = render("03_end", positionMs = duration)
        assertTrue(bitmap.at(355f).isAccent())
        // Material's stop indicator used to sit here; nothing above or below the line now.
        assertTrue(bitmap.at(357f, dyDp = -3f).isBackground())
        assertTrue(bitmap.at(357f, dyDp = 3f).isBackground())
    }

    @Test
    fun chaptersAreCutsInTheLine() {
        val chapters = listOf(
            PlayerChapter(startMs = 0L, title = "Prologue"),
            PlayerChapter(startMs = duration / 4, title = "Opening"),
            PlayerChapter(startMs = duration * 3 / 4, title = "Ending"),
        )
        val bitmap = render("04_chapters", positionMs = duration / 2, chapters = chapters)
        assertTrue(bitmap.at(90f).isBackground(), "no cut at 1/4: ${bitmap.at(90f)}")
        assertTrue(bitmap.at(270f).isBackground(), "no cut at 3/4: ${bitmap.at(270f)}")
        // Beside the cut the line goes on, and nothing is drawn over it.
        assertTrue(bitmap.at(88f).isAccent())
        assertTrue(bitmap.at(90f, dyDp = -3f).isBackground())
        assertTrue(bitmap.at(0f, dyDp = 0f).isAccent())
    }

    @Test
    fun whileDraggingTheLineGrowsAndTheCircleShows() {
        val bitmap = render("05_dragging", positionMs = duration / 3, dragging = true)
        // 8 dp line: 3 dp above the centre is the line.
        assertTrue(bitmap.at(60f, dyDp = -3f).isAccent())
        assertTrue(bitmap.at(250f, dyDp = -3f).isWhiteAlpha(0.20f))
        // 16 dp circle at a third of the bar (x = 120 dp): 6 dp above the centre is inside it.
        assertTrue(bitmap.at(120f, dyDp = -6f).isAccent())
        assertTrue(bitmap.at(140f, dyDp = -6f).isBackground())
    }
}
