package com.nuvio.app.features.cloudstorage

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJson
import com.nuvio.app.features.anilist.AniListConfig
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The names a title may carry in file names: Nuvio's title, TMDB's original title, and AniList's
 * romaji, English, native and synonym titles (each AniList entry tied to its TVDB season when the
 * mapping says so). AniList is found from the ids Nuvio already has, through the same ARM mapping
 * [com.nuvio.app.features.tracking.AnimeIdFallback] uses.
 */
internal object CloudTitleAliases {
    private val log = Logger.withTag("CloudTitleAliases")
    private val lock = SynchronizedObject()
    private val cache = LinkedHashMap<String, List<CloudTitleAlias>>()

    suspend fun aliases(cacheKey: String, type: String, meta: MetaDetails?, ids: TrackingExternalIds): List<CloudTitleAlias> {
        synchronized(lock) { cache[cacheKey] }?.let { return it }
        val result = buildList {
            meta?.name?.takeIf { it.isNotBlank() }?.let { add(CloudTitleAlias(it)) }
            ids.tmdb?.let { TmdbMetadataService.cachedOriginalTitle(it.toString(), type) }
                ?.takeIf { it.isNotBlank() }?.let { add(CloudTitleAlias(it)) }
            addAll(withTimeoutOrNull(ANILIST_TIMEOUT_MS) { aniListAliases(ids) }.orEmpty())
        }.distinctBy { it.title.lowercase() to it.seasonHint }
        synchronized(lock) {
            cache[cacheKey] = result
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return result
    }

    private suspend fun aniListAliases(ids: TrackingExternalIds): List<CloudTitleAlias> = try {
        val entries = ids.anilist?.let { listOf(it to null) } ?: armEntries(ids)
        entries.take(MAX_ANILIST_ENTRIES).flatMap { (id, season) -> aniListTitles(id).map { CloudTitleAlias(it, season) } }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        log.d { "AniList titles unavailable: ${error::class.simpleName}" }
        emptyList()
    }

    private suspend fun armEntries(ids: TrackingExternalIds): List<Pair<Long, Int?>> {
        val (source, id) = when {
            ids.mal != null -> "myanimelist" to ids.mal.toString()
            ids.kitsu != null -> "kitsu" to ids.kitsu.toString()
            ids.anidb != null -> "anidb" to ids.anidb.toString()
            ids.imdb != null -> "imdb" to ids.imdb
            ids.tmdb != null -> "themoviedb" to ids.tmdb.toString()
            else -> return emptyList()
        }
        val body = httpGetText("${AniListConfig.ARM_BASE_URL}/ids?source=$source&id=$id")
        val element = Json.parseToJsonElement(body)
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return objects.mapNotNull { entry ->
            val aniList = entry.primitive("anilist")?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return@mapNotNull null
            aniList to entry.primitive("thetvdb-season")?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        }
    }

    private suspend fun aniListTitles(id: Long): List<String> {
        val query = buildJsonObject {
            put("query", "query(\$id:Int){Media(id:\$id,type:ANIME){title{romaji english native} synonyms}}")
            putJsonObject("variables") { put("id", id) }
        }
        val media = (Json.parseToJsonElement(httpPostJson(AniListConfig.GRAPHQL_URL, query.toString())) as? JsonObject)
            ?.obj("data")?.obj("Media") ?: return emptyList()
        val title = media.obj("title")
        val names = listOfNotNull(
            title?.primitive("romaji")?.contentOrNull,
            title?.primitive("english")?.contentOrNull,
            title?.primitive("native")?.contentOrNull,
        ) + (media["synonyms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return names.filter { it.isNotBlank() }.distinct().take(12)
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonElement) as? JsonPrimitive

    private const val CACHE_SIZE = 64
    private const val MAX_ANILIST_ENTRIES = 4
    private const val ANILIST_TIMEOUT_MS = 6_000L
}
