package com.nuvio.app.features.subtitledownloader

import com.nuvio.app.features.cloudstorage.CloudStorageException
import com.nuvio.app.features.cloudstorage.CloudFailure
import com.nuvio.app.features.cloudstorage.CloudStorageRepository
import com.nuvio.app.features.cloudstorage.CloudStreams
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.subtitledownloader.engine.RemoteEndpoint
import com.nuvio.app.features.subtitledownloader.engine.RemoteSource
import com.nuvio.app.features.subtitledownloader.engine.SubtitleSession

internal class UnsupportedSourceException(message: String) : Exception(message)

/**
 * The remote file behind a stream, for the downloader: a cloud file straight from its provider
 * (renewed through the provider when its link stops working), a debrid link resolved the way
 * playback resolves it, a media server's link, or a plain HTTP link with the addon's headers.
 */
internal object SubtitleSources {
    suspend fun forStream(stream: StreamItem, season: Int?, episode: Int?): RemoteSource {
        val target = stream.serverTarget
        if (stream.playableDirectUrl == null && CloudStreams.isCloudTarget(target)) {
            val accountId = target!!.item.connectionId.removePrefix(CloudStreams.CONNECTION_PREFIX)
            val provider = CloudStorageRepository.provider(accountId) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
            val file = CloudStorageRepository.file(accountId, target.item.itemId) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
            return object : RemoteSource {
                override val stableId = "cloud:${target.item.connectionId}:${file.id}"
                override val sizeHint = file.sizeBytes.takeIf { it > 0 }
                override val canRefresh = true
                override suspend fun resolve(forceRefresh: Boolean): RemoteEndpoint {
                    val source = provider.resolve(file, forceRefresh)
                    return RemoteEndpoint(source.url, source.headers)
                }
            }
        }
        if (target != null && !CloudStreams.isCloudTarget(target) && stream.playableDirectUrl == null) {
            val prepared = ServerPlayback.prepare(stream)
            return direct(prepared, "server:${target.item.connectionId}:${target.item.itemId}", refresh = { ServerPlayback.prepare(stream) })
        }
        if (stream.playableDirectUrl == null && DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            suspend fun resolveDebrid(): StreamItem = when (val r = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)) {
                is DirectDebridPlayableResult.Success -> r.stream
                is DirectDebridPlayableResult.MissingApiKey -> throw UnsupportedSourceException("missing-api-key")
                is DirectDebridPlayableResult.NotCached -> throw UnsupportedSourceException("not-cached")
                else -> throw UnsupportedSourceException("resolve-failed")
            }
            val resolved = resolveDebrid()
            return direct(resolved, identity(stream), refresh = { resolveDebrid() })
        }
        if (stream.playableDirectUrl == null) {
            throw UnsupportedSourceException(if (stream.infoHash != null) "torrent" else "no-link")
        }
        return direct(stream, identity(stream), refresh = null)
    }

    private fun direct(stream: StreamItem, id: String, refresh: (suspend () -> StreamItem)?): RemoteSource {
        val first = stream.playableDirectUrl ?: throw UnsupportedSourceException("no-link")
        return object : RemoteSource {
            private var current = stream
            override val stableId = id
            override val sizeHint = stream.behaviorHints.videoSize?.takeIf { it > 0 }
            override val canRefresh = refresh != null
            override suspend fun resolve(forceRefresh: Boolean): RemoteEndpoint {
                if (forceRefresh && refresh != null) current = refresh()
                val url = current.playableDirectUrl ?: first
                return RemoteEndpoint(url, current.behaviorHints.proxyHeaders?.request.orEmpty())
            }
        }
    }

    /** What names a stream's file across fresh links: its file name and size, else its link without the query. */
    fun identity(stream: StreamItem): String {
        val name = stream.behaviorHints.filename
        val size = stream.behaviorHints.videoSize
        if (!name.isNullOrBlank() && size != null && size > 0) return "file:$name:$size"
        val url = (stream.playableDirectUrl ?: stream.url).orEmpty().substringBefore('?')
        return "url:" + SubtitleSession.sha256Hex(url.toByteArray()).take(24)
    }
}
