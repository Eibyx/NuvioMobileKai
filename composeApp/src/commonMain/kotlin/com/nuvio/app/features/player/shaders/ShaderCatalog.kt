package com.nuvio.app.features.player.shaders

/** Where a shader sits in the "Combine" tab. */
enum class ShaderCategory { Anime, GeneralScaling, Sharpening, Color, Downscaling, Deband, Imported }

/** Default order of a combined chain: cleanup → scaling → color → sharpening → downscaling. */
enum class ShaderStage { Cleanup, Scaling, Color, Sharpening, Downscaling }

/** The exact upstream commit a bundled shader was copied from. */
data class ShaderPin(val repository: String, val commit: String) {
    val url: String get() = "https://github.com/$repository/tree/$commit"
}

/** A shader project, with the license its files are under. */
data class ShaderProject(
    val id: String,
    val name: String,
    val url: String,
    /** SPDX identifier. */
    val license: String,
    val copyright: String,
    /** Under composeResources/files: the notice and license text shown in Settings > About. */
    val licenseFile: String,
)

data class BundledShader(
    val id: String,
    /** File name under the app's `assets/mpv_shaders/` (Media3 already uses `assets/shaders/`). */
    val file: String,
    val title: String,
    val category: ShaderCategory,
    val stage: ShaderStage,
    /** Gets an "Apply always" variant whose scale threshold starts at 1.0. */
    val upscaler: Boolean,
    /** Doubles the resolution; two of these in a chain are usually wasted work. */
    val doublesResolution: Boolean,
    /** Works on the chroma planes; it only runs on subsampled video. */
    val chroma: Boolean,
    /** Uses compute shaders: needs Vulkan or OpenGL ES 3.1+. */
    val compute: Boolean,
    val project: ShaderProject,
    val pin: ShaderPin,
    /** Gets a variant that takes an upscaled image down to the screen ("Apply always"). */
    val toScreen: Boolean = false,
)

/**
 * The shaders that ship with the app. NNEDI3, Anime4K VL/UL and Sirosky's Ani4K v2 are left out on
 * purpose: they are far too heavy for a phone. Debanding uses mpv's own `deband`, not a shader.
 */
object ShaderCatalog {
    const val ASSET_FOLDER = "mpv_shaders"
    const val LGPL_FILE = "files/licenses/shaders/lgpl-3.0.txt"

    val defaultShaderPack = ShaderPin("iwalton3/default-shader-pack", "9ab0195ece699680b422ec5bb2216d45e14c82b1")
    val artCnnRepository = ShaderPin("Artoriuz/ArtCNN", "0837e5326e710f49e2ab0a8d995da0708047442e")
    val mpvPrescalers = ShaderPin("bjin/mpv-prescalers", "b3f0a59d68f33b7162051ea5970a5169558f0ea2")
    val chromaFromLuma = ShaderPin("Artoriuz/glsl-chroma-from-luma-prediction", "066dff964b83aa34e893de25d590874bf8c8e94f")

    private fun license(name: String) = "files/licenses/shaders/$name.txt"

