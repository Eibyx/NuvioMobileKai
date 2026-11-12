package com.nuvio.app.features.cloudstorage

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerStreamSource
import com.nuvio.app.features.servers.catalogIds
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloud files as stream sources, next to add-ons, plugins and media servers. Each account is one
 * group (`server:cloud:<id>`), so the player treats it like a media server source: the URL is made
 * when the stream is chosen, it is never saved as the last link or opened in an external player.
 */
internal object CloudStreams {
    private val log = Logger.withTag("CloudStreams")
    const val CONNECTION_PREFIX = "cloud:"

    fun isCloudTarget(target: ServerPlaybackTarget?): Boolean =
        target?.item?.connectionId?.startsWith(CONNECTION_PREFIX) == true

    fun sources(type: String, videoId: String, season: Int?, episode: Int?): List<ServerStreamSource> {
        if (ServerItemRef.isServerId(videoId)) return emptyList()
        val accounts = runCatching { CloudStorageRepository.enabledAccounts() }.getOrDefault(emptyList())
            .filter { account -> account.manualLinks.isNotEmpty() || CloudStorageRepository.index(account.id)?.files?.isNotEmpty() == true }
        if (accounts.isEmpty()) return emptyList()
        accounts.forEach(CloudStorageRepository::refreshIfStale)
        return accounts.map { account ->
            ServerStreamSource(
                addonId = groupId(account.id),
                addonName = account.sourceLabel(),
                preferred = account.showFirst,
            ) { streams(account, type, videoId, season, episode) }
        }
    }

    private suspend fun streams(
        account: CloudAccount,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val request = request(type, videoId, season, episode) ?: run {
            InAppLogger.info("Cloud/Match", "${account.kind.id} no request for id=$videoId type=$type")
            return emptyList()
        }
        // Opening an episode re-indexes the account first (when not done in the last minutes), so a
        // file uploaded a moment ago is found without a manual sync.
        val files = CloudStorageRepository.freshIndex(account.id)?.files.orEmpty()
        val matches = CloudMatcher.match(files, request, account.manualLinks)
        log.d { "${matches.size} matches in a ${account.kind.id} account" }
        val (titled, misses) = if (matches.isEmpty()) CloudMatcher.nearMisses(files, request) else (0 to emptyList())
        InAppLogger.info(
            "Cloud/Match",
            "${account.kind.id} files=${files.size} ids=${request.contentIds.joinToString(",")} " +
                "s=${request.season} e=${request.episode} abs=${request.absoluteEpisode} " +
                "aliases=${request.aliases.size}[${request.aliases.take(4).joinToString("|") { it.title }}] matches=${matches.size}" +
                if (matches.isEmpty()) " sameTitle=$titled read=${misses.joinToString(",")}" else "",
        )
        return matches.map { match -> match.toStream(account, request) }
    }

    internal suspend fun request(type: String, videoId: String, season: Int?, episode: Int?): CloudMatchRequest? {
        val parts = videoId.split(':')
        val parentSize = if (videoId.startsWith("tt")) 1 else 2
        if (parts.size < parentSize) return null
        val parentId = parts.take(parentSize).joinToString(":")
        val position = parts.drop(parentSize).mapNotNull { it.toIntOrNull() }
        val isSeries = !type.equals("movie", ignoreCase = true)
        val meta: MetaDetails? = MetaDetailsRepository.peek(type, parentId)
            ?: withTimeoutOrNull(META_TIMEOUT_MS) { runCatching { MetaDetailsRepository.fetch(type, parentId) }.getOrNull() }
        val ids = parseTrackingExternalIds(parentId).let { parsed ->
            val metaImdb = meta?.imdbId?.takeIf { it.startsWith("tt") }
            if (parsed.imdb == null && metaImdb != null) parsed.copy(imdb = metaImdb) else parsed
        }
        val trackerEntry = isSeries && position.size == 1 && season == null
        val (requestSeason, requestEpisode) = when {
            !isSeries -> null to null
            position.size >= 2 -> (season ?: position[0]) to (episode ?: position[1])
            // Tracker ids (`kitsu:13:5`) number episodes within their own entry.
            position.size == 1 -> (season ?: 1) to (episode ?: position[0])
            else -> season to episode
        }
        if (isSeries && (requestSeason == null || requestEpisode == null)) return null
        val aliases = CloudTitleAliases.aliases("$type:$parentId", type, meta, ids)
        val perSeason = meta?.videos.orEmpty()
            .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .groupingBy { it.season!! }
            .eachCount()
        val video = meta?.videos.orEmpty().firstOrNull { it.season == requestSeason && it.episode == requestEpisode }
        // A tracker entry's "season 1" is the entry itself; its files carry the show's season.
        val matchSeason = if (trackerEntry) trackerEntrySeason(aliases) ?: requestSeason else requestSeason
        return CloudMatchRequest(
            displayTitle = meta?.name?.takeIf { it.isNotBlank() },
            episodeTitle = video?.title?.takeIf { it.isNotBlank() && !GENERIC_EPISODE_TITLE.matches(it.trim()) },
            runtimeMinutes = video?.runtime ?: meta?.runtime?.let { runtime -> DIGITS.find(runtime)?.value?.toIntOrNull() },
            isSeries = isSeries,
            contentIds = (listOf(parentId) + ids.catalogIds()).toSet(),
            aliases = aliases,
            year = meta?.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
            season = matchSeason,
            episode = requestEpisode,
            absoluteEpisode = if (matchSeason != null && requestEpisode != null) {
                absoluteEpisodeNumber(perSeason, matchSeason, requestEpisode)
            } else {
                null
            },
        )
    }

