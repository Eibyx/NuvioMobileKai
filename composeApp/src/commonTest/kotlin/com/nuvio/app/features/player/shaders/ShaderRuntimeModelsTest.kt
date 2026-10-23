package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderRuntimeModelsTest {
    private val upscale = ChainShader(
        id = "anime4k_upscale_m",
        title = "Anime4K Upscale M",
        path = "/data/shaders/Anime4K_Upscale_CNN_x2_M.glsl",
        passDescriptions = listOf("Anime4K-v4.0-Upscale-CNN-x2-(M)-Conv-4x3x3x3", "Anime4K-v4.0-Upscale-CNN-x2-(M)-Depth-to-Space"),
        upscaler = true,
        chroma = false,
        forced = false,
    )
    private val krig = ChainShader(
        id = "krig",
        title = "KrigBilateral",
        path = "/data/shaders/KrigBilateral.glsl",
        passDescriptions = listOf("KrigBilateral Downscaling Y", "KrigBilateral Upscaling UV"),
        upscaler = false,
        chroma = true,
        forced = false,
    )

    @Test
    fun validationNeedsHookOrDesc() {
        assertTrue(ShaderValidation.isMpvUserShader("//!HOOK MAIN\nvec4 hook() { return HOOKED_tex(HOOKED_pos); }"))
        assertTrue(ShaderValidation.isMpvUserShader("  //!DESC something"))
        assertFalse(ShaderValidation.isMpvUserShader("void main() { gl_FragColor = vec4(1.0); }"))
        assertFalse(ShaderValidation.isMpvUserShader(""))
        assertTrue(ShaderValidation.extensionAllowed("A.GLSL"))
        assertTrue(ShaderValidation.extensionAllowed("ravu.hook"))
        assertFalse(ShaderValidation.extensionAllowed("shader.frag"))
        assertFalse(ShaderValidation.extensionAllowed("glsl"))
        assertTrue(ShaderValidation.usesCompute("//!DESC x\n//!COMPUTE 32 8\n"))
        assertEquals(listOf("a", "b"), ShaderValidation.passDescriptions("//!DESC a\n//!DESC b\n//!DESC a\n"))
    }

    @Test
    fun passesAreMatchedToTheirShader() {
        val passes = listOf(
            MpvRenderPass("user shader: Anime4K-v4.0-Upscale-CNN-x2-(M)-Conv-4x3x3x3", 1_000_000, 1_200_000, 2_000_000),
            MpvRenderPass("user shader: Anime4K-v4.0-Upscale-CNN-x2-(M)-Depth-to-Space", 300_000, 400_000, 500_000),
            MpvRenderPass("scaling (OUTPUT)", 500_000, 500_000, 500_000),
        )
        val statuses = ShaderPassReport.statuses(listOf(upscale), passes, 1280, 720, 2169, 1220)
        assertTrue(statuses.single().active)
        assertEquals(1.6, statuses.single().costMs, 1e-9)
        assertEquals(2.1, ShaderPassReport.totalCostMs(passes), 1e-9)
        assertEquals("1.6 ms", ShaderPassReport.formatMs(1.6))
    }

    @Test
    fun theSameShaderTwiceSharesItsPassesInOrder() {
        val first = upscale.copy(id = "anime4k_upscale_s#forced", forced = true)
        val second = upscale.copy(id = "anime4k_upscale_s")
        val run = MpvRenderPass("Anime4K-v4.0-Upscale-CNN-x2-(M)-Conv-4x3x3x3", 0, 1_000_000, 0)
        val both = ShaderPassReport.statuses(listOf(first, second), listOf(run, run.copy(averageNs = 3_000_000)), 480, 360, 2169, 1220)
        assertEquals(listOf(1.0, 3.0), both.map { it.costMs })
        // Only the forced first copy ran (1080p): the second reports why it did not.
        val one = ShaderPassReport.statuses(listOf(first, second), listOf(run), 1920, 1080, 2169, 1220)
        assertTrue(one[0].active)
        assertEquals(ShaderInactiveReason.VideoMatchesScreen, one[1].inactiveReason)
    }

    @Test
    fun skippedShadersExplainWhy() {
        fun reason(shader: ChainShader, width: Int, height: Int, failed: Set<String> = emptySet()) =
            ShaderPassReport.statuses(listOf(shader), emptyList(), width, height, 2169, 1220, failed).single().inactiveReason
        assertEquals(ShaderInactiveReason.VideoMatchesScreen, reason(upscale, 1920, 1080))
        assertEquals(ShaderInactiveReason.VideoLargerThanScreen, reason(upscale, 3840, 2160))
        assertEquals(ShaderInactiveReason.ConditionNotMet, reason(upscale.copy(forced = true), 1920, 1080))
        assertEquals(ShaderInactiveReason.NoSubsampledChroma, reason(krig, 1920, 1080))
        assertEquals(ShaderInactiveReason.Failed, reason(upscale, 1280, 720, setOf(upscale.id)))
        assertNull(ShaderPassReport.scaleFactor(0, 1080, 2712, 1220))
        assertEquals(1220.0 / 1080, ShaderPassReport.scaleFactor(1920, 1080, 2712, 1220)!!, 1e-9)
    }

    @Test
    fun shaderErrorsAreRecognisedAndNamed() {
        assertTrue(ShaderFailureDetector.isShaderError("vo/gpu-next/libplacebo", 20, "shaderc: compilation failed"))
        assertTrue(ShaderFailureDetector.isShaderError("vo/gpu", 10, "Failed parsing user shader /data/shaders/KrigBilateral.glsl"))
        // Warnings and other modules are not shader failures.
        assertFalse(ShaderFailureDetector.isShaderError("vo/gpu", 30, "shader cache miss"))
        assertFalse(ShaderFailureDetector.isShaderError("ffmpeg", 20, "glsl"))
        assertFalse(ShaderFailureDetector.isShaderError("vo/gpu", 20, "Could not create swapchain"))
        val chain = listOf(upscale, krig)
        assertEquals(krig, ShaderFailureDetector.failingShader("Failed parsing user shader /data/shaders/KrigBilateral.glsl", chain))
        assertEquals(upscale, ShaderFailureDetector.failingShader("error in pass Anime4K-v4.0-Upscale-CNN-x2-(M)-Depth-to-Space", chain))
        assertNull(ShaderFailureDetector.failingShader("shaderc: compilation failed", chain))
    }

    @Test
    fun backendFollowsTheRendererPolicy() {
        val vulkan = MpvRenderBackendPolicy.select("gpu-next", hardwareDecoding = true, deviceSupportsVulkan = true, shadersEnabled = true)
        assertEquals(MpvRenderBackend("gpu-next", "vulkan", "androidvk", "mediacodec-copy,no"), vulkan)
        val openGl = MpvRenderBackendPolicy.select("gpu", hardwareDecoding = true, deviceSupportsVulkan = true, shadersEnabled = true)
        assertEquals(MpvRenderBackend("gpu", "opengl", "android", "mediacodec,mediacodec-copy,no"), openGl)
        val noShaders = MpvRenderBackendPolicy.select("gpu-next", hardwareDecoding = true, deviceSupportsVulkan = true, shadersEnabled = false)
        assertEquals("opengl", noShaders.gpuApi)
        assertEquals("mediacodec,mediacodec-copy,no", noShaders.hwdec)
        val noVulkan = MpvRenderBackendPolicy.select("gpu-next", hardwareDecoding = true, deviceSupportsVulkan = false, shadersEnabled = true)
        assertEquals("opengl", noVulkan.gpuApi)
        assertEquals("no", MpvRenderBackendPolicy.select("gpu-next", hardwareDecoding = false, deviceSupportsVulkan = true, shadersEnabled = true).hwdec)
        assertEquals("gpu-next", MpvRenderBackendPolicy.select("mediacodec_embed", true, true, true).vo)
    }
}
