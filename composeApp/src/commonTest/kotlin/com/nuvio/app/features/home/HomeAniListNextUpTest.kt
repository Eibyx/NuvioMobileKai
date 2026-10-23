package com.nuvio.app.features.home

import com.nuvio.app.features.anilist.AniListEntry
import com.nuvio.app.features.anilist.AniListLink
import com.nuvio.app.features.anilist.AniListLinkPart
import com.nuvio.app.features.anilist.AniListListStatus
import com.nuvio.app.features.anilist.AniListMedia
import com.nuvio.app.features.anilist.SeasonEpisode
import com.nuvio.app.features.anilist.importLinkedSeason
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.combinedSeriesPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home's "Next episode" with Nuvio's history and AniList's together, as the detail page has it. */
class HomeAniListNextUpTest {
    private val imdb = "tt13293588"
    private val today = "2026-10-23"
    private val content = WatchingContentRef(type = "series", id = imdb)

    private val meta = MetaDetails(
        id = imdb,
        type = "series",
        name = "Mushoku Tensei: Jobless Reincarnation",
        videos = (1..23).map { MetaVideo(id = "$imdb:1:$it", title = "S1E$it", released = "2021-01-11T00:00:00.000Z", season = 1, episode = it) } +
            (1..24).map { MetaVideo(id = "$imdb:2:$it", title = "S2E$it", released = "2023-07-03T00:00:00.000Z", season = 2, episode = it) },
    )

    private val link = AniListLink(
        key = "$imdb|s1",
        parts = listOf(AniListLinkPart(108465, "Mushoku Tensei", 1, 11), AniListLinkPart(127720, "Part 2", 12, 12)),
        manual = false,
    )

    private fun entry(id: Long, status: String, progress: Int, episodes: Int) =
        AniListEntry(id = id, mediaId = id, status = status, progress = progress, media = AniListMedia(id, episodes = episodes))

    /** Season one completed on AniList: both parts. */
    private val seasonOneKeys: Set<String> = importLinkedSeason(
        link,
        1,
        mapOf(
            108465L to entry(108465, AniListListStatus.COMPLETED, 11, 11),
            127720L to entry(127720, AniListListStatus.COMPLETED, 12, 12),
        ),
    ).flatMap { part -> part.episodes.flatMap { watchedItemKeys("series", imdb, it.season, it.episode) } }.toSet()

    private fun watched(season: Int, episode: Int, at: Long) =
        WatchedItem(id = imdb, type = "series", name = meta.name, season = season, episode = episode, markedAtEpochMs = at)

