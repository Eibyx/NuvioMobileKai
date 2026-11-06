package com.nuvio.app.features.cloudstorage

import com.nuvio.app.core.network.SharedOkHttpClient
import java.util.concurrent.TimeUnit

internal actual object CloudPlaybackGateway {
    private val server by lazy {
        LocalCloudStreamServer(
            SharedOkHttpClient.base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
    }

    actual val servesLocally: Boolean = true

    actual fun open(
        fileName: String,
        sizeBytes: Long?,
        mimeType: String,
        resolve: suspend (forceRefresh: Boolean) -> CloudPlaybackSource,
    ): String = server.open(fileName, sizeBytes, mimeType, resolve)

    actual fun close(url: String?) = server.close(url)

    actual fun isLocalUrl(url: String?): Boolean = server.isLocalUrl(url)
}