    val anime4k = ShaderProject("anime4k", "Anime4K", "https://github.com/bloc97/Anime4K", "MIT", "Copyright (c) 2019-2021 bloc97", license("anime4k"))
    val artCnn = ShaderProject("artcnn", "ArtCNN", "https://github.com/Artoriuz/ArtCNN", "MIT", "Copyright (c) 2024 João Chrisóstomo, Kacper Michajłow", license("artcnn"))
    val fsrcnnx = ShaderProject("fsrcnnx", "FSRCNNX", "https://github.com/igv/FSRCNN-TensorFlow", "LGPL-3.0-or-later", "Copyright (C) 2017-2021 igv", license("fsrcnnx"))
    val krigBilateral = ShaderProject("krigbilateral", "KrigBilateral", "https://gist.github.com/igv/a015fc885d5c22e6891820ad89555637", "LGPL-3.0-or-later", "Copyright (C) Shiandow, igv", license("krigbilateral"))
    val ssimDownscaler = ShaderProject("ssimdownscaler", "SSimDownscaler", "https://gist.github.com/igv/36508af3ffc84410fe39761d6969be10", "LGPL-3.0-or-later", "Copyright (C) igv", license("ssimdownscaler"))
    val fsr = ShaderProject("fsr", "AMD FidelityFX Super Resolution (mpv port by agyild)", "https://gist.github.com/agyild/82219c545228d70c5604f865ce0b0ce5", "MIT", "Copyright (c) 2021 Advanced Micro Devices, Inc.", license("fsr"))
    val cas = ShaderProject("cas", "AMD FidelityFX Contrast Adaptive Sharpening (mpv port by agyild)", "https://gist.github.com/agyild/bbb4e58298b2f86aa24da3032a0d2ee6", "MIT", "Copyright (c) 2017-2019 Advanced Micro Devices, Inc.", license("cas"))
    val nvidia = ShaderProject("nvidia-image-scaling", "NVIDIA Image Scaling (mpv port by agyild)", "https://gist.github.com/agyild/7e8951915b2bf24526a9343d951db214", "MIT", "Copyright (c) 2022 NVIDIA CORPORATION & AFFILIATES", license("nvidia-image-scaling"))
    val ravu = ShaderProject("ravu", "RAVU (mpv-prescalers)", "https://github.com/bjin/mpv-prescalers", "LGPL-3.0-or-later", "Copyright (C) Bin Jin and contributors", license("ravu"))
    val cfl = ShaderProject("cfl", "Chroma From Luma Prediction", "https://github.com/Artoriuz/glsl-chroma-from-luma-prediction", "MIT", "Copyright (c) 2023 João Chrisóstomo", license("cfl"))
    /** The pack's documentation and presets (pack.json), which the presets here follow. */
    val shaderPack = ShaderProject("default-shader-pack", "default-shader-pack", "https://github.com/iwalton3/default-shader-pack", "MIT", "Copyright (c) 2020 Ian Walton", license("default-shader-pack"))

    val projects: List<ShaderProject> = listOf(anime4k, artCnn, fsrcnnx, krigBilateral, ssimDownscaler, fsr, cas, nvidia, ravu, cfl, shaderPack)

    private fun anime4k(id: String, file: String, title: String, stage: ShaderStage, upscaler: Boolean = false) =
        BundledShader(
            id = id,
            file = file,
            title = title,
            category = ShaderCategory.Anime,
            stage = stage,
            upscaler = upscaler,
            doublesResolution = upscaler,
            chroma = false,
            compute = false,
            project = anime4k,
            pin = defaultShaderPack,
        )

