package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two identical "New Episode" cards for one anime (S1 E4): the show was watched from a Kitsu
 * catalogue (`kitsu:47398`) and from Cinemeta (`tt31010237`), and the AniList import brought both
 * ids to the same episode. Each id was its own series for Home.
 */
class ContinueWatchingDuplicatesTest {
    private val kitsu = "kitsu:47398"
    private val imdb = "tt31010237"
    private val title = "So What's Wrong with Getting Reborn as a Goblin?"

    @BeforeTest
    fun reset() = ContinueWatchingIdentity.clear()

    @AfterTest
    fun cleanUp() = ContinueWatchingIdentity.clear()

    private fun watched(id: String, episode: Int, at: Long) =
        WatchedItem(id = id, type = "series", name = title, season = 1, episode = episode, markedAtEpochMs = at)

    private fun seeds(canonical: (String) -> String) = buildHomeNextUpSeedCandidates(
        progressEntries = emptyList(),
        watchedItems = (1..3).map { watched(kitsu, it, 1_000L + it) } + (1..3).map { watched(imdb, it, 2_000L + it) },
        providerOwnsCompletedHistory = false,
        preferFurthestEpisode = true,
        nowEpochMs = 10_000L,
        canonicalSeedContentId = canonical,
    )

    private fun nextUp(id: String, at: Long, episode: Int = 4, cardTitle: String = title) = id to (at to ContinueWatchingItem(
        parentMetaId = id,
        parentMetaType = "series",
        videoId = "$id:1:$episode",
        title = cardTitle,
        subtitle = "S1 E$episode",
        imageUrl = null,
        seasonNumber = 1,
        episodeNumber = episode,
        isNextUp = true,
        nextUpSeedSeasonNumber = 1,
        nextUpSeedEpisodeNumber = episode - 1,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
        isReleaseAlert = true,
    ))

    private fun progress(id: String, type: String, at: Long, percent: Float, season: Int? = null, episode: Int? = null) =
        WatchProgressEntry(
            contentType = type,
            parentMetaId = id,
            parentMetaType = type,
            videoId = if (season != null) "$id:$season:$episode" else id,
            title = title,
            seasonNumber = season,
            episodeNumber = episode,
            lastPositionMs = (percent * 6_000_000L).toLong(),
            durationMs = 6_000_000L,
            lastUpdatedEpochMs = at,
        )

    private val linked = { type: String, id: String -> ContinueWatchingIdentity.aliases(type, id) }

    @Test
    fun theSameAnimeUnderTwoIdsWasTwoSeriesAndNowIsOne() {
        // As it was: each id seeds its own next episode, so Home showed S1 E4 twice.
        assertEquals(setOf(kitsu, imdb), seeds { it }.map { it.content.id }.toSet())

        // Opening either page (or ARM) says kitsu:47398 is tt31010237: one seed, the latest.
        ContinueWatchingIdentity.learn(kitsu, MetaDetails(id = kitsu, type = "series", name = title, imdbId = imdb))
        val merged = seeds { ContinueWatchingIdentity.canonical("series", it) }
        assertEquals(listOf(imdb), merged.map { it.content.id })
        assertEquals(3, merged.single().episodeNumber)
    }

    @Test
    fun twoNextUpCardsOfLinkedIdsBecomeOne() {
        val items = mapOf(nextUp(kitsu, 1_500L), nextUp(imdb, 2_500L))
        assertEquals(2, buildHomeContinueWatchingItems(emptyList(), nextUpItemsBySeries = items, seriesIdentity = { _, id -> setOf(id) }, sameEpisodeByTitle = false).size)

        ContinueWatchingIdentity.link("series", listOf(kitsu, "tmdb:240411"))
        ContinueWatchingIdentity.link("series", listOf("tmdb:240411", imdb))
        val cards = buildHomeContinueWatchingItems(emptyList(), nextUpItemsBySeries = items, seriesIdentity = linked)
        val card = cards.single()
        assertEquals(imdb, card.parentMetaId, "the most recent one stays")
        assertEquals(listOf(kitsu), card.duplicates.map { it.parentMetaId })
    }

