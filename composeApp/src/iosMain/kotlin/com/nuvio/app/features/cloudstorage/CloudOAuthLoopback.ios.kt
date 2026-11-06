package com.nuvio.app.features.cloudstorage

/** Not on iOS yet: Drive is connected there with a refresh token or an imported rclone.conf. */
internal actual object CloudOAuthLoopback {
    actual val supported: Boolean = false

    actual suspend fun receive(onReady: (redirectUri: String) -> Unit): Map<String, String> =
        throw CloudStorageException(CloudFailure.UNSUPPORTED)
}
