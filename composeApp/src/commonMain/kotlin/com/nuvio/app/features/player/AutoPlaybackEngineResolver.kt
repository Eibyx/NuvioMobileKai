package com.nuvio.app.features.player

/**
 * Mirrors NuvioTV's `resolveAutoInternalPlayerEngine()`: anime goes to libmpv, everything else to
 * ExoPlayer, HDR/Dolby Vision takes precedence over anime.
 */
internal fun resolveAutoPlaybackEngine(
    playbackEngineSetting: AndroidPlaybackEngine,
    videoId: String?,
    streamFilename: String?,
    streamName: String?,
    streamDescription: String?,
    contentTitle: String?,
    genres: List<String>,
    country: String?,
    language: String? = null,
    hasAniListLink: Boolean = false,
): AndroidPlaybackEngine? = autoPlaybackEngineChoice(
    playbackEngineSetting, videoId, streamFilename, streamName, streamDescription,
    contentTitle, genres, country, language, hasAniListLink,
)?.engine

/** The engine Auto picks and why, for the log. */
internal data class AutoPlaybackEngineChoice(val engine: AndroidPlaybackEngine, val reason: String)

internal fun autoPlaybackEngineChoice(
    playbackEngineSetting: AndroidPlaybackEngine,
    videoId: String?,
    streamFilename: String?,
    streamName: String?,
    streamDescription: String?,
    contentTitle: String?,
    genres: List<String>,
    country: String?,
    language: String? = null,
    hasAniListLink: Boolean = false,
): AutoPlaybackEngineChoice? {
    if (playbackEngineSetting != AndroidPlaybackEngine.Auto) return null

    val streamMetadataText = buildString {
        streamFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        streamDescription?.let { appendLine(it) }
        append(contentTitle.orEmpty())
    }

    // HDR / Dolby Vision takes precedence: libmpv would improperly tonemap it to SDR.
    if (HDR_OR_DOLBY_VISION_REGEX.containsMatchIn(streamMetadataText)) {
        return AutoPlaybackEngineChoice(AndroidPlaybackEngine.ExoPlayer, "hdr")
    }

    val animeReason = animeReason(videoId, genres, country, language, hasAniListLink)
    if (animeReason != null) return AutoPlaybackEngineChoice(AndroidPlaybackEngine.Libmpv, animeReason)

    return AutoPlaybackEngineChoice(AndroidPlaybackEngine.ExoPlayer, "default")
}

/** Whether a title is anime, with the same rules Auto uses to send it to mpv. */
internal fun isAnimeContent(
    videoId: String?,
    genres: List<String>,
    country: String?,
    language: String? = null,
    hasAniListLink: Boolean = false,
): Boolean = animeReason(videoId, genres, country, language, hasAniListLink) != null

private fun animeReason(
    videoId: String?,
    genres: List<String>,
    country: String?,
    language: String?,
    hasAniListLink: Boolean,
): String? {
    val hasAnimeId = videoId?.startsWith("kitsu:") == true ||
        videoId?.startsWith("mal:") == true ||
        videoId?.startsWith("anilist:") == true
    if (hasAnimeId) return "anime-id"
    // On the user's AniList: anime, whatever language the catalog writes its genres in.
    if (hasAniListLink) return "anilist"
    if (genres.any(::isAnimeGenre)) return "anime-genre"
    val isJapaneseAnimation = genres.any(::isAnimationGenre) && (isJapan(country) || isJapanese(language))
    return if (isJapaneseAnimation) "japanese-animation" else null
}


/** "Anime" as catalogs write it, in any language. */
internal fun isAnimeGenre(genre: String): Boolean = genre.normalizedToken() in ANIME_GENRES

/** "Animation" as catalogs write it: TMDB and addons translate it ("Animación", "Animação"...). */
internal fun isAnimationGenre(genre: String): Boolean = genre.normalizedToken() in ANIMATION_GENRES

private val ANIME_GENRES = setOf("anime", "animes", "アニメ", "动漫", "애니")

private val ANIMATION_GENRES = setOf(
    "animation", "animacion", "animacao", "animazione", "animatie", "animationsfilm", "animasi",
    "animaatio", "animacja", "animace", "animasyon", "анимация", "мультфильм", "アニメーション",
    "动画", "動畫", "애니메이션",
)

private val JAPAN_NAMES = setOf("japan", "jp", "jpn", "japon", "japao", "giappone", "japonya", "japonia", "日本", "япония")

private val JAPANESE_LANGUAGES = setOf("ja", "jpn", "japanese", "japones", "japonais", "giapponese", "japanisch", "日本語")

/** Lower case without accents, so "Animación" and "Japón" match their plain spelling. */
private fun String.normalizedToken(): String = trim().lowercase().map { ACCENTS[it] ?: it }.joinToString("")

private val ACCENTS = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    'ç' to 'c', 'ñ' to 'n',
)

private val HDR_OR_DOLBY_VISION_REGEX = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""")

// TMDB enrichment rewrites `country` as comma-joined ISO codes ("US, GB"), addon metadata as
// full names ("Japan") — match whole comma-separated tokens.
private fun isJapan(country: String?): Boolean =
    country?.split(',')?.any { token -> token.normalizedToken() in JAPAN_NAMES } == true

private fun isJapanese(language: String?): Boolean =
    language?.split(',')?.any { token -> token.normalizedToken() in JAPANESE_LANGUAGES } == true
