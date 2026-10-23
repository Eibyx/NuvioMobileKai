package com.nuvio.app.features.home

import com.nuvio.app.features.anilist.SeasonEpisode
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/** AniList says this episode is watched (and is not where a rewatch on AniList is at). */
internal fun isAniListWatchedEpisode(
    importedKeys: Set<String>,
    rewatch: Map<String, SeasonEpisode>,
    type: String,
    id: String,
    season: Int?,
    episode: Int?,
): Boolean {
    if (importedKeys.isEmpty() || season == null || episode == null) return false
    if (rewatch[id.lowercase()] == SeasonEpisode(season, episode)) return false
    return (watchedItemKeys(type, id, season, episode) + watchedItemKeys(type, id.lowercase(), season, episode))
        .any(importedKeys::contains)
}

/**
 * "Continue watching" with what AniList says is watched: Nuvio's half-watched copy of such an
 * episode counts as finished, so the row moves on to the next episode instead of resuming it (T1
 * completed on AniList while Nuvio stopped halfway through T1E5 continues at T2E1).
 */
internal fun List<WatchProgressEntry>.finishedByAniList(
    importedKeys: Set<String>,
    rewatch: Map<String, SeasonEpisode>,
): List<WatchProgressEntry> {
    if (importedKeys.isEmpty()) return this
    return map { entry ->
        if (
            !entry.isEffectivelyCompleted &&
            isAniListWatchedEpisode(importedKeys, rewatch, entry.parentMetaType, entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        ) {
            entry.copy(isCompleted = true, progressPercent = 100f)
        } else {
            entry
        }
    }
}

/** A saved "Next episode" AniList has already seen is worked out again instead of shown. */
internal fun CachedNextUpItem.isWatchedOnAniList(
    importedKeys: Set<String>,
    rewatch: Map<String, SeasonEpisode>,
): Boolean = isAniListWatchedEpisode(importedKeys, rewatch, contentType, contentId, season, episode)
