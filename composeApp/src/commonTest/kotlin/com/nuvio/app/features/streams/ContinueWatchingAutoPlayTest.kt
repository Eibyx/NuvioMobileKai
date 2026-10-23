package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingAutoPlayTest {
    private val reuseLink = PlayerSettingsUiState(streamReuseLastLinkEnabled = true)
    private val reuseBinge = PlayerSettingsUiState(
        streamAutoPlayReuseBingeGroup = true,
        streamAutoPlayPreferBingeGroup = true,
    )

    @Test
    fun aSavedLinkIsTriedWhenReusingLinks() {
        assertTrue(StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(reuseLink, hasSavedLink = true, hasBingeGroup = false))
        assertFalse(StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(reuseLink, hasSavedLink = false, hasBingeGroup = true))
    }

    @Test
    fun aBingeGroupIsTriedWhenBothBingeSettingsAreOn() {
        assertTrue(StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(reuseBinge, hasSavedLink = false, hasBingeGroup = true))
        assertFalse(
            StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(
                reuseBinge.copy(streamAutoPlayPreferBingeGroup = false),
                hasSavedLink = false,
                hasBingeGroup = true,
            ),
        )
    }

    @Test
    fun nothingToTryOpensTheStreamList() {
        // Even with "first stream" auto-play, Continue Watching goes straight to the list.
        val firstStream = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        assertFalse(StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(firstStream, hasSavedLink = false, hasBingeGroup = false))
        assertFalse(StreamAutoPlayPolicy.continueWatchingHasAutoPlayTarget(reuseLink, hasSavedLink = false, hasBingeGroup = false))
    }
}
