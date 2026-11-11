package com.nuvio.app.core.images

import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options

/**
 * Artwork CDNs whose URLs never change content (each file has its own name or id). Their
 * responses do not let Cache-Control reuse the disk copy, so with [CacheControlCacheStrategy]
 * every app start downloaded the home hero's 1920x1080 backdrop again from fanart.tv and the
 * hero appeared late. For these hosts the disk copy is used as is; everything else (add-on
 * images that can change at the same URL) keeps following Cache-Control.
 */
internal val IMMUTABLE_ARTWORK_HOSTS = setOf(
    "assets.fanart.tv",
    "image.tmdb.org",
    "artworks.thetvdb.com",
)

internal fun isImmutableArtworkUrl(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        .substringAfterLast('@').substringBefore(':').lowercase()
    return host in IMMUTABLE_ARTWORK_HOSTS
}

/** [CacheStrategy.DEFAULT] for immutable artwork hosts, [CacheControlCacheStrategy] for the rest. */
internal class ArtworkCacheStrategy(
    private val headersStrategy: CacheStrategy = CacheControlCacheStrategy(),
) : CacheStrategy {
    private fun strategyFor(request: NetworkRequest): CacheStrategy =
        if (isImmutableArtworkUrl(request.url)) CacheStrategy.DEFAULT else headersStrategy

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult = strategyFor(networkRequest).read(cacheResponse, networkRequest, options)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = strategyFor(networkRequest).write(cacheResponse, networkRequest, networkResponse, options)
}
