package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision

/**
 * Memory cache extra that keeps the small helper decodes of an artwork (the cinematic
 * blurred backdrop, the dominant color sample) apart from the full-size artwork itself.
 *
 * Coil's memory cache key is only the URL unless a request has transformations, so a
 * 320 px or 96 px decode of the hero URL replaced the hero's own 1920 px entry. When the
 * hero came back on screen (scrolled back up, recreated in the LazyColumn) the cached
 * small image was too small for it, so it was a miss and the hero disappeared until it
 * loaded again. That only happens in the Cinematic and Dominant Color modes, the two that
 * make these extra requests. The disk cache key stays the URL, so the file is shared.
 */
internal const val ArtworkUseMemoryKeyExtra = "nuvio#artwork-use"

internal const val CinematicBackdropDecodeSize = 320

internal fun cinematicBackdropRequest(context: PlatformContext, imageUrl: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(imageUrl)
        .size(CinematicBackdropDecodeSize)
        .precision(Precision.EXACT)
        .memoryCacheKeyExtra(ArtworkUseMemoryKeyExtra, "cinematic-backdrop")
        .build()

internal const val DominantColorSampleSize = 96

internal fun dominantColorSampleRequest(context: PlatformContext, imageUrl: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(imageUrl)
        .size(DominantColorSampleSize, DominantColorSampleSize)
        .memoryCacheKeyExtra(ArtworkUseMemoryKeyExtra, "dominant-color")
        .build()
