package com.nuvio.app.features.player.shaders

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The name of a preset as the settings and the player's panel show it. */
@Composable
fun shaderPresetLabel(presetId: String, chains: List<ShaderChainDefinition>): String = when (presetId) {
    ShaderController.AUTOMATIC -> stringResource(Res.string.shader_preset_automatic)
    ShaderPresets.ANIME4K_A -> stringResource(Res.string.shader_preset_anime4k_a)
    ShaderPresets.ANIME4K_B -> stringResource(Res.string.shader_preset_anime4k_b)
    ShaderPresets.ANIME4K_C -> stringResource(Res.string.shader_preset_anime4k_c)
    ShaderPresets.ANIME4K_AA -> stringResource(Res.string.shader_preset_anime4k_aa)
    ShaderPresets.ANIME4K_BB -> stringResource(Res.string.shader_preset_anime4k_bb)
    ShaderPresets.ANIME4K_CA -> stringResource(Res.string.shader_preset_anime4k_ca)
    ShaderPresets.ARTCNN -> stringResource(Res.string.shader_preset_artcnn)
    ShaderPresets.ARTCNN_SHARP -> stringResource(Res.string.shader_preset_artcnn_sharp)
    ShaderPresets.ARTCNN_SOFT -> stringResource(Res.string.shader_preset_artcnn_soft)
    ShaderPresets.FSR -> "AMD FSR 1.0"
    ShaderPresets.FSRCNNX -> "FSRCNNX + KrigBilateral + SSimDownscaler"
    ShaderPresets.RAVU -> "RAVU-Lite-AR"
    ShaderPresets.NVSCALER -> "NVIDIA Image Scaling"
    else -> chains.firstOrNull { ShaderPresets.CHAIN_PREFIX + it.id == presetId }?.name ?: presetId
}

/** What a preset is good for, in one line. */
@Composable
fun shaderPresetDescription(presetId: String): String? = when (presetId) {
    ShaderPresets.ANIME4K_A, ShaderPresets.ANIME4K_AA -> stringResource(Res.string.shader_preset_anime4k_a_description)
    ShaderPresets.ANIME4K_B, ShaderPresets.ANIME4K_BB -> stringResource(Res.string.shader_preset_anime4k_b_description)
    ShaderPresets.ANIME4K_C, ShaderPresets.ANIME4K_CA -> stringResource(Res.string.shader_preset_anime4k_c_description)
    ShaderPresets.ARTCNN, ShaderPresets.ARTCNN_SHARP, ShaderPresets.ARTCNN_SOFT -> stringResource(Res.string.shader_preset_artcnn_description)
    ShaderPresets.FSR -> stringResource(Res.string.shader_preset_fsr_description)
    ShaderPresets.FSRCNNX -> stringResource(Res.string.shader_preset_fsrcnnx_description)
    ShaderPresets.RAVU -> stringResource(Res.string.shader_preset_ravu_description)
    ShaderPresets.NVSCALER -> stringResource(Res.string.shader_preset_nvscaler_description)
    else -> null
}

@Composable
fun shaderQualityLabel(quality: ShaderQuality): String = when (quality) {
    ShaderQuality.Fast -> stringResource(Res.string.shader_quality_fast)
    ShaderQuality.Balanced -> stringResource(Res.string.shader_quality_balanced)
    ShaderQuality.High -> stringResource(Res.string.shader_quality_high)
}

@Composable
fun shaderQualityDescription(quality: ShaderQuality): String = when (quality) {
    ShaderQuality.Fast -> stringResource(Res.string.shader_quality_fast_description)
    ShaderQuality.Balanced -> stringResource(Res.string.shader_quality_balanced_description)
    ShaderQuality.High -> stringResource(Res.string.shader_quality_high_description)
}

@Composable
fun sharpenExtraLabel(extra: SharpenExtra): String = when (extra) {
    SharpenExtra.None -> stringResource(Res.string.shader_extra_none)
    SharpenExtra.Cas -> "AMD CAS"
    SharpenExtra.NvSharpen -> "NVIDIA Sharpen"
}

@Composable
fun colorExtraLabel(extra: ColorExtra): String = when (extra) {
    ColorExtra.None -> stringResource(Res.string.shader_extra_none)
    ColorExtra.KrigBilateral -> "KrigBilateral"
    ColorExtra.ChromaFromLuma -> "Chroma From Luma"
    ColorExtra.ChromaFromLumaLite -> "Chroma From Luma Lite"
}

@Composable
fun debandLabel(level: DebandLevel): String = when (level) {
    DebandLevel.Off -> stringResource(Res.string.shader_deband_off)
    DebandLevel.Low -> stringResource(Res.string.shader_deband_low)
    DebandLevel.Medium -> stringResource(Res.string.shader_deband_medium)
    DebandLevel.High -> stringResource(Res.string.shader_deband_high)
}

@Composable
fun finalScalerLabel(scaler: FinalScaler): String = when (scaler) {
    FinalScaler.Bilinear -> "Bilinear"
    FinalScaler.CatmullRom -> "Catmull-Rom"
    FinalScaler.Spline36 -> "Spline36"
    FinalScaler.EwaLanczos -> "EWA Lanczos Sharp"
}

@Composable
fun finalScalerDescription(scaler: FinalScaler): String = when (scaler) {
    FinalScaler.Bilinear -> stringResource(Res.string.shader_scaler_bilinear_description)
    FinalScaler.CatmullRom -> stringResource(Res.string.shader_scaler_catmull_description)
    FinalScaler.Spline36 -> stringResource(Res.string.shader_scaler_spline_description)
    FinalScaler.EwaLanczos -> stringResource(Res.string.shader_scaler_ewa_description)
}
