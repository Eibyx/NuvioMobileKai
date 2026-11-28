package com.nuvio.app.features.artwork

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.nuvio.app.core.logging.InAppLogger

/** One image a title can use, with where it comes from. */
data class ArtworkCandidate(
    val url: String,
    val previewUrl: String,
    val source: String,
    val language: String?,
    val detail: String?,
)

data class ArtworkCandidates(
    val logos: List<ArtworkCandidate> = emptyList(),
    val backgrounds: List<ArtworkCandidate> = emptyList(),
    val posters: List<ArtworkCandidate> = emptyList(),
    val fanartUsed: Boolean = false,
    val fanartFailed: Boolean = false,
    /** Why Fanart.tv gave nothing: "no-id", "empty", "key", "network". */
    val fanartIssue: String? = null,
) {
    fun of(kind: ArtworkKind) = when (kind) {
        ArtworkKind.LOGO -> logos
        ArtworkKind.BACKGROUND -> backgrounds
        ArtworkKind.POSTER -> posters
    }
}

/** Every logo, background and poster TMDB and (with a key) Fanart.tv have for a title. */
object ArtworkCandidateLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, ArtworkCandidates>()

    @Serializable
    private data class TmdbImageList(
        val logos: List<TmdbImageItem> = emptyList(),
        val backdrops: List<TmdbImageItem> = emptyList(),
        val posters: List<TmdbImageItem> = emptyList(),
    )

    @Serializable
    private data class TmdbImageItem(
        @SerialName("file_path") val filePath: String? = null,
        @SerialName("iso_639_1") val language: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        @SerialName("vote_average") val vote: Double? = null,
    )

    @Serializable
    private data class TmdbExternal(@SerialName("tvdb_id") val tvdbId: Int? = null, @SerialName("imdb_id") val imdbId: String? = null)

    suspend fun load(id: String, type: String, imdbId: String?, tvdbId: String?, language: String?): ArtworkCandidates = coroutineScope {
        val fanartKey = ArtworkChoices.fanartKey
        val cacheKey = "$type:$id:${fanartKey != null}"
        cache[cacheKey]?.let { return@coroutineScope it }
        val mediaType = if (type.lowercase() in setOf("movie", "film")) "movie" else "tv"
        val tmdbId = runCatching { TmdbService.ensureTmdbId(id, mediaType, imdbId) }.getOrNull()
        val tmdb = async { tmdbId?.let { tmdbImages(it, mediaType) } }
        val fanart = async {
            if (fanartKey == null) return@async null to null
            val target = if (mediaType == "tv") {
                tvdbId?.removePrefix("tvdb:")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: tmdbId?.let { externalTvdb(it) }
            } else {
                tmdbId ?: imdbId
            }
            if (target == null) return@async null to "no-id"
            runCatching { fanartImages(target, mediaType, fanartKey) }.fold(
                onSuccess = { found ->
                    val empty = found.logos.isEmpty() && found.backgrounds.isEmpty() && found.posters.isEmpty()
                    found to (if (empty) "empty" else null)
                },
                onFailure = { e ->
                    val message = e.message.orEmpty()
                    InAppLogger.warn("Artwork", "Fanart.tv $mediaType id=$target failed: ${e::class.simpleName} ${message.replace(fanartKey, "[REDACTADO]")}")
                    null to (if ("401" in message || "403" in message) "key" else "network")
                },
            )
        }
        val t = tmdb.await()
        val (fan, issue) = fanart.await()
        val failed = issue == "key" || issue == "network"
        fun sorted(list: List<ArtworkCandidate>) = list.sortedWith(
            compareByDescending<ArtworkCandidate> { it.language != null && it.language == language }
                .thenByDescending { it.language == null || it.language == "en" || it.language == "00" },
        )
        val result = ArtworkCandidates(
            logos = sorted(fan?.logos.orEmpty() + t?.logos.orEmpty()),
            backgrounds = fan?.backgrounds.orEmpty() + t?.backgrounds.orEmpty(),
            posters = sorted(fan?.posters.orEmpty() + t?.posters.orEmpty()),
            fanartUsed = fan != null,
            fanartFailed = failed,
            fanartIssue = issue,
        )
        cache[cacheKey] = result
        result
    }

    private suspend fun tmdbImages(tmdbId: String, mediaType: String): ArtworkCandidates? {
        val key = TmdbSettingsRepository.effectiveApiKey().takeIf { it.isNotBlank() } ?: return null
        val url = buildTmdbUrl(endpoint = "$mediaType/$tmdbId/images", apiKey = key, query = emptyMap())
        val list = runCatching { json.decodeFromString<TmdbImageList>(httpGetText(url)) }.getOrNull() ?: return null
        fun map(items: List<TmdbImageItem>, preview: String) = items.mapNotNull { item ->
            val path = item.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArtworkCandidate(
                url = "https://image.tmdb.org/t/p/original$path",
                previewUrl = "https://image.tmdb.org/t/p/$preview$path",
                source = "TMDB",
                language = item.language,
                detail = listOfNotNull(
                    if (item.width != null && item.height != null) "${item.width}×${item.height}" else null,
                    if (path.endsWith(".svg")) "SVG" else null,
                ).joinToString(" · ").ifBlank { null },
            )
        }
        return ArtworkCandidates(map(list.logos, "w500"), map(list.backdrops, "w780"), map(list.posters, "w342"))
    }

    private suspend fun externalTvdb(tmdbId: String): String? {
        val key = TmdbSettingsRepository.effectiveApiKey().takeIf { it.isNotBlank() } ?: return null
        val url = buildTmdbUrl(endpoint = "tv/$tmdbId/external_ids", apiKey = key, query = emptyMap())
        return runCatching { json.decodeFromString<TmdbExternal>(httpGetText(url)) }.getOrNull()?.tvdbId?.toString()
    }

    private suspend fun fanartImages(target: String, mediaType: String, key: String): ArtworkCandidates {
        val kind = if (mediaType == "tv") "tv" else "movies"
        val body = httpGetText("https://webservice.fanart.tv/v3/$kind/$target?api_key=$key")
        val root = json.parseToJsonElement(body) as? JsonObject ?: return ArtworkCandidates()
        fun list(name: String, label: String): List<ArtworkCandidate> = (root[name] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = (item["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = (item["lang"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val likes = (item["likes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            ArtworkCandidate(
                url = url,
                previewUrl = url.replace("/fanart/", "/preview/"),
                source = "Fanart.tv",
                language = lang,
                detail = listOfNotNull(label, likes.takeIf { it > 0 }?.let { "♥ $it" }).joinToString(" · "),
            ) to likes
        }.sortedByDescending { it.second }.map { it.first }
        return if (mediaType == "tv") {
            ArtworkCandidates(list("hdtvlogo", "HD logo") + list("clearlogo", "ClearLogo"), list("showbackground", "Fondo"), list("tvposter", "Póster"))
        } else {
            ArtworkCandidates(list("hdmovielogo", "HD logo") + list("movielogo", "Logo"), list("moviebackground", "Fondo"), list("movieposter", "Póster"))
        }
    }

    fun clearCache() = cache.clear()
}
