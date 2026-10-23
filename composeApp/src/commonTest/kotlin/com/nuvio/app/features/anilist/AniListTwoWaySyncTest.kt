package com.nuvio.app.features.anilist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.rewatchPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mushoku Tensei end to end: AniList's two completed parts reach the detail page, and nothing echoes back. */
class AniListTwoWaySyncTest {
    private val imdb = "tt13293588"

    private val meta = MetaDetails(
        id = imdb,
        type = "series",
        name = "Mushoku Tensei: Jobless Reincarnation",
        videos = (1..23).map { MetaVideo(id = "$imdb:1:$it", title = "S1E$it", released = "2021-01-11T00:00:00.000Z", season = 1, episode = it) } +
            (1..24).map { MetaVideo(id = "$imdb:2:$it", title = "S2E$it", released = "2023-07-03T00:00:00.000Z", season = 2, episode = it) },
    )

    private val link = AniListLink(
        key = aniListSendKey(imdb, "series", 1),
        parts = listOf(AniListLinkPart(108465, "Mushoku Tensei", 1, 11), AniListLinkPart(127720, "Part 2", 12, 12)),
        manual = false,
    )

    private fun completed(id: Long, episodes: Int) = AniListEntry(
        id = id,
        mediaId = id,
        status = AniListListStatus.COMPLETED,
        progress = episodes,
        media = AniListMedia(id = id, episodes = episodes),
    )

    private fun importedKeys(parts: List<AniListImportedPart>): Set<String> =
        parts.flatMap { part -> part.episodes.flatMap { watchedItemKeys("series", imdb, it.season, it.episode) } }.toSet()

    @Test
    fun seasonOneCompletedOnAniListMakesSeasonTwoTheNextEpisode() {
        val parts = importLinkedSeason(link, 1, mapOf(108465L to completed(108465, 11), 127720L to completed(127720, 12)))
        val keys = importedKeys(parts)
        val action = meta.seriesPrimaryAction(
            entries = emptyList(),
            watchedItems = emptyList(),
            todayIsoDate = "2026-10-23",
            watchedKeys = keys,
            allowRewatch = true,
        )!!
        assertEquals(2 to 1, action.seasonNumber to action.episodeNumber)
    }

    @Test
    fun rewatchingResumesAtTheRewatchEpisode() {
        val parts = importLinkedSeason(
            link,
            1,
            mapOf(108465L to completed(108465, 11), 127720L to AniListEntry(2, 127720, AniListListStatus.REPEATING, 3, media = AniListMedia(127720, episodes = 12))),
        )
        val resume = parts.firstNotNullOf { it.resume }
        val next = meta.seriesPrimaryAction(emptyList(), emptyList(), "2026-10-23", watchedKeys = importedKeys(parts), allowRewatch = true)
        val action = meta.rewatchPrimaryAction(resume.season, resume.episode, next)!!
        assertEquals(1 to 15, action.seasonNumber to action.episodeNumber)
    }

    @Test
    fun whatCameFromAniListIsNotSentBack() {
        val watching = AniListEntry(2, 127720, AniListListStatus.CURRENT, progress = 5, media = AniListMedia(127720, episodes = 12))
        val parts = importLinkedSeason(link, 1, mapOf(108465L to completed(108465, 11), 127720L to watching))
        val keys = importedKeys(parts)
        // Marking the whole season in Nuvio: E1-E16 came from AniList, only E17-E23 are new.
        val season = (1..23).map { WatchedItem(id = imdb, type = "series", name = "", season = 1, episode = it, markedAtEpochMs = 1L) }
        val sent = withoutImported(season, keys)
        assertEquals((17..23).toList(), sent.map { it.episode })
        // A season AniList already has completely: nothing at all.
        val all = importedKeys(importLinkedSeason(link, 1, mapOf(108465L to completed(108465, 11), 127720L to completed(127720, 12))))
        assertTrue(withoutImported(season, all).isEmpty())
        // Nothing imported: everything goes.
        assertEquals(23, withoutImported(season, emptySet()).size)
    }
}
