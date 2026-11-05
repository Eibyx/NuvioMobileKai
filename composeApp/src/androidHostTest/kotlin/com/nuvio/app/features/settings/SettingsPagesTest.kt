package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsPagesTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        ThemeSettingsStorage.initialize(context)
        ThemeSettingsRepository.clearLocalState()
        ThemeSettingsRepository.ensureLoaded()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
        ThemeSettingsRepository.clearLocalState()
    }

    @Test
    fun phoneRootRoutesLayoutAndPlayback() {
        var opened: SettingsPage? = null
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    settingsRootContent(isTablet = false, onPageClick = { opened = it })
                }
            }
        }
        compose.onNodeWithText("Layout").performScrollTo().performClick()
        assertEquals(SettingsPage.Layout, opened)
        compose.onNodeWithText("Playback").performScrollTo().performClick()
        assertEquals(SettingsPage.Playback, opened)
        compose.onNodeWithText("Tracking").performScrollTo().performClick()
        assertEquals(SettingsPage.TraktAuthentication, opened)
    }

    @Test
    fun tabletServicesGroupContainsIntegrationsAndTracking() {
        var opened: SettingsPage? = null
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    settingsRootContent(
                        isTablet = true,
                        category = SettingsCategory.Services,
                        onPageClick = { opened = it },
                    )
                }
            }
        }
        compose.onNodeWithText("Integrations").assertIsDisplayed()
        compose.onNodeWithText("Tracking").performClick()
        assertEquals(SettingsPage.TraktAuthentication, opened)
        compose.onNodeWithText("Appearance").assertDoesNotExist()
        compose.onNodeWithText("Playback").assertDoesNotExist()
    }

    @Test
    fun layoutKeepsItsChildPagesAndMobileNavigationControls() {
        var opened = ""
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    layoutSettingsContent(
                        isTablet = false,
                        liquidGlassNativeTabBarSupported = false,
                        onHomescreenClick = { opened = "home" },
                        onMetaScreenClick = { opened = "details" },
                        onStreamsClick = { opened = "streams" },
                        onCollectionsClick = { opened = "collections" },
                        onContinueWatchingClick = { opened = "continue" },
                        onPosterCustomizationClick = { opened = "posters" },
                    )
                }
            }
        }
        mapOf(
            "Home Layout" to "home",
            "Collections" to "collections",
            "Continue Watching" to "continue",
            "Poster Card Style" to "posters",
            "Streams" to "streams",
            "Detail Page" to "details",
        ).forEach { (label, destination) ->
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
            assertEquals(destination, opened)
        }
        compose.onNode(hasText("Navigation Bar") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Classic").performScrollTo().performClick()
        assertEquals(NavBarStyle.CLASSIC, ThemeSettingsRepository.navBarStyle.value)
        compose.runOnIdle { ThemeSettingsRepository.onProfileChanged() }
        assertEquals(NavBarStyle.CLASSIC, ThemeSettingsRepository.navBarStyle.value)
    }

    /** Back sits at the top of the list, out of composition once a long page is scrolled. */
    private fun goBack() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Back").performClick()
    }

    @Test
    fun playbackNavigationKeepsMobileControlsAndSavesTheirChanges() {
        val page = mutableStateOf(SettingsPage.Playback)
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (page.value != SettingsPage.Playback) {
                        item {
                            Button(onClick = { page.value = page.value.previousPage()!! }) { Text("Back") }
                        }
                    }
                    playbackSettingsContent(false, page.value) { page.value = it }
                }
            }
        }
        compose.onNodeWithText("Player interface").performScrollTo().performClick()
        compose.onNodeWithText("Touch Gestures").performScrollTo().performClick()
        assertFalse(PlayerSettingsRepository.uiState.value.touchGesturesEnabled)
        assertEquals(false, PlayerSettingsStorage.loadTouchGesturesEnabled())
        goBack()
        compose.onNodeWithText("Audio").performScrollTo().performClick()
        compose.onNodeWithText("Preferred Audio Language").performClick()
        compose.onNodeWithText("Original language").performClick()
        assertEquals("original", PlayerSettingsRepository.uiState.value.preferredAudioLanguage)
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        assertFalse(PlayerSettingsRepository.uiState.value.touchGesturesEnabled)
        assertEquals("original", PlayerSettingsRepository.uiState.value.preferredAudioLanguage)
        goBack()
        compose.onNodeWithText("Player").performScrollTo().performClick()
        compose.onNodeWithText("Auto-switch engine on playback error").performScrollTo().performClick()
        assertFalse(PlayerSettingsRepository.uiState.value.autoSwitchPlaybackEngineOnError)
        assertEquals(false, PlayerSettingsStorage.loadAutoSwitchPlaybackEngineOnError())
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        assertFalse(PlayerSettingsRepository.uiState.value.autoSwitchPlaybackEngineOnError)
        compose.onNodeWithText("Playback Engine").performClick()
        compose.onNodeWithText("libmpv").performClick()
        assertEquals(AndroidPlaybackEngine.Libmpv, PlayerSettingsRepository.uiState.value.androidPlaybackEngine)
        goBack()
        // mpv has its own buffer settings, so the page stays.
        compose.onNodeWithText("Buffer and cache").performScrollTo().assertExists()
    }

    @Test
    fun mpvBufferShowsWithMpvAndKeepsItsLimits() {
        PlayerSettingsRepository.setAndroidPlaybackEngine(AndroidPlaybackEngine.Libmpv)
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    playbackSettingsContent(false, SettingsPage.PlaybackBuffer) {}
                }
            }
        }
        // With libmpv only mpv's buffer shows, not ExoPlayer's.
        compose.onNodeWithText("ExoPlayer Native Memory (Experimental)").assertDoesNotExist()
        compose.onNodeWithText("Custom mpv buffer").performScrollTo().performClick()
        assertTrue(PlayerSettingsRepository.uiState.value.libmpvCustomBufferEnabled)
        assertEquals(true, PlayerSettingsStorage.loadLibmpvCustomBufferEnabled())
        compose.onNodeWithText("Ahead (maximum size)", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep the buffer on storage").performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            PlayerSettingsRepository.setLibmpvForwardBufferMaxMb(1536)
            PlayerSettingsRepository.setLibmpvBackBufferMb(5000)
        }
        assertEquals(1536, PlayerSettingsStorage.loadLibmpvForwardBufferMaxMb())
        // In RAM, behind stops at 1024 MB.
        assertEquals(1024, PlayerSettingsRepository.uiState.value.libmpvBackBufferMb)

        compose.onNodeWithText("Keep the buffer on storage").performScrollTo().performClick()
        compose.runOnIdle { PlayerSettingsRepository.setLibmpvForwardBufferMaxMb(9000) }
        assertEquals(9000, PlayerSettingsRepository.uiState.value.libmpvForwardBufferMaxMb)

        // Back to RAM: the size comes down to the RAM limit, and stays after a reload.
        compose.onNodeWithText("Keep the buffer on storage").performScrollTo().performClick()
        assertEquals(2048, PlayerSettingsRepository.uiState.value.libmpvForwardBufferMaxMb)
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        assertEquals(2048, PlayerSettingsRepository.uiState.value.libmpvForwardBufferMaxMb)
        assertFalse(PlayerSettingsRepository.uiState.value.libmpvBufferOnDisk)
    }

    @Test
    fun typedNumbersAreCheckedAgainstTheRange() {
        assertEquals("1536", cleanNumberInput("1,536 MB"))
        assertEquals(1536, parseNumberInput("1536", 16..2048))
        assertEquals(null, parseNumberInput("9000", 16..2048))
        assertEquals(9000, parseNumberInput("9000", 16..65536))
        assertEquals(null, parseNumberInput("", 16..2048))
    }

    @Test
    fun assSubtitlesHaveOneSwitchAndNoRenderModes() {
        PlayerSettingsRepository.setUseLibass(true)
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    playbackSettingsContent(false, SettingsPage.PlaybackSubtitles) {}
                }
            }
        }
        // Kai renders ASS with libmpv's libass alone: one switch, no render mode or scale.
        compose.onNodeWithText("ASS Render Mode").assertDoesNotExist()
        compose.onNodeWithText("Libass Render Mode").assertDoesNotExist()
        compose.onNodeWithText("Subtitle Render Resolution").assertDoesNotExist()
        compose.runOnIdle { PlayerSettingsRepository.setUseLibass(false) }
        assertEquals(false, PlayerSettingsRepository.uiState.value.useLibass)
    }

    @Test
    fun externalPlayersStillDisableMobileGestures() {
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { PlaybackInterfaceSettings(false, PlayerSettingsUiState(externalPlayerEnabled = true)) }
                }
            }
        }
        compose.onNodeWithText("Touch Gestures").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hold To Speed").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun searchOpensTheMovedControlsAndFiltersUnsupportedBufferOptions() {
        var entries = emptyList<SettingsSearchEntry>()
        compose.setContent {
            NuvioTheme {
                entries = settingsSearchEntries(
                    isTablet = false,
                    pluginsEnabled = true,
                    supportersContributorsPageEnabled = true,
                    accountDeletionEnabled = true,
                    personalMediaAddonCopyEnabled = true,
                    liquidGlassNativeTabBarSupported = true,
                    switchProfileAvailable = true,
                    checkForUpdatesAvailable = true,
                )
            }
        }
        compose.runOnIdle {
            assertEquals(entries.size, entries.map { it.key }.distinct().size)
            mapOf(
                "playback-touch-gestures" to SettingsPage.PlaybackInterface,
                "playback-preferred-audio" to SettingsPage.PlaybackAudio,
                "playback-preferred-subtitles" to SettingsPage.PlaybackSubtitles,
                "playback-auto-switch-engine" to SettingsPage.PlaybackPlayer,
                "playback-libass" to SettingsPage.PlaybackSubtitles,
                "playback-custom-buffers" to SettingsPage.PlaybackBuffer,
                "liquid-glass" to SettingsPage.Layout,
                "navigation-bar" to SettingsPage.Layout,
                "app-icon" to SettingsPage.Appearance,
            ).forEach { (key, page) ->
                assertEquals(SettingsSearchTarget.Page(page), entries.single { it.key == key }.target)
            }
            assertEquals("Services", entries.single { it.key == "tracking" }.category)
            PlayerSettingsRepository.setAndroidPlaybackEngine(AndroidPlaybackEngine.Libmpv)
        }
        compose.runOnIdle {
            assertEquals(SettingsSearchTarget.Page(SettingsPage.PlaybackBuffer), entries.single { it.key == "playback-libmpv-buffer" }.target)
            assertTrue(entries.any { it.target == SettingsSearchTarget.Page(SettingsPage.PlaybackVideo) })
        }
    }
}
