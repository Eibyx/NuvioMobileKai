package com.nuvio.app.features.tracking

import com.nuvio.app.features.anilist.AniListProjection
import com.nuvio.app.features.anilist.displayTitle
import com.nuvio.app.features.anilist.posterUrl

/** The title and artwork a tracker holds for a content id. */
internal data class TrackerMediaInfo(
    val title: String,
    val poster: String?,
    val background: String?,
)

/**
 * Looks a content id up in the trackers' own lists, for Continue Watching entries whose metadata
 * cannot load (such as a mal: id no addon knows), so they show a title and artwork, not the raw id.
 */
internal object TrackerMediaLookup {
    fun find(contentId: String, aniList: AniListProjection): TrackerMediaInfo? {
        val mediaId = aniList.mediaIdFor(contentId) ?: return null
        val media = aniList.entryByMediaId[mediaId]?.media ?: return null
        val title = media.displayTitle().trim().takeIf(String::isNotBlank) ?: return null
        return TrackerMediaInfo(
            title = title,
            poster = media.posterUrl(),
            background = media.bannerImage,
        )
    }
}