    @Test
    fun withoutAnyIdLinkTheSameTitleAndEpisodeStillShowOnce() {
        val items = mapOf(nextUp(kitsu, 1_500L), nextUp(imdb, 2_500L, cardTitle = "So What’s Wrong with Getting Reborn as a Goblin"))
        val card = buildHomeContinueWatchingItems(emptyList(), nextUpItemsBySeries = items).single()
        assertEquals(imdb, card.parentMetaId)
        assertEquals("same title and episode", card.duplicates.single().reason)

        // Another episode of a show with that title is not the same card.
        val other = mapOf(nextUp(kitsu, 1_500L, episode = 7), nextUp(imdb, 2_500L))
        assertEquals(2, buildHomeContinueWatchingItems(emptyList(), nextUpItemsBySeries = other).size)

        // Two shows sharing a name in the same catalogue (both tt…) stay two cards.
        val namesakes = mapOf(nextUp("tt0386676", 1_500L, cardTitle = "The Office"), nextUp("tt0290978", 2_500L, cardTitle = "The Office"))
        assertEquals(2, buildHomeContinueWatchingItems(emptyList(), nextUpItemsBySeries = namesakes).size)
    }

    @Test
    fun onATieProgressBeatsTheNextEpisode() {
        ContinueWatchingIdentity.link("series", listOf(kitsu, imdb))
        val card = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress(kitsu, "series", at = 2_500L, percent = 0.4f, season = 1, episode = 4)),
            nextUpItemsBySeries = mapOf(nextUp(imdb, 2_500L)),
            nextUpSuppressedSeriesIds = emptySet(),
            seriesIdentity = linked,
        ).single()
        assertFalse(card.isNextUp)
        assertEquals(kitsu, card.parentMetaId)
    }

    @Test
    fun oneCardPerMovieWithItsLatestProgressAndNoneOnceFinished() {
        ContinueWatchingIdentity.link("movie", listOf("tmdb:550", "tt0137523"))
        val cards = buildHomeContinueWatchingItems(
            visibleEntries = listOf(
                progress("tt0137523", "movie", at = 3_000L, percent = 0.62f),
                progress("tmdb:550", "movie", at = 1_000L, percent = 0.20f),
            ),
            nextUpItemsBySeries = emptyMap(),
            seriesIdentity = linked,
        )
        assertEquals(listOf("tt0137523"), cards.map { it.parentMetaId })
        assertEquals(0.62f, cards.single().progressFraction, 0.001f)

        // A TV show with the same TMDB number is something else.
        assertFalse(ContinueWatchingIdentity.key("series", "tmdb:550") in ContinueWatchingIdentity.aliases("movie", "tmdb:550"))

        // Closing the player at the end: the movie leaves the row.
        val finished = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress("tt0137523", "movie", at = 4_000L, percent = 0.999f)),
            nextUpItemsBySeries = emptyMap(),
            seriesIdentity = linked,
        )
        assertTrue(finished.isEmpty())
    }

    @Test
    fun hidingTheCardHidesItsOtherIdsToo() {
        ContinueWatchingIdentity.link("series", listOf(kitsu, imdb))
        val card = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress(imdb, "series", at = 3_000L, percent = 0.3f, season = 1, episode = 4)),
            nextUpItemsBySeries = mapOf(nextUp(kitsu, 2_000L)),
            nextUpSuppressedSeriesIds = emptySet(),
            seriesIdentity = linked,
        ).single()

        val dismissed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        removeContinueWatchingCard(card, dismissNextUp = { dismissed += it }, removeProgress = { removed += it })
        assertEquals(listOf(imdb), removed)
        assertEquals(listOf(nextUpDismissKey(kitsu, 1, 3)), dismissed)
    }

    @Test
    fun theDiagnosticsLineHasIdsAndOriginButNoTitles() {
        ContinueWatchingIdentity.link("series", listOf(kitsu, imdb))
        val cards = buildHomeContinueWatchingItems(
            emptyList(),
            nextUpItemsBySeries = mapOf(nextUp(kitsu, 1_500L), nextUp(imdb, 2_500L)),
            seriesIdentity = linked,
        )
        val lines = continueWatchingDiagnosticLines(cards)
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(imdb in line && kitsu in line && "S1E4" in line && "release alert" in line, line)
        assertFalse("Goblin" in line, line)
    }
}
