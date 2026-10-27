package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAutoPlayLoadingPolicyTest {

    private val autoPlaySettings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)

    private val stream = StreamItem(url = "https://example.com/episode.mp4", addonName = "Test", addonId = "test")
    private val reuseSettings = listOf(
        autoPlaySettings,
        PlayerSettingsUiState(streamReuseLastLinkEnabled = true),
        PlayerSettingsUiState(streamAutoPlayReuseBingeGroup = true, streamAutoPlayPreferBingeGroup = true),
    )

    /** Autoplay looking for a stream for this request: the direct flow, nothing chosen yet. */
    private val searching = StreamsUiState(
        requestToken = "new",
        autoPlayDecided = true,
        isDirectAutoPlayFlow = true,
        showDirectAutoPlayOverlay = true,
    )

    @Test
    fun `while autoplay is still deciding the streams screen shows without the poster`() {
        reuseSettings.forEach { settings ->
            assertFalse(StreamsUiState().shouldShowAutoPlayLoading("new", settings, false))
            assertFalse(StreamsUiState(requestToken = "new").shouldShowAutoPlayLoading("new", settings, false))
            assertFalse(searching.shouldShowAutoPlayLoading("new", settings, false))
            assertFalse(searching.shouldUseLandscapeAutoPlayLoading("new", settings, false))
        }
    }

    @Test
    fun `once a stream is chosen the poster shows until the player opens`() {
        val chosen = searching.copy(autoPlayStream = stream)
        reuseSettings.forEach { settings ->
            assertTrue(chosen.shouldShowAutoPlayLoading("new", settings, false))
        }
        assertTrue(chosen.shouldUseLandscapeAutoPlayLoading("new", autoPlaySettings, false))
        // Not for another request.
        assertFalse(chosen.shouldShowAutoPlayLoading("other", autoPlaySettings, false))
        assertFalse(chosen.shouldUseLandscapeAutoPlayLoading("other", autoPlaySettings, false))
    }

    @Test
    fun `when autoplay decides not to play the list stays without the poster`() {
        val settled = searching.copy(isDirectAutoPlayFlow = false, showDirectAutoPlayOverlay = false)
        reuseSettings.forEach { settings ->
            assertFalse(settled.shouldShowAutoPlayLoading("new", settings, false))
            assertFalse(settled.shouldUseLandscapeAutoPlayLoading("new", settings, false))
        }
    }

    @Test
    fun `a manual selection never shows the poster`() {
        val chosen = searching.copy(autoPlayStream = stream)
        reuseSettings.forEach { settings ->
            assertFalse(StreamsUiState().shouldShowAutoPlayLoading("new", settings, true))
            assertFalse(chosen.shouldShowAutoPlayLoading("new", settings, true))
            assertFalse(chosen.shouldUseLandscapeAutoPlayLoading("new", settings, true))
        }
        assertFalse(StreamsUiState().shouldShowAutoPlayLoading("new", PlayerSettingsUiState(), false))
    }

    @Test
    fun `manual binge group reuse does not rotate`() {
        val settings = PlayerSettingsUiState(streamAutoPlayReuseBingeGroup = true, streamAutoPlayPreferBingeGroup = true)
        val chosen = searching.copy(autoPlayStream = stream)
        assertTrue(chosen.shouldShowAutoPlayLoading("new", settings, false))
        assertFalse(chosen.shouldUseLandscapeAutoPlayLoading("new", settings, false))
    }

    @Test
    fun `the small notice shows only while the previous source is searched for`() {
        val lastLink = PlayerSettingsUiState(streamReuseLastLinkEnabled = true)
        val bingeGroup = PlayerSettingsUiState(streamAutoPlayReuseBingeGroup = true, streamAutoPlayPreferBingeGroup = true)
        assertTrue(StreamsUiState().isSearchingPreviousSource("new", lastLink, false, lastLinkChecked = false))
        assertFalse(searching.isSearchingPreviousSource("new", lastLink, false, lastLinkChecked = true))
        assertTrue(searching.isSearchingPreviousSource("new", bingeGroup, false, lastLinkChecked = true))
        assertFalse(searching.copy(autoPlayStream = stream).isSearchingPreviousSource("new", bingeGroup, false, lastLinkChecked = true))
        assertFalse(searching.copy(isDirectAutoPlayFlow = false).isSearchingPreviousSource("new", bingeGroup, false, lastLinkChecked = true))
        assertFalse(StreamsUiState().isSearchingPreviousSource("new", lastLink, true, lastLinkChecked = false))
        assertFalse(StreamsUiState().isSearchingPreviousSource("new", autoPlaySettings, false, lastLinkChecked = false))
    }

    @Test
    fun `stream picker and external preparation overlays do not rotate`() {
        val state = StreamsUiState(requestToken = "new", autoPlayDecided = true)

        assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", autoPlaySettings, false))
        assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", autoPlaySettings, true))
        assertFalse(
            state.copy(showDirectAutoPlayOverlay = true)
                .shouldUseLandscapeAutoPlayLoading("new", autoPlaySettings, false),
        )
    }

    @Test
    fun `installed addons are loaded while plugins are still loading`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "plugin:comet", isLoading = true),
        )

        assertTrue(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    @Test
    fun `installed addons remain loading until every addon finishes`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "addon:comet", isLoading = true),
            group(addonId = "plugin:mediafusion", isLoading = false),
        )

        assertFalse(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonIds = setOf("addon:torrentio", "addon:comet"),
            ),
        )
    }

    @Test
    fun `enabled plugins are loaded while addons are still loading`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = true),
            group(addonId = "plugin:comet", isLoading = false),
        )

        assertTrue(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    @Test
    fun `all sources wait for addons and plugins`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "plugin:comet", isLoading = true),
        )

        assertFalse(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.ALL_SOURCES,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    private fun group(addonId: String, isLoading: Boolean): AddonStreamGroup =
        AddonStreamGroup(
            addonName = addonId,
            addonId = addonId,
            streams = emptyList(),
            isLoading = isLoading,
        )
}
