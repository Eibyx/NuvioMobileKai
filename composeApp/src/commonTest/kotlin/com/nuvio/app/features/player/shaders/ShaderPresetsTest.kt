package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderPresetsTest {
    private fun files(presetId: String, settings: ShaderSettings = ShaderSettings(), quality: ShaderQuality = settings.quality, applyAlways: Boolean = settings.applyAlways) =
        ShaderPresets.resolve(presetId, settings, quality, applyAlways).map { entry ->
            ShaderCatalog.shader(entry.shaderId)!!.file + if (entry.forced) " (forced)" else ""
        }

    @Test
    fun anime4kModesFollowTheOfficialOrder() {
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl (forced)",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
                "SSimDownscaler.glsl (forced)",
            ),
            files(ShaderPresets.ANIME4K_A),
        )
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_Soft_L.glsl",
                "Anime4K_Upscale_CNN_x2_L.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Restore_CNN_Soft_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
            ),
            files(ShaderPresets.ANIME4K_BB, quality = ShaderQuality.High, applyAlways = false),
        )
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Upscale_Denoise_CNN_x2_S.glsl (forced)",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Restore_CNN_S.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
            ),
            // Fast: no SSimDownscaler to the screen.
            files(ShaderPresets.ANIME4K_CA, quality = ShaderQuality.Fast),
        )
        assertEquals(5, files(ShaderPresets.ANIME4K_C, applyAlways = false).size)
        assertTrue(files(ShaderPresets.ANIME4K_B).contains("Anime4K_Restore_CNN_Soft_M.glsl"))
        assertTrue(files(ShaderPresets.ANIME4K_AA).count { it.startsWith("Anime4K_Restore_CNN_") } == 2)
    }

    @Test
    fun onlyTheFirstUpscaleIsForced() {
        ShaderPresets.anime.forEach { preset ->
            val entries = ShaderPresets.resolve(preset, ShaderSettings())
            val forcedUpscalers = entries.filter { it.forced && ShaderCatalog.shader(it.shaderId)!!.upscaler }
            assertEquals(1, forcedUpscalers.size, preset)
            assertEquals(entries.first { ShaderCatalog.shader(it.shaderId)!!.upscaler }, forcedUpscalers.single(), preset)
            // AutoDownscalePre never uses a variant.
            assertFalse(entries.any { it.forced && it.shaderId.startsWith("anime4k_autodownscale") }, preset)
        }
        assertTrue(ShaderPresets.resolve(ShaderPresets.ANIME4K_A, ShaderSettings(applyAlways = false)).none { it.forced })
    }

    @Test
    fun artCnnAndGeneralPresetsFollowThePack() {
        assertEquals(listOf("ArtCNN_C4F16.glsl (forced)", "KrigBilateral.glsl", "SSimDownscaler.glsl (forced)"), files(ShaderPresets.ARTCNN))
        assertEquals(listOf("ArtCNN_C4F32.glsl", "KrigBilateral.glsl", "SSimDownscaler.glsl"), files(ShaderPresets.ARTCNN, quality = ShaderQuality.High, applyAlways = false))
        assertEquals("ArtCNN_C4F16_DS.glsl", files(ShaderPresets.ARTCNN_SHARP, applyAlways = false).first())
        assertEquals("ArtCNN_C4F16_DN.glsl", files(ShaderPresets.ARTCNN_SOFT, applyAlways = false).first())
        assertEquals(listOf("FSRCNNX_x2_8-0-4-1.glsl (forced)", "KrigBilateral.glsl", "SSimDownscaler.glsl (forced)"), files(ShaderPresets.FSRCNNX))
        assertEquals(listOf("FSR.glsl"), files(ShaderPresets.FSR))
        assertEquals(listOf("NVScaler.glsl"), files(ShaderPresets.NVSCALER))
        assertEquals(listOf("ravu-lite-ar-r2.hook (forced)"), files(ShaderPresets.RAVU, quality = ShaderQuality.Fast))
        assertEquals("ravu-lite-ar-r3.hook (forced)", files(ShaderPresets.RAVU).first())
    }

    @Test
    fun extrasGoInAtTheirStage() {
        val settings = ShaderSettings(sharpen = SharpenExtra.Cas, color = ColorExtra.ChromaFromLuma, applyAlways = false)
        assertEquals(listOf("FSRCNNX_x2_8-0-4-1.glsl", "CfL_Prediction.glsl", "CAS-scaled.glsl", "SSimDownscaler.glsl"), files(ShaderPresets.FSRCNNX, settings))
        assertEquals(listOf("FSR.glsl", "CfL_Prediction.glsl", "CAS-scaled.glsl"), files(ShaderPresets.FSR, settings))
        val anime = files(ShaderPresets.ANIME4K_AA, settings.copy(sharpen = SharpenExtra.NvSharpen))
        assertEquals(listOf("CfL_Prediction.glsl", "NVSharpen.glsl"), anime.takeLast(2))
    }

    @Test
    fun ownChainsKeepTheirOrder() {
        val chain = ShaderChainDefinition("c1", "Mine", listOf("fsr", "krigbilateral", "missing"))
        val settings = ShaderSettings(chains = listOf(chain), applyAlways = false)
        assertEquals(listOf("FSR.glsl", "KrigBilateral.glsl"), files("chain:c1", settings))
        assertTrue(ShaderPresets.resolve("chain:none", settings).isEmpty())
    }

    @Test
    fun doublingTwiceIsFlagged() {
        assertTrue(ShaderPresets.doublesTwice(listOf("artcnn_c4f16", "fsrcnnx_8")))
        assertTrue(ShaderPresets.doublesTwice(listOf("anime4k_upscale_m", "artcnn_c4f16")))
        assertFalse(ShaderPresets.doublesTwice(listOf("anime4k_upscale_m", "anime4k_upscale_s")))
        assertFalse(ShaderPresets.doublesTwice(listOf("fsr", "krigbilateral")))
        assertEquals(
            listOf("anime4k_restore_m", "fsrcnnx_8", "krigbilateral", "cas_scaled", "ssimdownscaler"),
            ShaderPresets.defaultOrder(listOf("ssimdownscaler", "cas_scaled", "krigbilateral", "fsrcnnx_8", "anime4k_restore_m")),
        )
    }

    @Test
    fun automaticPicksByContent() {
        val settings = ShaderSettings(enabled = true)
        val anime = ShaderRuntimeInputs(content = ShaderContent("tt1", "tt1", isAnime = true))
        val other = ShaderRuntimeInputs(content = ShaderContent("tt2", "tt2", isAnime = false))
        assertEquals(ShaderPresets.ANIME4K_A, ShaderController.presetFor(settings, anime))
        assertEquals(ShaderPresets.FSRCNNX, ShaderController.presetFor(settings, other))
        assertEquals(ShaderPresets.FSR, ShaderController.presetFor(settings.copy(mode = ShaderMode.Manual, manualPreset = ShaderPresets.FSR), anime))
        // A remembered series preset wins when the option is on.
        val remembered = settings.copy(rememberPerSeries = true, seriesPresets = mapOf("tt1" to ShaderPresets.ARTCNN))
        assertEquals(ShaderPresets.ARTCNN, ShaderController.presetFor(remembered, anime))
        assertEquals(ShaderPresets.ANIME4K_A, ShaderController.presetFor(remembered.copy(rememberPerSeries = false), anime))
        // Picked in the player for this video.
        assertEquals(ShaderPresets.NVSCALER, ShaderController.presetFor(remembered, anime.copy(presetOverride = ShaderPresets.NVSCALER)))
        assertTrue(ShaderContentKind.isAnime(hasAnimeId = true, genres = emptyList(), hasAniListLink = false))
        assertTrue(ShaderContentKind.isAnime(hasAnimeId = false, genres = listOf("Animation"), hasAniListLink = false))
        assertTrue(ShaderContentKind.isAnime(hasAnimeId = false, genres = emptyList(), hasAniListLink = true))
        assertFalse(ShaderContentKind.isAnime(hasAnimeId = false, genres = listOf("Drama"), hasAniListLink = false))
    }

    @Test
    fun theGuardAndComparingChangeThePlan() {
        val settings = ShaderSettings(enabled = true, quality = ShaderQuality.High)
        val inputs = ShaderRuntimeInputs(content = ShaderContent(isAnime = true))
        val plan = ShaderController.plan(settings, inputs)
        assertTrue(plan.active)
        assertEquals(ShaderQuality.High, plan.quality)
        assertEquals(FinalScaler.Spline36, plan.options.scale)
        assertEquals(ShaderQuality.Balanced, ShaderController.plan(settings, inputs.copy(qualityCap = ShaderQuality.Balanced)).quality)
        assertFalse(ShaderController.plan(settings, inputs.copy(applyAlwaysSuspended = true)).applyAlways)
        assertFalse(ShaderController.plan(settings, inputs.copy(suspension = ShaderSuspension.Performance)).active)
        val comparing = ShaderController.plan(settings, inputs.copy(comparing = true))
        assertFalse(comparing.active)
        assertEquals(MpvVideoOptions(), comparing.options)
        assertFalse(ShaderController.plan(settings.copy(enabled = false), inputs).active)
        assertTrue(ShaderController.plan(settings.copy(enabled = false), inputs.copy(enabledOverride = true)).active)
    }

    @Test
    fun settingsSurviveARoundTrip() {
        val settings = ShaderSettings(enabled = true, quality = ShaderQuality.Fast, deband = DebandLevel.High, seriesPresets = mapOf("a" to "fsr"))
        assertEquals(settings, decodeShaderSettings(encodeShaderSettings(settings)))
        assertEquals(ShaderSettings(), decodeShaderSettings("not json"))
        assertEquals(ShaderSettings(), decodeShaderSettings(null))
        // Defaults asked for: shaders off until turned on, Apply always on, Balanced, battery saver respected.
        val defaults = ShaderSettings()
        assertTrue(defaults.applyAlways)
        assertEquals(ShaderQuality.Balanced, defaults.quality)
        assertTrue(defaults.disableOnBatterySaver)
        assertFalse(defaults.rememberPerSeries)
        assertTrue(defaults.finalScaler != FinalScaler.EwaLanczos)
    }
}
