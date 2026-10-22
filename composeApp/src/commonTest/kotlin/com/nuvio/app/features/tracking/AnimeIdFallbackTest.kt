package com.nuvio.app.features.tracking

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.buildHomeContinueWatchingItems
import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimeIdFallbackTest {
    private val originalLookup = AnimeIdFallback.lookupExternalIds
    private val originalEpisodes = AnimeIdFallback.lookupEpisodeMapping
    private var lookups = 0

    @BeforeTest
    fun stubLookups() = runTest {
        AnimeIdFallback.clearCache()
        AnimeIdFallback.lookupExternalIds = { ref ->
            lookups += 1
            when (ref.contentId) {
                "mal:22" -> AnimeExternalIds(imdb = "tt0466352", tmdb = 27845)
                "kitsu:7442" -> AnimeExternalIds(tmdb = 1429)
                "anilist:16498" -> AnimeExternalIds(imdb = "tt2560140", tvdbSeason = 1)
                else -> null
            }
        }
        AnimeIdFallback.lookupEpisodeMapping = { null }
    }

    @AfterTest
    fun restore() = runTest {
        AnimeIdFallback.lookupExternalIds = originalLookup
        AnimeIdFallback.lookupEpisodeMapping = originalEpisodes
        AnimeIdFallback.clearCache()
    }

    @Test
    fun animeIdsAndVideoIdsAreRecognised() {
        assertEquals(AnimeIdRef("mal", 22), parseAnimeIdRef("mal:22"))
        assertEquals(AnimeIdRef("mal", 22, episode = 5), parseAnimeIdRef("mal:22:5"))
        assertEquals(AnimeIdRef("kitsu", 13, episode = 7), parseAnimeIdRef("kitsu:13:1:7"))
        assertEquals(AnimeIdRef("anidb", 56), parseAnimeIdRef(" AniDB:56 "))
        assertNull(parseAnimeIdRef("tt0466352"))
        assertNull(parseAnimeIdRef("tmdb:27845"))
        assertNull(parseAnimeIdRef("mal:abc"))
        assertNull(parseAnimeIdRef("mal:22:x"))
    }

    @Test
    fun malIdFallsBackToImdbAndKitsuWithoutImdbToTmdb() = runTest {
        assertEquals("tt0466352", AnimeIdFallback.metaId(AnimeIdRef("mal", 22)))
        assertEquals("tmdb:1429", AnimeIdFallback.metaId(AnimeIdRef("kitsu", 7442)))
        assertNull(AnimeIdFallback.metaId(AnimeIdRef("mal", 999_999)))
    }

    @Test
    fun answersAreCachedIncludingMisses() = runTest {
        repeat(3) { AnimeIdFallback.metaId(AnimeIdRef("mal", 22)) }
        repeat(3) { AnimeIdFallback.metaId(AnimeIdRef("mal", 999_999)) }
        assertEquals(2, lookups)
    }

    @Test
    fun absoluteEpisodesCountRegularSeasonsInOrderAndSkipSpecials() {
        val videos = listOf(
            video(season = 2, episode = 1),
            video(season = 0, episode = 1),
            video(season = 1, episode = 2),
            video(season = 1, episode = 1),
            video(season = 2, episode = 2),
            video(season = 1, episode = 3),
        )
        assertEquals(1 to 1, absoluteEpisodeCoordinates(videos, 1))
        assertEquals(1 to 3, absoluteEpisodeCoordinates(videos, 3))
        assertEquals(2 to 2, absoluteEpisodeCoordinates(videos, 5))
        assertNull(absoluteEpisodeCoordinates(videos, 6))
        assertNull(absoluteEpisodeCoordinates(videos, 0))
    }

    @Test
    fun episodeMappingPrefersSimklThenTheTvdbSeasonThenTheEpisodeList() = runTest {
        val videos = (1..3).map { video(season = 1, episode = it) } + (1..3).map { video(season = 2, episode = it) }

        assertEquals(2 to 2, AnimeIdFallback.episodeCoordinates(AnimeIdRef("mal", 22), 5) { videos })
        assertEquals(1 to 5, AnimeIdFallback.episodeCoordinates(AnimeIdRef("anilist", 16498), 5) { videos })

        AnimeIdFallback.lookupEpisodeMapping = { ref ->
            TrackingAnimeIdMapper.AnimeImdbMapping(
                imdb = "tt0466352",
                episodes = listOf(SimklIdResolver.EpisodeMapping(animeEpisode = 1, tvdbSeason = 3, tvdbEpisode = 10)),
                fallbackSeason = null,
            ).takeIf { ref.contentId == "mal:22" }
        }
        assertEquals(3 to 14, AnimeIdFallback.episodeCoordinates(AnimeIdRef("mal", 22), 5) { videos })
    }

    @Test
    fun continueWatchingNeverShowsTheRawIdAsTitle() {
        val entry = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "mal:22",
            parentMetaType = "series",
            videoId = "mal:22:5",
            title = "mal:22",
            seasonNumber = 1,
            episodeNumber = 5,
            lastPositionMs = 60_000L,
            durationMs = 1_200_000L,
            lastUpdatedEpochMs = 1_000L,
        )
        val tracked = buildHomeContinueWatchingItems(
            visibleEntries = listOf(entry),
            nextUpItemsBySeries = emptyMap(),
            trackerMedia = { id ->
                TrackerMediaInfo("The Prince of Tennis", "https://img.example/poster.jpg", null).takeIf { id == "mal:22" }
            },
            unknownTitle = "Untitled",
        ).single()
        assertEquals("The Prince of Tennis", tracked.title)
        assertEquals("https://img.example/poster.jpg", tracked.poster)

        val untracked = buildHomeContinueWatchingItems(
            visibleEntries = listOf(entry),
            nextUpItemsBySeries = emptyMap(),
            unknownTitle = "Untitled",
        ).single()
        assertEquals("Untitled", untracked.title)
    }

    private fun video(season: Int, episode: Int) = MetaVideo(
        id = "tt0466352:$season:$episode",
        title = "S${season}E$episode",
        season = season,
        episode = episode,
    )
}
