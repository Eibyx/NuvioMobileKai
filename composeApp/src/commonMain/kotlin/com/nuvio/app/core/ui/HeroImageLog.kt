package com.nuvio.app.core.ui

import coil3.request.SuccessResult
import com.nuvio.app.core.logging.InAppLogger

/**
 * One diagnostics line per loaded hero image: where it came from (memory, disk or network)
 * and its decoded size. Only the host is logged: addon image URLs can carry the addon
 * configuration in their path.
 */
internal fun logHeroImageLoaded(screen: String, url: String?, result: SuccessResult) {
    val host = url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')
        ?.takeIf { it.isNotBlank() } ?: "local"
    InAppLogger.debug(
        "Images/Hero",
        "$screen source=${result.dataSource.name} host=$host decoded=${result.image.width}x${result.image.height}",
    )
}