    private fun progress(season: Int, episode: Int, positionMs: Long, at: Long) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = imdb,
        parentMetaType = "series",
        videoId = "$imdb:$season:$episode",
        title = meta.name,
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = positionMs,
        durationMs = 1_440_000,
        lastUpdatedEpochMs = at,
    )

    @Test
    fun nextEpisodeAfterTheLatestNuvioEpisodeSkipsWhatAniListHas() {
        // Watched S1E3 in Nuvio, the rest of season one elsewhere; Home continues from the latest.
        val nuvio = listOf(watched(1, 3, at = 1_000))
        val before = meta.seriesPrimaryAction(content, emptyList(), nuvio, today, preferFurthestEpisode = false)!!
        assertEquals(1 to 4, before.seasonNumber to before.episodeNumber)

        val action = meta.combinedSeriesPrimaryAction(
            content = content,
            entries = emptyList(),
            watchedItems = nuvio,
            importedKeys = seasonOneKeys,
            todayIsoDate = today,
            preferFurthestEpisode = false,
        )!!
        assertEquals(2 to 1, action.seasonNumber to action.episodeNumber)
        assertEquals("$imdb:2:1", action.videoId)
        assertNull(action.resumePositionMs)
    }

    @Test
    fun furthestEpisodeIsTheLastOneAniListHas() {
        val action = meta.combinedSeriesPrimaryAction(
            content = content,
            entries = emptyList(),
            watchedItems = listOf(watched(1, 3, at = 1_000)),
            importedKeys = seasonOneKeys,
            todayIsoDate = today,
            preferFurthestEpisode = true,
        )!!
        assertEquals(2 to 1, action.seasonNumber to action.episodeNumber)
    }

    @Test
    fun aHalfWatchedEpisodeAniListHasIsNotResumed() {
        // Nuvio stopped halfway through S1E5; AniList has all of season one.
        val entries = listOf(progress(1, 5, positionMs = 600_000, at = 2_000))
        val home = entries.finishedByAniList(seasonOneKeys, emptyMap())
        assertTrue(home.single().isEffectivelyCompleted)

        // So Home has a "Next episode" seed instead of "Resume S1E5"...
        val seeds = buildHomeNextUpSeedCandidates(
            progressEntries = home,
            watchedItems = emptyList(),
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = false,
            nowEpochMs = 3_000,
        )
        assertEquals(listOf(1 to 5), seeds.map { it.seasonNumber to it.episodeNumber })

        // ...and both Home and the detail page go to S2E1.
        val fromHome = meta.combinedSeriesPrimaryAction(content, home, emptyList(), seasonOneKeys, todayIsoDate = today, preferFurthestEpisode = false)!!
        val fromDetail = meta.combinedSeriesPrimaryAction(content, entries, emptyList(), seasonOneKeys, todayIsoDate = today, preferFurthestEpisode = false)!!
        assertEquals(2 to 1, fromHome.seasonNumber to fromHome.episodeNumber)
        assertEquals(fromHome, fromDetail)

        // Without AniList nothing changes: S1E5 is resumed.
        assertEquals(entries, entries.finishedByAniList(emptySet(), emptyMap()))
        val plain = meta.combinedSeriesPrimaryAction(content, entries, emptyList(), emptySet(), todayIsoDate = today)!!
        assertEquals(600_000L, plain.resumePositionMs)
    }

    @Test
    fun rewatchingOnAniListResumesThere() {
        // Rewatching Part 2 at episode 3: S1E15. Nuvio's half-watched S1E15 is kept and resumed.
        val rewatch = SeasonEpisode(1, 15)
        val rewatchKeys = seasonOneKeys
        val entries = listOf(progress(1, 15, positionMs = 300_000, at = 2_000))
        val home = entries.finishedByAniList(rewatchKeys, mapOf(imdb to rewatch))
        assertFalse(home.single().isEffectivelyCompleted)

        val resumed = meta.combinedSeriesPrimaryAction(content, home, emptyList(), rewatchKeys, rewatch, today)!!
        assertEquals(1 to 15, resumed.seasonNumber to resumed.episodeNumber)
        assertEquals(300_000L, resumed.resumePositionMs)

        // With nothing in Nuvio the rewatch episode is the next one.
        val next = meta.combinedSeriesPrimaryAction(content, emptyList(), listOf(watched(1, 3, 1_000)), rewatchKeys, rewatch, today)!!
        assertEquals(1 to 15, next.seasonNumber to next.episodeNumber)
        assertNull(next.resumePositionMs)
    }

    @Test
    fun aSavedNextEpisodeAniListHasIsWorkedOutAgain() {
        fun cached(season: Int, episode: Int) = CachedNextUpItem(
            contentId = imdb,
            contentType = "series",
            name = meta.name,
            videoId = "$imdb:$season:$episode",
            season = season,
            episode = episode,
            lastWatched = 1_000,
            sortTimestamp = 1_000,
            seedSeason = 1,
            seedEpisode = 3,
        )
        assertTrue(cached(1, 4).isWatchedOnAniList(seasonOneKeys, emptyMap()))
        assertFalse(cached(2, 1).isWatchedOnAniList(seasonOneKeys, emptyMap()))
        assertFalse(cached(1, 4).isWatchedOnAniList(emptySet(), emptyMap()))
        assertFalse(cached(1, 4).isWatchedOnAniList(seasonOneKeys, mapOf(imdb to SeasonEpisode(1, 4))))
    }

    @Test
    fun everythingWatchedOnAniListLeavesNoNextEpisode() {
        val allKeys = seasonOneKeys + (1..24).flatMap { watchedItemKeys("series", imdb, 2, it) }
        val action = meta.combinedSeriesPrimaryAction(
            content = content,
            entries = emptyList(),
            watchedItems = listOf(watched(1, 3, 1_000)),
            importedKeys = allKeys,
            todayIsoDate = today,
            preferFurthestEpisode = false,
        )
        assertNull(action)
    }
}
