package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

/**
 * The poster overlay before the player: only once autoplay has chosen a stream for this request
 * and the player is about to open. While autoplay is still deciding (reusing the last link, the
 * same binge group or the first stream) the Streams screen shows with its normal loading.
 */
internal fun StreamsUiState.shouldShowAutoPlayLoading(
    expectedRequestToken: String,
    settings: PlayerSettingsUiState,
    manualSelection: Boolean,
): Boolean =
    !manualSelection &&
        requestToken == expectedRequestToken &&
        autoPlayDecided &&
        isDirectAutoPlayFlow &&
        showDirectAutoPlayOverlay &&
        autoPlayStream != null

/** Turns to landscape only behind that overlay, never during the search. */
internal fun StreamsUiState.shouldUseLandscapeAutoPlayLoading(
    expectedRequestToken: String,
    settings: PlayerSettingsUiState,
    manualSelection: Boolean,
): Boolean =
    settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL &&
        shouldShowAutoPlayLoading(expectedRequestToken, settings, manualSelection)

/**
 * The small notice on the Streams screen while autoplay looks for the source of the previous
 * episode (last link or binge group); null once it has decided, and for manual picks.
 */
internal fun StreamsUiState.isSearchingPreviousSource(
    expectedRequestToken: String,
    settings: PlayerSettingsUiState,
    manualSelection: Boolean,
    lastLinkChecked: Boolean,
): Boolean {
    if (manualSelection) return false
    val lastLink = settings.streamReuseLastLinkEnabled
    val bingeGroup = settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup
    if (!lastLink && !bingeGroup) return false
    if (lastLink && !lastLinkChecked) return true
    if (!bingeGroup) return false
    if (requestToken != expectedRequestToken || !autoPlayDecided) return true
    return isDirectAutoPlayFlow && autoPlayStream == null
}

internal fun List<AddonStreamGroup>.areAutoPlaySourcesLoaded(
    source: StreamAutoPlaySource,
    installedAddonIds: Set<String>,
): Boolean = none { group ->
    group.isLoading && when (source) {
        StreamAutoPlaySource.ALL_SOURCES -> true
        StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> group.addonId in installedAddonIds
        StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> group.addonId !in installedAddonIds
    }
}
