package com.nuvio.app.core.poster

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Precision
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "High quality images": TMDB and MetaHub artwork arrives as links to a fixed, often small size
 * (posters at 500 px, backdrops at 1280 px). With the setting on, the link is swapped for the
 * smallest size of the same image that covers what the screen draws, so the detail hero gets the
 * original while a poster in a row keeps a light file. If the larger file fails, the link the
 * source gave is loaded instead.
 */
class ArtworkQualityInterceptor(
    private val enabled: () -> Boolean = { ThemeSettingsRepository.highQualityArtworkEnabled.value },
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val height = (chain.size.height as? Dimension.Pixels)?.px ?: 0
        val upgraded = if (enabled()) upgradeArtworkUrl(url, width, height) else null
        if (upgraded == null) {
            // Any artwork drawn at a known size is decoded at that size. Fanart.tv and many
            // add-ons link only the full file (1920 to 3840 px wide); decoded "at least" the
            // drawn size it stays 8 to 33 MB in memory, and a few of those push the heroes out of
            // the memory cache, so they vanish and load again when scrolled back into view.
            if (chain.request.precision == Precision.EXACT) return chain.proceed()
            return chain.withRequest(chain.request.newBuilder().precision(Precision.EXACT).build()).proceed()
        }

        // Decoded at the size it is drawn, not "at least" that size: an original TMDB backdrop
        // (3840 × 2160) would otherwise stay whole in memory, about 33 MB, and a few of them push
        // each other and the rest out of the memory cache, so heroes reload from disk on the way back.
        val upgradedRequest = chain.request.newBuilder()
            .data(upgraded)
            .precision(Precision.EXACT)
            .build()
        val result = chain.withRequest(upgradedRequest).proceed()
        return if (result is ErrorResult) chain.proceed() else result
    }
}

private val tmdbImagePattern = Regex("""^(https?://image\.tmdb\.org/t/p/)(w\d+|original)(/.+)$""")
private val metahubImagePattern = Regex("""^(https?://images\.metahub\.space/poster/)(small|medium)(/.+)$""")

private val tmdbPhotoWidths = listOf(342, 500, 780, 1280)
private val tmdbLogoWidths = listOf(300, 500)
private const val ORIGINAL = Int.MAX_VALUE

/**
 * The link to load for an image drawn [widthPx] × [heightPx], or null to keep [url]. Only ever
 * asks for a larger file than the one linked.
 */
internal fun upgradeArtworkUrl(url: String, widthPx: Int, heightPx: Int): String? {
    if (widthPx <= 0) return null
    tmdbImagePattern.matchEntire(url)?.let { match ->
        val (prefix, size, path) = match.destructured
        if (size == "original") return null
        val current = size.removePrefix("w").toIntOrNull() ?: return null
        val extension = path.substringAfterLast('.', "").lowercase()
        val isLogo = extension == "png" || extension == "svg"
        // Backdrops (linked at 780 px and up) are 16:9; posters and stills linked smaller are
        // treated as 2:3, so a cropped image is wide enough for the height it fills too.
        val needed = when {
            isLogo -> widthPx
            current >= 780 -> max(widthPx, (heightPx * 16f / 9f).roundToInt())
            else -> max(widthPx, (heightPx * 2f / 3f).roundToInt())
        }
        if (needed <= current) return null
        val ladder = if (isLogo) tmdbLogoWidths else tmdbPhotoWidths
        val target = ladder.firstOrNull { it >= needed && it > current } ?: ORIGINAL
        return prefix + (if (target == ORIGINAL) "original" else "w$target") + path
    }
    metahubImagePattern.matchEntire(url)?.let { match ->
        val (prefix, size, path) = match.destructured
        // MetaHub posters: small ≈ 300 px wide, medium 500 px, large 780 px. Its logos and
        // backgrounds are the same file at every size, so they are left alone.
        val current = if (size == "small") 300 else 500
        val needed = max(widthPx, (heightPx * 2f / 3f).roundToInt())
        if (needed <= current) return null
        val target = if (size == "small" && needed <= 500) "medium" else "large"
        return prefix + target + path
    }
    return null
}
