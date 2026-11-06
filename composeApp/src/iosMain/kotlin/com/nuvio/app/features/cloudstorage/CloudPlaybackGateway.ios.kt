package com.nuvio.app.features.cloudstorage

/** No local server on iOS yet: the player gets the provider URL and headers directly. Untested. */
internal actual object CloudPlaybackGateway {
    actual val servesLocally: Boolean = false

    actual fun open(
        fileName: String,
        sizeBytes: Long?,
        mimeType: String,
        resolve: suspend (forceRefresh: Boolean) -> CloudPlaybackSource,
    ): String = throw UnsupportedOperationException("No local cloud server on iOS")

    actual fun close(url: String?) = Unit

    actual fun isLocalUrl(url: String?): Boolean = false
}
