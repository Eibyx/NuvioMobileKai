package com.nuvio.app.features.tracking

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.anilist.AniListConfig
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An anime catalogue id as trackers write it: `mal:22`, `kitsu:13`, `anilist:22`, `anidb:56`, or a
 * video id with an absolute episode such as `mal:22:5` (also `mal:22:1:5`).
 */
internal data class AnimeIdRef(
    val source: String,
    val id: Long,
    val episode: Int? = null,
) {
    val contentId: String get() = "$source:$id"
}

internal val ANIME_ID_SOURCES = setOf("mal", "kitsu", "anilist", "anidb")

internal fun parseAnimeIdRef(value: String?): AnimeIdRef? {
    val parts = value?.trim()?.split(':') ?: return null
    if (parts.size !in 2..4) return null
    val source = parts[0].lowercase().takeIf { it in ANIME_ID_SOURCES } ?: return null
    val id = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val episode = when (parts.size) {
        2 -> null
        else -> parts.last().toIntOrNull()?.takeIf { it > 0 } ?: return null
    }
    return AnimeIdRef(source, id, episode)
}

/** The ids of the same title in the catalogues addons and TMDB understand. */
internal data class AnimeExternalIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdbSeason: Int? = null,
) {
    /** The id to load metadata and streams with: IMDb first, else TMDB. */
    val metaId: String? get() = imdb ?: tmdb?.let { "tmdb:$it" }
}

/**
 * The (season, episode) of the [absolute] episode in a show numbered by seasons, counting the
 * regular episodes in order and leaving specials (season 0) out.
 */
internal fun absoluteEpisodeCoordinates(videos: List<MetaVideo>, absolute: Int): Pair<Int, Int>? {
    if (absolute <= 0) return null
    return videos
        .mapNotNull { video ->
            val season = video.season?.takeIf { it > 0 } ?: return@mapNotNull null
            val episode = video.episode?.takeIf { it > 0 } ?: return@mapNotNull null
            season to episode
        }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))
        .getOrNull(absolute - 1)
}

/**
 * Translates anime ids no installed addon accepts into IMDb or TMDB through ARM (arm.haglund.dev),
 * which needs no API key, and absolute anime episodes into season/episode: Simkl's episode map when
 * Simkl is configured, else the show's own episode list.
 */
internal object AnimeIdFallback {
    private const val TAG = "Metadata/AnimeFallback"
    private const val MISS_TTL_MS = 10L * 60_000L

    /** Swapped in tests. */
    internal var lookupExternalIds: suspend (AnimeIdRef) -> AnimeExternalIds? = ::fetchFromArm
    internal var lookupEpisodeMapping: suspend (AnimeIdRef) -> TrackingAnimeIdMapper.AnimeImdbMapping? = { ref ->
        TrackingAnimeIdMapper.animeImdbMapping(ref.source, ref.id)
    }
    internal var now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private val found = mutableMapOf<String, AnimeExternalIds>()
    private val missedAt = mutableMapOf<String, Long>()

    suspend fun externalIds(ref: AnimeIdRef): AnimeExternalIds? {
        val key = ref.contentId
        lock.withLock {
            found[key]?.let { return it }
            missedAt[key]?.let { if (now() - it < MISS_TTL_MS) return null }
        }
        val ids = try {
            lookupExternalIds(ref)?.takeIf { it.metaId != null }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            InAppLogger.warn(TAG, "Lookup failed for ${ref.contentId}: ${InAppLogger.throwableSummary(error)}")
            null
        }
        lock.withLock {
            if (ids != null) {
                found[key] = ids
                missedAt.remove(key)
            } else {
                missedAt[key] = now()
            }
        }
        InAppLogger.info(TAG, "${ref.contentId} -> ${ids?.metaId ?: "no IMDb or TMDB id"}")
        return ids
    }

    suspend fun metaId(ref: AnimeIdRef): String? = externalIds(ref)?.metaId

    /**
     * The season/episode in the IMDb or TMDB show for [ref]'s absolute episode. [videos] loads the
     * show's episode list for the last-resort absolute count.
     */
    suspend fun episodeCoordinates(
        ref: AnimeIdRef,
        absolute: Int,
        videos: suspend () -> List<MetaVideo>?,
    ): Pair<Int, Int>? {
        val mapped = try {
            lookupEpisodeMapping(ref)?.imdbEpisodeFor(absolute)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (mapped != null) return mapped
        externalIds(ref)?.tvdbSeason?.takeIf { it > 0 }?.let { season -> return season to absolute }
        return videos()?.let { absoluteEpisodeCoordinates(it, absolute) }
    }

    internal suspend fun clearCache() = lock.withLock {
        found.clear()
        missedAt.clear()
    }

    private suspend fun fetchFromArm(ref: AnimeIdRef): AnimeExternalIds? {
        val source = when (ref.source) {
            "mal" -> "myanimelist"
            else -> ref.source
        }
        val body = httpGetText("${AniListConfig.ARM_BASE_URL}/ids?source=$source&id=${ref.id}")
        val value = json.parseToJsonElement(body) as? JsonObject ?: return null
        fun text(key: String): String? = (value[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        return AnimeExternalIds(
            imdb = text("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) },
            tmdb = text("themoviedb")?.toLongOrNull()?.takeIf { it > 0L },
            tvdbSeason = text("thetvdb-season")?.toIntOrNull(),
        )
    }
}
