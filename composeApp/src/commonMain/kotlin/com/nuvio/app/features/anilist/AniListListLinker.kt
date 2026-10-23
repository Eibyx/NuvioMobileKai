package com.nuvio.app.features.anilist

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

private const val TAG = "Sync/AniList"

/** Where linking the AniList list stands, for the AniList card in Settings. */
data class AniListListLinkProgress(
    val running: Boolean = false,
    /** Entries linked or looked up, of [total]. */
    val done: Int = 0,
    val total: Int = 0,
    /** Entries with a link: what they say watched shows up in Nuvio. */
    val linked: Int = 0,
)

/** The list entries whose episodes count as seen: watching, completed, rewatching and paused. */
internal val ANILIST_LIST_LINK_STATUSES = setOf(
    AniListListStatus.CURRENT,
    AniListListStatus.COMPLETED,
    AniListListStatus.REPEATING,
    AniListListStatus.PAUSED,
)

/** Entries of [snapshot] linking is for: seen (see [ANILIST_LIST_LINK_STATUSES]) and not movies. */
internal fun aniListListEntries(snapshot: AniListSyncSnapshot): List<AniListEntry> =
    snapshot.entries.filter { entry ->
        entry.status in ANILIST_LIST_LINK_STATUSES && entry.media?.isMovie != true
    }

/**
 * Entries still to link: no link has them, they were not looked up in the last [recheckMs] and
 * their ids are known (the list sync resolves them from ARM first).
 */
internal fun aniListEntriesToLink(
    snapshot: AniListSyncSnapshot,
    linkedMediaIds: Set<Long>,
    checked: Map<Long, Long>,
    nowMs: Long,
    recheckMs: Long = AniListListLinker.RECHECK_MS,
): List<AniListEntry> =
    aniListListEntries(snapshot).filter { entry ->
        entry.mediaId !in linkedMediaIds &&
            checked[entry.mediaId]?.let { nowMs - it in 0 until recheckMs } != true &&
            (snapshot.mappings[entry.mediaId]?.resolvedAtEpochMs ?: 0L) > 0L
    }

/**
 * The TVDB seasons ARM puts [mediaIds] in. A row without a season (one entry for the whole show,
 * One Piece) needs the show's episode list and is left to the detail page.
 */
internal fun armSeasonsOf(rows: List<ArmImdbEntry>, mediaIds: Set<Long>): List<Int> =
    rows.filter { it.anilist in mediaIds }.mapNotNull { it.tvdbSeason?.takeIf { season -> season > 0 } }.distinct().sorted()

/**
 * Catalogs with anime ids (Kitsu, MAL) have one AniList entry per title, season 1: those links
 * need no lookup.
 */
internal fun animeIdLinks(entry: AniListEntry, mapping: AniListMapping?): List<AniListLink> {
    val title = entry.media?.displayTitle() ?: "AniList ${entry.mediaId}"
    val part = AniListLinkPart(entry.mediaId, title, episodes = entry.media?.episodes)
    return listOfNotNull(mapping?.mal?.let { "mal:$it" }, mapping?.kitsu?.let { "kitsu:$it" })
        .map { id -> AniListLink(aniListSendKey(id, "series", 1), listOf(part), manual = false) }
}

/**
 * Links the anime of the AniList list in the background, so what they say watched shows up in
 * Nuvio without opening each detail page (see [AniListWatchedImporter]).
 *
 * - Only reads: ARM's season table and AniList media lookups. Nothing is written to AniList.
 * - Entries in Watching, Completed, Rewatching and Paused that no link has yet.
 * - In batches of [BATCH_SIZE] shows, one ARM and at most one AniList request per show, with
 *   [REQUEST_PAUSE_MS] between shows (below both services' limits); a refusal (429) or network
 *   failure stops the run and the next sync carries on.
 * - Each entry is looked up once; one without a match is tried again after [RECHECK_MS].
 */