    /**
     * Laid out like the debrid add-on results: a headline of tags and one line per fact. The
     * provider is not repeated: the source chip above the list already names it.
     */
    private fun CloudMatch.toStream(account: CloudAccount, request: CloudMatchRequest): StreamItem {
        val release = release
        val quality = (release.resolution ?: file.height?.let { "${it}p" })?.uppercase()
        val format = (release.container ?: file.name.substringAfterLast('.', "")).uppercase().takeIf { it.isNotBlank() }
        val sidecars = CloudStorageRepository.index(account.id)?.subtitles?.let { CloudSidecarSubtitles.find(file, it) }.orEmpty()
        val headline = listOfNotNull(
            quality?.let { "🎞️ $it" },
            release.source?.let { "💎 ${sourceLabel(it)}" },
            format?.let { "📼 $it" },
            if (manual) "📌 MANUAL" else null,
        ).joinToString(" • ")
        // Title, year and episode as the catalogue (TMDB) has them, not as the file is named.
        val title = request.displayTitle ?: release.title.takeIf { it.isNotBlank() }
        val year = request.year ?: release.year
        val episodeLine = if (request.isSeries && request.season != null && request.episode != null) {
            "📺 EPISODE: S${request.season.twoDigits()} • E${request.episode.twoDigits()}" +
                (release.episode?.takeIf { it != request.episode }?.let { " (FILE ${it.twoDigits()})" } ?: "")
        } else {
            null
        }
        val runtimeMs = file.durationMs ?: request.runtimeMinutes?.let { it * 60_000L }
        val lines = listOfNotNull(
            title?.let { "🎬 TITLE: $it" + (year?.let { value -> " ($value)" } ?: "") },
            episodeLine,
            request.episodeTitle?.let { "📝 EP TITLE: $it" },
            release.videoCodec?.let { "🎞️ CODEC: ${codecLabel(it)}" },
            release.audio?.let { "🔊 AUDIO: $it" },
            sidecars.takeIf { it.isNotEmpty() }?.let { list ->
                "💬 SUBTITLES: " + list.joinToString(" • ") { it.label.ifBlank { it.language.uppercase() } }
            },
            listOfNotNull(
                file.sizeBytes.takeIf { it > 0 }?.let { "📦 SIZE: ${formatBytes(it)}" },
                runtimeMs?.let { "RUNTIME: ${runtime(it)}" },
            ).joinToString(" • ").takeIf { it.isNotBlank() },
            release.group?.let { "🏷️ GROUP: $it" },
            "📁 ${file.parentPath.substringAfterLast('/').ifBlank { "/" }}",
            "📄 ${file.name}",
            if (manual) "🔗 ID MATCHED (MANUAL)" else "🎯 MATCH: TITLE • ${if (request.isSeries) "EPISODE" else "YEAR"}",
        )
        return StreamItem(
            name = headline.ifBlank { file.name },
            description = lines.joinToString("\n"),
            sourceName = account.sourceLabel(),
            addonName = account.sourceLabel(),
            addonId = groupId(account.id),
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "cloud:${account.id}:${release.group.orEmpty()}:${quality.orEmpty()}",
                videoSize = file.sizeBytes.takeIf { it > 0 },
                filename = file.name,
            ),
            serverTarget = ServerPlaybackTarget(ServerItemRef("$CONNECTION_PREFIX${account.id}", file.id), mediaSourceId = null),
        )
    }

    private fun sourceLabel(source: String): String = when (source) {
        "BD" -> "BLU-RAY"
        "Remux" -> "REMUX"
        else -> source.uppercase()
    }

    private fun codecLabel(codec: String): String = when (codec) {
        "HEVC" -> "H.265 HEVC"
        "H.264" -> "H.264 AVC"
        else -> codec
    }

    private fun runtime(durationMs: Long): String {
        val minutes = durationMs / 60_000
        val seconds = (durationMs / 1_000) % 60
        return if (minutes >= 60) "${minutes / 60}h:${(minutes % 60).toInt().twoDigits()}m" else "${minutes}m:${seconds.toInt().twoDigits()}s"
    }

    /**
     * The name the user gave the account ("Gdrive"). An unnamed account is named after its e-mail
     * address when it connects; that is too long for a chip, so it shows the service's name.
     */
    private fun CloudAccount.sourceLabel(): String =
        name.trim().takeIf { it.isNotEmpty() && '@' !in it } ?: kind.displayName

    private fun groupId(accountId: String) = "server:cloud:$accountId"

    private fun Int.twoDigits(): String = toString().padStart(2, '0')

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> {
            val hundredths = (bytes + 5_000_000L) / 10_000_000L
            "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
        }
        bytes >= 1_000_000L -> "${(bytes + 500_000L) / 1_000_000L} MB"
        else -> "${(bytes + 500L) / 1_000L} KB"
    }

    private val GENERIC_EPISODE_TITLE = Regex("(?i)(episode|episodio|ep\\.?)\\s*\\d+")
    private val DIGITS = Regex("\\d+")
    private val YEAR = Regex("""(19|20)\d{2}""")
    private const val META_TIMEOUT_MS = 8_000L
}

