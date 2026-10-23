package com.nuvio.app.features.player.shaders

import kotlinx.serialization.Serializable

/** Rápida / Equilibrada / Alta: the size of the networks (Anime4K S/M/L, ArtCNN C4F16/C4F32). */
@Serializable
enum class ShaderQuality { Fast, Balanced, High;

    /** One level down, or null at the bottom. */
    fun lower(): ShaderQuality? = entries.getOrNull(ordinal - 1)
}

@Serializable
enum class SharpenExtra { None, Cas, NvSharpen }

@Serializable
enum class ColorExtra { None, KrigBilateral, ChromaFromLuma, ChromaFromLumaLite }

/** mpv's own debanding (`deband=yes`), not a shader. */
@Serializable
enum class DebandLevel(val iterations: Int, val threshold: Int, val range: Int) {
    Off(0, 0, 0),
    Low(1, 32, 12),
    Medium(2, 48, 16),
    High(4, 64, 16),
}

/**
 * mpv's main scaler (`scale`, `cscale`). Bilinear and the separable filters cost almost nothing
 * on a phone; the EWA filters (polar, 2D) cost several milliseconds per frame at 1080p and up.
 */
@Serializable
enum class FinalScaler(val mpvValue: String) {
    Bilinear("bilinear"),
    CatmullRom("catmull_rom"),
    Spline36("spline36"),
    EwaLanczos("ewa_lanczossharp"),
}

/** Which preset the player starts with. */
@Serializable
enum class ShaderMode { Automatic, Manual }

@Serializable
data class ShaderSettings(
    val enabled: Boolean = false,
    /** Runs the upscalers from a scale factor of 1.0, so a 1080p video on a 1220 px tall screen. */
    val applyAlways: Boolean = true,
    val mode: ShaderMode = ShaderMode.Automatic,
    /** Used by Automatic for anime. */
    val animePreset: String = ShaderPresets.ANIME4K_A,
    /** Used by Automatic for everything else. */
    val generalPreset: String = ShaderPresets.FSRCNNX,
    /** Used in Manual mode: any bundled preset or an own chain ("chain:<id>"). */
    val manualPreset: String = ShaderPresets.ANIME4K_A,
    val quality: ShaderQuality = ShaderQuality.Balanced,
    val sharpen: SharpenExtra = SharpenExtra.None,
    val color: ColorExtra = ColorExtra.None,
    val deband: DebandLevel = DebandLevel.Off,
    val finalScaler: FinalScaler = FinalScaler.Spline36,
    /** Remembers the preset picked in the player for each series. */
    val rememberPerSeries: Boolean = false,
    val seriesPresets: Map<String, String> = emptyMap(),
    val disableOnBatterySaver: Boolean = true,
    /** Own chains made in the player's "Combine" tab or imported (part 6). */
    val chains: List<ShaderChainDefinition> = emptyList(),
    /** Shaders imported by the user (part 6). */
    val imported: List<ImportedShader> = emptyList(),
)

/** An own chain: shader ids in order, under a name. */
@Serializable
data class ShaderChainDefinition(
    val id: String,
    val name: String,
    val shaderIds: List<String>,
)

/** A shader file the user imported; [id] starts with "imported:". */
@Serializable
data class ImportedShader(
    val id: String,
    val title: String,
    val fileName: String,
    val upscaler: Boolean,
    val doublesResolution: Boolean,
    val compute: Boolean,
)
