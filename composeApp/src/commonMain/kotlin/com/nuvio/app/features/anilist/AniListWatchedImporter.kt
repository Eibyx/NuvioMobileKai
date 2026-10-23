package com.nuvio.app.features.anilist

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watched.watchedItemKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "Sync/AniList"

/** One season of a title as AniList sees it: its linked parts and what each says was watched. */
internal data class AniListSeasonImport(
    val linkKey: String,
    val contentId: String,
    val season: Int,
    val parts: List<AniListImportedPart>,
)

/**
 * AniList → Nuvio for anime, whatever the watched source is: every linked season's AniList
 * progress becomes watched episodes of the detail page (see [importLinkedSeason]). The episodes go
 * to [com.nuvio.app.features.watched.WatchedRepository] as imported keys, never as Nuvio items, so
 * nothing sends them back to AniList.
 */
internal class AniListWatchedImporter(
    private val sync: AniListSyncRepository,
    private val api: AniListApiClient,
    private val queue: AniListSendQueue,
    private val resolver: AniListIdResolver,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    /** The detail page's own id spelling per link key (keys are lowercase). */
    @kotlin.concurrent.Volatile
    private var contentIds: Map<String, String> = emptyMap()
    /** Episode lists of opened shows, for absolute links (One Piece) and air dates. */
    private val showEpisodes = MutableStateFlow<Map<String, List<ShowEpisode>>>(emptyMap())
    /** Entries placed by air date when no season table placed them, per show id. */
    private val dateLinks = MutableStateFlow<Map<String, List<AniListLink>>>(emptyMap())

    private val mutableImports = MutableStateFlow<Map<String, AniListSeasonImport>>(emptyMap())
    val imports: StateFlow<Map<String, AniListSeasonImport>> = mutableImports.asStateFlow()

    private val mutableKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Watched keys of every imported episode, both "series" and its type aliases. */
    val keys: StateFlow<Set<String>> = mutableKeys.asStateFlow()

    private val mutableResume = MutableStateFlow<Map<String, SeasonEpisode>>(emptyMap())
    /** Rewatching on AniList: where each show resumes, by its id (lowercase). */
    val resume: StateFlow<Map<String, SeasonEpisode>> = mutableResume.asStateFlow()

    fun start() {
        scope.launch {
            combine(sync.projectionState, queue.state, showEpisodes, dateLinks) { _, state, _, _ -> state.links }
                .collect { links -> recompute(links) }
        }
    }

    private fun recompute(links: List<AniListLink>) {
        val snapshot = sync.currentSnapshot()
        if (snapshot == null || !sync.isAuthenticatedFor(sync.currentScope().profileId)) {
            publish(emptyMap())
            return
        }
        val entries = snapshot.entries.associateBy(AniListEntry::mediaId)
        val dated = dateLinks.value.values.flatten()
        val imports = (links + dated)
            .filterNot { it.key.endsWith("|movie") }
            .mapNotNull { link ->
                val (id, season) = parseKey(link.key) ?: return@mapNotNull null
                val contentId = contentIds[link.key] ?: id
                val parts = importLinkedSeason(link, season, link.parts.associate { it.mediaId to entries[it.mediaId] }, showEpisodes.value[id].orEmpty())
                link.key to AniListSeasonImport(link.key, contentId, season, parts)
            }
            .toMap()
        publish(imports)
    }

    private fun publish(imports: Map<String, AniListSeasonImport>) {
        val previous = mutableImports.value
        mutableImports.value = imports
        val keys = linkedSetOf<String>()
        val resume = mutableMapOf<String, SeasonEpisode>()
        imports.values.forEach { season ->
            season.parts.forEach { part ->
                part.episodes.forEach { keys += watchedItemKeys("series", season.contentId, it.season, it.episode) }
                part.resume?.let { resume[season.contentId.lowercase()] = it }
            }
        }
        mutableKeys.value = keys
        mutableResume.value = resume
        imports.forEach { (key, season) ->
            val before = previous[key]?.parts?.map { it.watched }
            val now = season.parts.map { it.watched }
            if (before != now && now.any { it > 0 }) {
                InAppLogger.info(
                    TAG,
                    "Imported from AniList: ${season.contentId} season ${season.season} " +
                        season.parts.joinToString { "${it.title} ${it.watched}/${it.total ?: "?"} ${it.status ?: "not on list"}" },
                )
            }
        }
    }

    /**
     * Opening the page of an anime: binds each of its seasons to AniList (as playing would), reads
     * those entries fresh from AniList (watched elsewhere, in Aniyomi for instance) and places list
     * entries no season table knows by their air dates.
     */
    suspend fun refreshTitle(meta: MetaDetails) {
        if (meta.type.equals("movie", ignoreCase = true) || !meta.isAniListCandidate()) return
        val authScope = sync.currentScope()
        if (!sync.isAuthenticatedFor(authScope.profileId)) return
        lock.withLock {
            val id = meta.id.trim().lowercase()
            val episodes = meta.videos.mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                ShowEpisode(season, episode, releasedEpochDay(video.released))
            }
            showEpisodes.value = showEpisodes.value + (id to episodes)
            val seasons = episodes.map { it.season }.filter { it > 0 }.distinct().sorted()
            val links = seasons.mapNotNull { season ->
                val key = aniListSendKey(meta.id, meta.type, season)
                contentIds = contentIds + (key to meta.id)
                queue.linkFor(key) ?: try {
                    val first = episodes.filter { it.season == season }.minOfOrNull { it.episode } ?: 1
                    resolver.link(
                        buildTrackingMediaReference(meta.type, meta.id, title = meta.name, seasonNumber = season, episodeNumber = first),
                        sync.currentSnapshot(),
                    )?.also { queue.setLink(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    InAppLogger.info(TAG, "Could not link ${meta.name} season $season for import: ${InAppLogger.throwableSummary(error)}")
                    null
                }
            }
            val linked = links.flatMap { link -> link.parts.map(AniListLinkPart::mediaId) }.toSet()
            refreshEntries(authScope, linked)
            dateLinks.value = dateLinks.value + (id to datedLinks(meta, episodes, linked))
            InAppLogger.info(TAG, "Refreshed ${meta.name} from AniList: ${linked.size} linked entries, ${seasons.size} seasons")
        }
    }

    /** "Sync now" in the AniList sheet: every entry of the title read again from AniList. */
    suspend fun syncTitle(meta: MetaDetails) {
        refreshTitle(meta)
        queue.retryNow("Sync now pressed")
    }

    private suspend fun refreshEntries(authScope: AniListAuthScope, mediaIds: Set<Long>) {
        mediaIds.forEach { mediaId ->
            try {
                val (media, entry) = api.mediaById(authScope, mediaId) ?: return@forEach
                if (entry != null) sync.applyEntry(authScope, entry, media) else sync.removeEntry(authScope, mediaId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                InAppLogger.info(TAG, "Could not refresh AniList entry $mediaId: ${InAppLogger.throwableSummary(error)}")
            }
        }
    }

    /**
     * List entries that map to this show but no season table placed (ARM missing a part): matched to
     * the block of episodes that aired when they did, with the same number of episodes.
     */
    private fun datedLinks(meta: MetaDetails, episodes: List<ShowEpisode>, linked: Set<Long>): List<AniListLink> {
        val snapshot = sync.currentSnapshot() ?: return emptyList()
        val imdb = (meta.imdbId ?: meta.id).trim()
        return snapshot.entries
            .filter { entry -> entry.mediaId !in linked && snapshot.mappings[entry.mediaId]?.imdb.equals(imdb, ignoreCase = true) }
            .mapNotNull { entry ->
                val media = entry.media ?: return@mapNotNull null
                if (media.isMovie) return@mapNotNull null
                val start = aniListStartDay(media) ?: return@mapNotNull null
                val count = media.episodes?.takeIf { it > 0 } ?: entry.progress.takeIf { it > 0 } ?: return@mapNotNull null
                val block = aniListDateBlock(episodes, start, count) ?: return@mapNotNull null
                val first = block.first()
                // A block that crosses into the next season is cut at the season's end.
                val sameSeason = block.takeWhile { it.season == first.season }
                InAppLogger.info(TAG, "Placed AniList ${media.id} by air date at S${first.season}E${first.episode} (${sameSeason.size} episodes)")
                AniListLink(
                    key = aniListSendKey(meta.id, meta.type, first.season) + "#date${media.id}",
                    parts = listOf(AniListLinkPart(media.id, media.displayTitle(), first.episode, sameSeason.size)),
                    manual = false,
                ).also { contentIds = contentIds + (it.key to meta.id) }
            }
    }

    /** "tt13293588|s1" (or with a "#date…" suffix) to its id and season. */
    private fun parseKey(key: String): Pair<String, Int>? {
        val base = key.substringBefore('#')
        val id = base.substringBeforeLast('|', "").takeIf(String::isNotBlank) ?: return null
        val season = base.substringAfterLast('|').removePrefix("s").toIntOrNull() ?: return null
        return id to season
    }
}