/** Turns a cloud stream into something the player can open, when the stream is chosen. */
internal object CloudPlayback {
    private val log = Logger.withTag("CloudPlayback")

    suspend fun prepare(stream: StreamItem): StreamItem {
        val target = stream.serverTarget ?: return stream
        val accountId = target.item.connectionId.removePrefix(CloudStreams.CONNECTION_PREFIX)
        val provider = CloudStorageRepository.provider(accountId) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val file = CloudStorageRepository.file(accountId, target.item.itemId) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val sidecars = CloudStorageRepository.index(accountId)?.subtitles?.let { CloudSidecarSubtitles.find(file, it) }.orEmpty()
        if (CloudPlaybackGateway.servesLocally) {
            val url = CloudPlaybackGateway.open(file.name, file.sizeBytes.takeIf { it > 0 }, file.mimeType ?: cloudMimeTypeFor(file.name)) { refresh ->
                provider.resolve(file, refresh)
            }
            val subtitles = sidecars.map { sidecar ->
                val subtitleUrl = CloudPlaybackGateway.open(
                    sidecar.file.name,
                    sidecar.file.sizeBytes.takeIf { it > 0 },
                    cloudSubtitleMimeTypeFor(sidecar.file.name),
                ) { refresh -> provider.resolve(sidecar.file, refresh) }
                StreamSubtitle(url = subtitleUrl, language = sidecar.language, name = sidecar.label)
            }
            if (subtitles.isNotEmpty()) synchronized(lock) { sidecarUrls[url] = subtitles.map { it.url } }
            log.i { "Serving a cloud file locally with ${subtitles.size} subtitle files" }
            return stream.copy(
                url = url,
                externalSubtitles = subtitles + stream.externalSubtitles,
                behaviorHints = stream.behaviorHints.copy(proxyHeaders = null),
            )
        }
        val source = provider.resolve(file, forceRefresh = false)
        val subtitles = sidecars.mapNotNull { sidecar ->
            runCatching { provider.resolve(sidecar.file, forceRefresh = false) }.getOrNull()?.let {
                StreamSubtitle(url = it.url, language = sidecar.language, name = sidecar.label, headers = it.headers)
            }
        }
        return stream.copy(
            url = source.url,
            externalSubtitles = subtitles + stream.externalSubtitles,
            behaviorHints = stream.behaviorHints.copy(
                proxyHeaders = com.nuvio.app.features.streams.StreamProxyHeaders(request = source.headers),
            ),
        )
    }

    fun stop(url: String?) {
        CloudPlaybackGateway.close(url)
        synchronized(lock) { url?.let(sidecarUrls::remove) }?.forEach(CloudPlaybackGateway::close)
    }

    private val lock = SynchronizedObject()
    private val sidecarUrls = mutableMapOf<String, List<String>>()
}
