package com.nuvio.app.features.player.shaders

/** One shader of a chain, and whether it uses its "Apply always" variant. */
data class ChainEntry(val shaderId: String, val forced: Boolean = false)

enum class PresetGroup { Anime, General }

/**
 * The bundled presets and how a preset becomes an ordered chain. The Anime4K chains follow
 * Anime4K's own mpv instructions and default-shader-pack's pack.json; ArtCNN and FSRCNNX come with
 * KrigBilateral and SSimDownscaler, as in pack.json.
 */
object ShaderPresets {
    const val ANIME4K_A = "anime4k_a"
    const val ANIME4K_B = "anime4k_b"
    const val ANIME4K_C = "anime4k_c"
    const val ANIME4K_AA = "anime4k_aa"
    const val ANIME4K_BB = "anime4k_bb"
    const val ANIME4K_CA = "anime4k_ca"
    const val ARTCNN = "artcnn"
    const val ARTCNN_SHARP = "artcnn_sharp"
    const val ARTCNN_SOFT = "artcnn_soft"
    const val FSR = "fsr"
    const val FSRCNNX = "fsrcnnx"
    const val RAVU = "ravu"
    const val NVSCALER = "nvscaler"

    /** An own chain is referred to as "chain:<id>". */
    const val CHAIN_PREFIX = "chain:"

    val anime = listOf(ANIME4K_A, ANIME4K_B, ANIME4K_C, ANIME4K_AA, ANIME4K_BB, ANIME4K_CA, ARTCNN, ARTCNN_SHARP, ARTCNN_SOFT)
    val general = listOf(FSR, FSRCNNX, RAVU, NVSCALER)

    fun group(presetId: String): PresetGroup? = when (presetId) {
        in anime -> PresetGroup.Anime
        in general -> PresetGroup.General
        else -> null
    }

    private fun size(quality: ShaderQuality) = when (quality) {
        ShaderQuality.Fast -> "s"
        ShaderQuality.Balanced -> "m"
        ShaderQuality.High -> "l"
    }

    /** Anime4K's second x2 stage, for videos that need more than one doubling. */
    private fun secondSize(quality: ShaderQuality) = if (quality == ShaderQuality.High) "m" else "s"

    /** The shader ids of [presetId] at [quality], in order; null for an unknown preset. */
    fun baseChain(presetId: String, quality: ShaderQuality): List<String>? {
        val q = size(quality)
        val second = secondSize(quality)
        val autoDownscale = listOf("anime4k_autodownscale_x2", "anime4k_autodownscale_x4")
        val clamp = "anime4k_clamp_highlights"
        return when (presetId) {
            ANIME4K_A -> listOf(clamp, "anime4k_restore_$q", "anime4k_upscale_$q") + autoDownscale + "anime4k_upscale_$second"
            ANIME4K_B -> listOf(clamp, "anime4k_restore_soft_$q", "anime4k_upscale_$q") + autoDownscale + "anime4k_upscale_$second"
            ANIME4K_C -> listOf(clamp, "anime4k_upscale_denoise_$q") + autoDownscale + "anime4k_upscale_$second"
            ANIME4K_AA -> listOf(clamp, "anime4k_restore_$q", "anime4k_upscale_$q") + autoDownscale +
                listOf("anime4k_restore_$second", "anime4k_upscale_$second")
            ANIME4K_BB -> listOf(clamp, "anime4k_restore_soft_$q", "anime4k_upscale_$q") + autoDownscale +
                listOf("anime4k_restore_soft_$second", "anime4k_upscale_$second")
            ANIME4K_CA -> listOf(clamp, "anime4k_upscale_denoise_$q") + autoDownscale +
                listOf("anime4k_restore_$second", "anime4k_upscale_$second")
            ARTCNN -> listOf(if (quality == ShaderQuality.High) "artcnn_c4f32" else "artcnn_c4f16", "krigbilateral", "ssimdownscaler")
            ARTCNN_SHARP -> listOf("artcnn_c4f16_ds", "krigbilateral", "ssimdownscaler")
            ARTCNN_SOFT -> listOf("artcnn_c4f16_dn", "krigbilateral", "ssimdownscaler")
            FSR -> listOf("fsr")
            FSRCNNX -> listOf("fsrcnnx_8", "krigbilateral", "ssimdownscaler")
            RAVU -> listOf(if (quality == ShaderQuality.Fast) "ravu_lite_ar_r2" else "ravu_lite_ar_r3")
            NVSCALER -> listOf("nvscaler")
            else -> null
        }
    }

    /** What the resolver needs to know about a shader, bundled or imported. */
    data class ShaderTraits(val stage: ShaderStage, val upscaler: Boolean, val doublesResolution: Boolean)