    private val anime4kShaders: List<BundledShader> = buildList {
        add(anime4k("anime4k_clamp_highlights", "Anime4K_Clamp_Highlights.glsl", "Anime4K Clamp Highlights", ShaderStage.Cleanup))
        for (size in listOf("S", "M", "L")) {
            add(anime4k("anime4k_restore_${size.lowercase()}", "Anime4K_Restore_CNN_$size.glsl", "Anime4K Restore ($size)", ShaderStage.Cleanup))
            add(anime4k("anime4k_restore_soft_${size.lowercase()}", "Anime4K_Restore_CNN_Soft_$size.glsl", "Anime4K Restore Soft ($size)", ShaderStage.Cleanup))
            add(anime4k("anime4k_upscale_${size.lowercase()}", "Anime4K_Upscale_CNN_x2_$size.glsl", "Anime4K Upscale x2 ($size)", ShaderStage.Scaling, upscaler = true))
            add(anime4k("anime4k_upscale_denoise_${size.lowercase()}", "Anime4K_Upscale_Denoise_CNN_x2_$size.glsl", "Anime4K Upscale Denoise x2 ($size)", ShaderStage.Scaling, upscaler = true))
        }
        // They shrink the first upscale's output so the second one lands on the screen size; never forced.
        add(anime4k("anime4k_autodownscale_x2", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K AutoDownscalePre x2", ShaderStage.Scaling))
        add(anime4k("anime4k_autodownscale_x4", "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K AutoDownscalePre x4", ShaderStage.Scaling))
    }

    private fun artCnn(id: String, file: String, title: String, pin: ShaderPin) = BundledShader(
        id = id,
        file = file,
        title = title,
        category = ShaderCategory.Anime,
        stage = ShaderStage.Scaling,
        upscaler = true,
        doublesResolution = true,
        chroma = false,
        compute = true,
        project = artCnn,
        pin = pin,
    )

    val shaders: List<BundledShader> = anime4kShaders + listOf(
        artCnn("artcnn_c4f16", "ArtCNN_C4F16.glsl", "ArtCNN C4F16", defaultShaderPack),
        artCnn("artcnn_c4f16_ds", "ArtCNN_C4F16_DS.glsl", "ArtCNN C4F16 DS", defaultShaderPack),
        // The pack has no DN variant: it comes from ArtCNN itself.
        artCnn("artcnn_c4f16_dn", "ArtCNN_C4F16_DN.glsl", "ArtCNN C4F16 DN", artCnnRepository),
        artCnn("artcnn_c4f32", "ArtCNN_C4F32.glsl", "ArtCNN C4F32", defaultShaderPack),
        BundledShader("fsrcnnx_8", "FSRCNNX_x2_8-0-4-1.glsl", "FSRCNNX x2 8-0-4-1", ShaderCategory.GeneralScaling, ShaderStage.Scaling, upscaler = true, doublesResolution = true, chroma = false, compute = false, project = fsrcnnx, pin = defaultShaderPack),
        BundledShader("ravu_lite_ar_r2", "ravu-lite-ar-r2.hook", "RAVU-Lite-AR r2", ShaderCategory.GeneralScaling, ShaderStage.Scaling, upscaler = true, doublesResolution = true, chroma = false, compute = false, project = ravu, pin = mpvPrescalers),
        BundledShader("ravu_lite_ar_r3", "ravu-lite-ar-r3.hook", "RAVU-Lite-AR r3", ShaderCategory.GeneralScaling, ShaderStage.Scaling, upscaler = true, doublesResolution = true, chroma = false, compute = false, project = ravu, pin = mpvPrescalers),
        // FSR and NVScaler scale to the output size in one go; their area check (> 1.0) already runs from 1.0.
        BundledShader("fsr", "FSR.glsl", "AMD FSR 1.0", ShaderCategory.GeneralScaling, ShaderStage.Scaling, upscaler = false, doublesResolution = false, chroma = false, compute = false, project = fsr, pin = defaultShaderPack),
        BundledShader("nvscaler", "NVScaler.glsl", "NVIDIA Image Scaling", ShaderCategory.GeneralScaling, ShaderStage.Scaling, upscaler = false, doublesResolution = false, chroma = false, compute = true, project = nvidia, pin = defaultShaderPack),
        BundledShader("cas_scaled", "CAS-scaled.glsl", "AMD CAS", ShaderCategory.Sharpening, ShaderStage.Sharpening, upscaler = false, doublesResolution = false, chroma = false, compute = false, project = cas, pin = defaultShaderPack),
        BundledShader("nvsharpen", "NVSharpen.glsl", "NVIDIA Sharpen", ShaderCategory.Sharpening, ShaderStage.Sharpening, upscaler = false, doublesResolution = false, chroma = false, compute = true, project = nvidia, pin = defaultShaderPack),
        BundledShader("krigbilateral", "KrigBilateral.glsl", "KrigBilateral", ShaderCategory.Color, ShaderStage.Color, upscaler = false, doublesResolution = false, chroma = true, compute = false, project = krigBilateral, pin = defaultShaderPack),
        BundledShader("cfl", "CfL_Prediction.glsl", "Chroma From Luma", ShaderCategory.Color, ShaderStage.Color, upscaler = false, doublesResolution = false, chroma = true, compute = false, project = cfl, pin = chromaFromLuma),
        BundledShader("cfl_lite", "CfL_Prediction_Lite.glsl", "Chroma From Luma Lite", ShaderCategory.Color, ShaderStage.Color, upscaler = false, doublesResolution = false, chroma = true, compute = false, project = cfl, pin = chromaFromLuma),
        BundledShader("ssimdownscaler", "SSimDownscaler.glsl", "SSimDownscaler", ShaderCategory.Downscaling, ShaderStage.Downscaling, upscaler = false, doublesResolution = false, chroma = false, compute = false, project = ssimDownscaler, pin = defaultShaderPack, toScreen = true),
    )

    private val byId = shaders.associateBy { it.id }

    fun shader(id: String): BundledShader? = byId[id]
}
