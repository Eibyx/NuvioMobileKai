package com.nuvio.app.features.home

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.AnimeIdFallback
import com.nuvio.app.features.tracking.parseAnimeIdRef
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The ids one title goes by. The same anime reaches Nuvio as `kitsu:…` from one catalogue, `tt…`
 * from another, `tmdb:…` or `mal:…` from a third (or from an AniList import), and each id kept its
 * own history and its own Continue Watching card. Ids are linked here as they are learnt — from
 * the metadata of the page (its IMDb and TVDB ids), from ARM for anime ids and from TMDB for
 * `tmdb:` ids — so Home can treat them as one series.
 *
 * Keys carry the kind ("series"/"movie") because TMDB numbers movies and shows separately.
 */
internal object ContinueWatchingIdentity {
    private const val TAG = "Home/ContinueWatching"

    private val mutableGroups = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Every linked key to its whole group; ids never linked are absent. */
    val groups: StateFlow<Map<String, Set<String>>> = mutableGroups.asStateFlow()

    /** Ids already looked up this session. */
    private val looked = MutableStateFlow<Set<String>>(emptySet())

    fun key(type: String, id: String): String = "${kindOf(type)}|${id.trim().lowercase()}"

    /** The keys of [id]: itself and every id linked to it. */
    fun aliases(type: String, id: String, groups: Map<String, Set<String>> = this.groups.value): Set<String> {
        val own = key(type, id)
        return groups[own] ?: setOf(own)
    }

    /** One stable id per group (the smallest key), to merge seeds of the same series. */
    fun canonical(type: String, id: String, groups: Map<String, Set<String>> = this.groups.value): String {
        val group = groups[key(type, id)] ?: return id
        return group.min().substringAfter('|')
    }

    fun link(type: String, ids: Collection<String?>) {
        val keys = ids.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.map { key(type, it) }.toSet()
        if (keys.size < 2) return
        mutableGroups.update { current -> current.linking(keys) }
    }

    /** What a page's metadata says: the id it was asked for is also its own, IMDb and TVDB ids. */
    fun learn(requestedId: String, meta: MetaDetails) {
        link(
            meta.type,
            listOf(
                requestedId,
                meta.id,
                meta.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) },
                meta.tvdbId?.takeIf(String::isNotBlank)?.let { "tvdb:$it" },
            ),
        )
    }

    /**
     * Learns the other ids of [id] from what is at hand: cached metadata first, then ARM for anime
     * ids and TMDB for `tmdb:` ids (each asked once per session; both cache their answers).
     */
    suspend fun resolve(type: String, id: String) {
        MetaDetailsRepository.peek(type, id)?.let { meta -> learn(id, meta) }
        val ownKey = key(type, id)
        var first = false
        looked.update { seen ->
            first = ownKey !in seen
            if (first) seen + ownKey else seen
        }
        if (!first) return
        try {
            val anime = parseAnimeIdRef(id)
            when {
                anime != null && anime.episode == null -> {
                    val external = AnimeIdFallback.externalIds(anime) ?: return
                    link(type, listOf(id, external.imdb, external.tmdb?.let { "tmdb:$it" }))
                }
                id.startsWith("tmdb:", ignoreCase = true) -> {
                    val tmdbId = id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return
                    val imdb = TmdbService.tmdbToImdb(tmdbId, if (kindOf(type) == "movie") "movie" else "tv")
                    link(type, listOf(id, imdb?.takeIf { it.startsWith("tt", ignoreCase = true) }))
                }
            }
        } catch (error: CancellationException) {
            looked.update { it - ownKey }
            throw error
        } catch (error: Throwable) {
            InAppLogger.info(TAG, "Could not look up the other ids of $id: ${InAppLogger.throwableSummary(error)}")
        }
    }

    private val lastReport = MutableStateFlow<List<String>?>(null)

    /** Writes the cards to the diagnostics log when they change: ids, kind and origin only. */
    fun report(lines: List<String>) {
        if (lastReport.value == lines) return
        lastReport.value = lines
        InAppLogger.info(TAG, if (lines.isEmpty()) "Cards: none" else "Cards:\n" + lines.joinToString("\n"))
    }

    internal fun clear() {
        mutableGroups.value = emptyMap()
        looked.value = emptySet()
    }

    private fun kindOf(type: String): String =
        if (type.trim().equals("movie", ignoreCase = true)) "movie" else "series"
}

/** [keys] joined to every group they touch, all of it one group now. */
internal fun Map<String, Set<String>>.linking(keys: Set<String>): Map<String, Set<String>> {
    val merged = keys.flatMapTo(linkedSetOf()) { key -> this[key] ?: setOf(key) }
    if (keys.all { this[it] == merged }) return this
    return this + merged.associateWith { merged }
}