    fun bundledTraits(id: String): ShaderTraits? =
        ShaderCatalog.shader(id)?.let { ShaderTraits(it.stage, it.upscaler, it.doublesResolution) }

    /**
     * [presetId] (a bundled preset or "chain:<id>") as the chain to run:
     * - the color and sharpening extras go in at their stage (an extra color shader replaces the
     *   preset's own);
     * - with [applyAlways], only the first upscaler uses its forced variant (in Anime4K's two-step
     *   modes the second upscale and AutoDownscalePre keep their thresholds);
     * - with [applyAlways] at Balanced or High, a chain that doubles the resolution ends with
     *   SSimDownscaler taking the upscaled image down to the screen.
     * An own chain ("chain:<id>") is run exactly as made: no extras and nothing appended, only
     * the "Apply always" variants of its first upscaler and of its SSimDownscaler.
     */
    fun resolve(
        presetId: String,
        settings: ShaderSettings,
        quality: ShaderQuality = settings.quality,
        applyAlways: Boolean = settings.applyAlways,
        traits: (String) -> ShaderTraits? = ::bundledTraits,
    ): List<ChainEntry> {
        val base = if (presetId.startsWith(CHAIN_PREFIX)) {
            settings.chains.firstOrNull { it.id == presetId.removePrefix(CHAIN_PREFIX) }?.shaderIds
        } else {
            baseChain(presetId, quality)
        } ?: return emptyList()
        val ids = base.filter { traits(it) != null }.toMutableList()
        val exact = presetId.startsWith(CHAIN_PREFIX)
        val colorExtra = if (exact) null else when (settings.color) {
            ColorExtra.None -> null
            ColorExtra.KrigBilateral -> "krigbilateral"
            ColorExtra.ChromaFromLuma -> "cfl"
            ColorExtra.ChromaFromLumaLite -> "cfl_lite"
        }
        if (colorExtra != null) {
            ids.removeAll { traits(it)?.stage == ShaderStage.Color }
            insertAtStage(ids, colorExtra, traits)
        }
        val sharpenExtra = if (exact) null else when (settings.sharpen) {
            SharpenExtra.None -> null
            SharpenExtra.Cas -> "cas_scaled"
            SharpenExtra.NvSharpen -> "nvsharpen"
        }
        if (sharpenExtra != null && sharpenExtra !in ids) insertAtStage(ids, sharpenExtra, traits)
        val toScreen = applyAlways && quality != ShaderQuality.Fast && ids.any { traits(it)?.doublesResolution == true }
        if (toScreen && SSIM !in ids && !exact) ids += SSIM
        val firstUpscaler = if (applyAlways) ids.indexOfFirst { traits(it)?.upscaler == true } else -1
        return ids.mapIndexed { index, id ->
            ChainEntry(id, forced = index == firstUpscaler || (id == SSIM && toScreen))
        }
    }

    private const val SSIM = "ssimdownscaler"

    /** Puts [id] after the last shader of its stage or an earlier one. */
    private fun insertAtStage(ids: MutableList<String>, id: String, traits: (String) -> ShaderTraits?) {
        val stage = traits(id)?.stage ?: return
        val at = ids.indexOfLast { (traits(it)?.stage ?: ShaderStage.Scaling) <= stage } + 1
        ids.add(at, id)
    }

    /** Two shaders that each double the resolution: allowed, but usually wasted work. */
    fun doublesTwice(ids: List<String>, traits: (String) -> ShaderTraits? = ::bundledTraits): Boolean {
        val doublers = ids.filter { traits(it)?.doublesResolution == true }.map { family(it) }.distinct()
        return doublers.size > 1
    }

    /** Anime4K's two x2 stages are meant to work together, so they count as one family. */
    private fun family(id: String) = when {
        id.startsWith("anime4k_") -> "anime4k"
        id.startsWith("artcnn_") -> "artcnn"
        id.startsWith("ravu_") -> "ravu"
        else -> id
    }

    /** The default order of a combined chain: cleanup → scaling → color → sharpening → downscaling. */
    fun defaultOrder(ids: List<String>, traits: (String) -> ShaderTraits? = ::bundledTraits): List<String> =
        ids.sortedBy { traits(it)?.stage?.ordinal ?: ShaderStage.Scaling.ordinal }
}

/** Whether a title is anime, for the Automatic mode: linked on AniList, an anime id (mal:, kitsu:...) or the Animation genre. */
object ShaderContentKind {
    fun isAnime(hasAnimeId: Boolean, genres: List<String>, hasAniListLink: Boolean): Boolean =
        hasAniListLink || hasAnimeId ||
            genres.any { it.equals("Animation", ignoreCase = true) || it.equals("Anime", ignoreCase = true) }
}