internal class AniListListLinker(
    private val snapshot: () -> AniListSyncSnapshot?,
    private val profile: () -> Int?,
    private val queue: AniListSendQueue,
    private val armRows: suspend (imdb: String) -> List<ArmImdbEntry>,
    private val link: suspend (TrackingMediaReference) -> AniListLink?,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val runLock = Mutex()
    private val mutableProgress = MutableStateFlow(AniListListLinkProgress())
    val progress: StateFlow<AniListListLinkProgress> = mutableProgress.asStateFlow()

    /** One pass over the list; a call while one runs is dropped (the running pass sees the list). */
    suspend fun run() {
        if (!runLock.tryLock()) return
        try {
            linkList()
        } finally {
            runLock.unlock()
            mutableProgress.value = mutableProgress.value.copy(running = false)
        }
    }

    private suspend fun linkList() {
        val profileId = profile() ?: return clear()
        if (queue.openProfile != profileId) return
        val snap = snapshot() ?: return clear()
        val listIds = aniListListEntries(snap).mapTo(mutableSetOf(), AniListEntry::mediaId)
        val todo = aniListEntriesToLink(snap, linkedIds(), queue.state.value.listChecked, now())
        publish(listIds, running = todo.isNotEmpty())
        if (todo.isEmpty()) return

        fun idLinks(entries: List<AniListEntry>) = entries.flatMap { animeIdLinks(it, snap.mappings[it.mediaId]) }
        // Entries known only by anime ids first: no requests.
        val (withImdb, withoutImdb) = todo.partition { !snap.mappings[it.mediaId]?.imdb.isNullOrBlank() }
        if (withoutImdb.isNotEmpty()) {
            queue.addListLinks(profileId, idLinks(withoutImdb), withoutImdb.associate { it.mediaId to now() }, listIds)
            publish(listIds, running = true)
        }

        val shows = withImdb.groupBy { snap.mappings.getValue(it.mediaId).imdb!!.trim().lowercase() }
        InAppLogger.info(TAG, "Linking the AniList list: ${todo.size} entries, ${shows.size} shows to look up")
        var linkedShows = 0
        shows.entries.chunked(BATCH_SIZE).forEach { batch ->
            val links = mutableListOf<AniListLink>()
            val checked = mutableMapOf<Long, Long>()
            for ((imdb, entries) in batch) {
                if (profile() != profileId) return
                val found = try {
                    linkShow(imdb, entries, snap)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // A refusal (429) or a network failure: keep what this batch found, carry on next sync.
                    InAppLogger.info(TAG, "Stopped linking the AniList list at $imdb: ${InAppLogger.throwableSummary(error)}")
                    queue.addListLinks(profileId, links, checked, listIds)
                    publish(listIds, running = false)
                    return
                }
                links += found + idLinks(entries)
                if (found.isNotEmpty()) linkedShows++
                entries.forEach { checked[it.mediaId] = now() }
                pause(REQUEST_PAUSE_MS)
            }
            queue.addListLinks(profileId, links, checked, listIds)
            publish(listIds, running = true)
        }
        InAppLogger.info(TAG, "Linked the AniList list: $linkedShows of ${shows.size} shows found")
    }

    /** Every season of [imdb] that has one of [entries], as ARM splits it. */
    private suspend fun linkShow(imdb: String, entries: List<AniListEntry>, snap: AniListSyncSnapshot): List<AniListLink> {
        val ids = entries.mapTo(mutableSetOf(), AniListEntry::mediaId)
        val rows = armRows(imdb)
        val seasons = if (rows.isNotEmpty()) {
            armSeasonsOf(rows, ids)
        } else {
            entries.map { snap.mappings[it.mediaId]?.tvdbSeason?.takeIf { season -> season > 0 } ?: 1 }.distinct()
        }
        val title = entries.first().media?.displayTitle()
        return seasons.mapNotNull { season ->
            val key = aniListSendKey(imdb, "series", season)
            if (queue.linkFor(key) != null) return@mapNotNull null
            link(buildTrackingMediaReference("series", imdb, title = title, seasonNumber = season, episodeNumber = 1))
                ?.takeIf { found -> found.parts.isNotEmpty() }
        }
    }

    private fun linkedIds(): Set<Long> =
        queue.state.value.links.flatMapTo(mutableSetOf()) { link -> link.parts.map(AniListLinkPart::mediaId) }

    private fun publish(listIds: Set<Long>, running: Boolean) {
        val linked = linkedIds()
        val checked = queue.state.value.listChecked
        mutableProgress.value = AniListListLinkProgress(
            running = running,
            done = listIds.count { it in linked || it in checked },
            total = listIds.size,
            linked = listIds.count { it in linked },
        )
    }

    private fun clear() {
        mutableProgress.value = AniListListLinkProgress()
    }

    companion object {
        const val BATCH_SIZE = 10
        /** About 30 shows a minute: within AniList's (30 to 90 a minute) and ARM's limits. */
        const val REQUEST_PAUSE_MS = 2_000L
        const val RECHECK_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
