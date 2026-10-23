package com.nuvio.app.features.watched

import com.nuvio.app.features.tracking.TrackingProviderId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Episodes a tracker (AniList) reports as watched, shown whatever the watched source is (Nuvio Sync here). */
class WatchedImportedKeysTest {
    @AfterTest
    fun reset() = WatchedRepository.setImportedWatchedKeys(TrackingProviderId.MDBLIST, emptySet())

    @Test
    fun importedEpisodesShowAsWatchedAndUnmarkingTakesThemBack() {
        // AniList's own importer owns the AniList slot; another provider's slot keeps this test alone.
        WatchedRepository.ensureLoaded()
        val keys = (1..23).flatMap { watchedItemKeys("series", "tt13293588", 1, it) }.toSet()
        WatchedRepository.setImportedWatchedKeys(TrackingProviderId.MDBLIST, keys)
        assertTrue(WatchedRepository.isWatched("tt13293588", "series", 1, 23))
        assertTrue(watchedItemKey("series", "tt13293588", 1, 12) in WatchedRepository.uiState.value.watchedKeys)
        assertFalse(WatchedRepository.isWatched("tt13293588", "series", 2, 1))

        // Unmarking by hand: no longer watched in Nuvio (and AniList is told).
        WatchedRepository.unmarkWatched("tt13293588", "series", 1, 23)
        assertFalse(WatchedRepository.isWatched("tt13293588", "series", 1, 23))
        assertTrue(WatchedRepository.isWatched("tt13293588", "series", 1, 22))
    }
}
