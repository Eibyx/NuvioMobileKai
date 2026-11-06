package com.nuvio.app.features.cloudstorage

/**
 * How the platform hands a cloud file to the player.
 *
 * Android serves it through a loopback HTTP server in the app process (the role `rclone serve http`
 * plays in Round Sync Extra): the player gets `http://127.0.0.1:<port>/v1/<token>/<name>`, and the
 * server adds the provider's headers, forwards Range requests and replaces an expired link.
 * Platforms without one ([servesLocally] false) give the player the provider URL and headers.
 */
internal expect object CloudPlaybackGateway {
    val servesLocally: Boolean

    /** A local URL for [file]; the session lasts until [close] or the app process ends. */
    fun open(
        fileName: String,
        sizeBytes: Long?,
        mimeType: String,
        resolve: suspend (forceRefresh: Boolean) -> CloudPlaybackSource,
    ): String

    fun close(url: String?)

    fun isLocalUrl(url: String?): Boolean
}
