package com.nuvio.app.features.cloudstorage

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerStreamSource
import com.nuvio.app.features.servers.catalogIds
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
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
        val request = request(type, videoId, season, episode) ?: return emptyList()
        val files = CloudStorageRepository.index(account.id)?.files.orEmpty()
        val matches = CloudMatcher.match(files, request, account.manualLinks)
        log.d { "${matches.size} matches in a ${account.kind.id} account" }
        return matches.map { match -> match.toStream(account) }
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
        return CloudMatchRequest(
            isSeries = isSeries,
            contentIds = (listOf(parentId) + ids.catalogIds()).toSet(),
            aliases = aliases,
            year = meta?.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
            season = requestSeason,
            episode = requestEpisode,
            absoluteEpisode = if (requestSeason != null && requestEpisode != null) {
                absoluteEpisodeNumber(perSeason, requestSeason, requestEpisode)
            } else {
                null
            },
        )
    }

    private fun CloudMatch.toStream(account: CloudAccount): StreamItem {
        val release = release
        val episodeLabel = release.episode?.let { number ->
            listOfNotNull(release.season?.let { "S${it.twoDigits()}" }, "EP${number.twoDigits()}").joinToString("")
        }
        val quality = release.resolution ?: file.height?.let { "${it}p" }
        val format = (release.container ?: file.name.substringAfterLast('.', "")).uppercase().takeIf { it.isNotBlank() }
        val details = listOfNotNull(release.title.takeIf { it.isNotBlank() }, episodeLabel, quality, release.videoCodec, format)
        return StreamItem(
            name = listOfNotNull(account.kind.displayName, quality).joinToString(" · "),
            title = details.joinToString(" | "),
            description = listOfNotNull(
                details.joinToString(" | "),
                listOfNotNull(file.sizeBytes.takeIf { it > 0 }?.let(::formatBytes), release.audio, release.group).joinToString(" · ").takeIf { it.isNotBlank() },
                file.name,
                if (manual) "✓" else null,
            ).joinToString("\n"),
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

    private fun CloudAccount.sourceLabel(): String =
        if (name.equals(kind.displayName, ignoreCase = true)) kind.displayName else "${kind.displayName} · $name"

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
        if (CloudPlaybackGateway.servesLocally) {
            val url = CloudPlaybackGateway.open(file.name, file.sizeBytes.takeIf { it > 0 }, file.mimeType ?: cloudMimeTypeFor(file.name)) { refresh ->
                provider.resolve(file, refresh)
            }
            log.i { "Serving a cloud file locally" }
            return stream.copy(url = url, behaviorHints = stream.behaviorHints.copy(proxyHeaders = null))
        }
        val source = provider.resolve(file, forceRefresh = false)
        return stream.copy(
            url = source.url,
            behaviorHints = stream.behaviorHints.copy(
                proxyHeaders = com.nuvio.app.features.streams.StreamProxyHeaders(request = source.headers),
            ),
        )
    }

    fun stop(url: String?) = CloudPlaybackGateway.close(url)
}
