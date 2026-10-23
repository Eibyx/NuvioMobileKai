package com.nuvio.app.features.player.shaders

import kotlin.math.roundToInt

/** A shader file mpv can load: the user shader syntax always has a `//!HOOK` or a `//!DESC`. */
object ShaderValidation {
    fun isMpvUserShader(content: String): Boolean =
        content.lineSequence().any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//!HOOK") || trimmed.startsWith("//!DESC")
        }

    /** Every `//!DESC` of [content]: how mpv names the shader's passes in `vo-passes`. */
    fun passDescriptions(content: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("//!DESC") }
            .map { it.removePrefix("//!DESC").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** True when [content] needs compute shaders (Vulkan or OpenGL ES 3.1+). */
    fun usesCompute(content: String): Boolean = content.lineSequence().any { it.trimStart().startsWith("//!COMPUTE") }

    fun extensionAllowed(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension == "glsl" || extension == "hook"
    }
}

/** One render pass mpv reports in the `vo-passes` property; times in nanoseconds. */
data class MpvRenderPass(val description: String, val lastNs: Long, val averageNs: Long, val peakNs: Long)

/** A shader of the active chain as the player shows it: running or not, why, and what it costs. */
data class ShaderPassStatus(
    val shaderId: String,
    val title: String,
    val active: Boolean,
    /** Average GPU time of its passes per frame, in milliseconds. */
    val costMs: Double,
    val inactiveReason: ShaderInactiveReason? = null,
)

enum class ShaderInactiveReason {
    /** The video is already about the screen's size and "Apply always" is off. */
    VideoMatchesScreen,
    /** The video is larger than the screen (4K on a phone): an upscaler never runs. */
    VideoLargerThanScreen,
    /** A chroma shader on a video without subsampled chroma. */
    NoSubsampledChroma,
    /** Its own `//!WHEN` condition kept it off. */
    ConditionNotMet,
    /** mpv could not compile or load it; it was taken out of the chain. */
    Failed,
}

/** A shader as the chain uses it: its file, its pass names and what kind of shader it is. */
data class ChainShader(
    val id: String,
    val title: String,
    val path: String,
    val passDescriptions: List<String>,
    val upscaler: Boolean,
    val chroma: Boolean,
    val forced: Boolean,
)

object ShaderPassReport {
    /**
     * Matches the passes mpv ran on the last fresh frame to the [chain]. A pass belongs to a
     * shader when its description contains one of the shader's `//!DESC` names (vo_gpu prefixes
     * "user shader:" and libplacebo appends the bound textures). When the same shader is in the
     * chain twice (Anime4K's second upscale at the same size), each run of a pass goes to the
     * copy that has had the fewest runs of it so far, in chain order.
     */
    fun statuses(
        chain: List<ChainShader>,
        passes: List<MpvRenderPass>,
        videoWidth: Int,
        videoHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        failedIds: Set<String> = emptySet(),
    ): List<ShaderPassStatus> {
        val assigned = chain.indices.associateWith { mutableListOf<MpvRenderPass>() }
        val runs = HashMap<Pair<Int, String>, Int>()
        for (pass in passes) {
            val matches = chain.indices.mapNotNull { index ->
                if (chain[index].id in failedIds) return@mapNotNull null
                chain[index].passDescriptions.filter { pass.description.contains(it) }.maxByOrNull { it.length }?.let { index to it }
            }
            val pick = matches.minByOrNull { runs[it] ?: 0 } ?: continue
            runs[pick] = (runs[pick] ?: 0) + 1
            assigned.getValue(pick.first) += pass
        }
        return chain.mapIndexed { index, shader -> status(shader, assigned.getValue(index), videoWidth, videoHeight, outputWidth, outputHeight, failedIds) }
    }

    private fun status(
        shader: ChainShader,
        own: List<MpvRenderPass>,
        videoWidth: Int,
        videoHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        failedIds: Set<String>,
    ): ShaderPassStatus {
        if (shader.id in failedIds) {
            return ShaderPassStatus(shader.id, shader.title, active = false, costMs = 0.0, inactiveReason = ShaderInactiveReason.Failed)
        }
        return if (own.isNotEmpty()) {
            ShaderPassStatus(shader.id, shader.title, active = true, costMs = own.sumOf { it.averageNs } / 1_000_000.0)
        } else {
            ShaderPassStatus(
                shader.id,
                shader.title,
                active = false,
                costMs = 0.0,
                inactiveReason = inactiveReason(shader, videoWidth, videoHeight, outputWidth, outputHeight),
            )
        }
    }

    /** Why a shader that did not run was skipped, judged from the sizes involved. */
    fun inactiveReason(
        shader: ChainShader,
        videoWidth: Int,
        videoHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): ShaderInactiveReason {
        val factor = scaleFactor(videoWidth, videoHeight, outputWidth, outputHeight)
        return when {
            shader.upscaler && factor != null && factor < 1.0 -> ShaderInactiveReason.VideoLargerThanScreen
            shader.upscaler && !shader.forced -> ShaderInactiveReason.VideoMatchesScreen
            shader.chroma -> ShaderInactiveReason.NoSubsampledChroma
            else -> ShaderInactiveReason.ConditionNotMet
        }
    }

    /** How much the video is enlarged to fill the output; below 1.0 it is shrunk. */
    fun scaleFactor(videoWidth: Int, videoHeight: Int, outputWidth: Int, outputHeight: Int): Double? {
        if (videoWidth <= 0 || videoHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return null
        return minOf(outputWidth.toDouble() / videoWidth, outputHeight.toDouble() / videoHeight)
    }

    /** Total GPU time of the frame's passes in milliseconds. */
    fun totalCostMs(passes: List<MpvRenderPass>): Double = passes.sumOf { it.averageNs } / 1_000_000.0

    fun formatMs(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10} ms"
    }
}

/** Reads mpv's log for shaders that failed to compile or load, so they can leave the chain. */
object ShaderFailureDetector {
    private const val MPV_LOG_ERROR = 20

    private val shaderWords = listOf("shader", "glsl", "spir", "hook", "compile", "compilation", "parsing")

    /** True for an mpv error (level 20 or lower) from the video output about a shader. */
    fun isShaderError(prefix: String, level: Int, text: String): Boolean {
        if (level > MPV_LOG_ERROR) return false
        if (!prefix.startsWith("vo") && !prefix.contains("libplacebo") && !prefix.contains("shader")) return false
        val lower = text.lowercase()
        return shaderWords.any { lower.contains(it) }
    }

    /**
     * The shader of [chain] the error names, by file name, path or pass description. Null when
     * the message names none of them.
     */
    fun failingShader(text: String, chain: List<ChainShader>): ChainShader? =
        chain.firstOrNull { shader ->
            val fileName = shader.path.substringAfterLast('/')
            text.contains(shader.path) || text.contains(fileName) ||
                shader.passDescriptions.any { it.length >= 6 && text.contains(it) }
        }
}

/** The renderer mpv is set up with before a file opens. */
data class MpvRenderBackend(val vo: String, val gpuApi: String, val gpuContext: String, val hwdec: String)

/*
 * hwdec choice adapted from mpvRx's RendererBackendPolicy (https://github.com/Riteshp2001/mpvRx,
 * ui/player/RendererBackendPolicy.kt), AGPL-3.0-or-later.
 */
object MpvRenderBackendPolicy {
    /**
     * Shaders need vo=gpu or gpu-next. Vulkan (gpu-next only) is used when shaders are on and the
     * device supports it, since compute shaders (ArtCNN, NVScaler) need Vulkan or GLES 3.1+;
     * otherwise OpenGL. Our libmpv cannot map MediaCodec frames into Vulkan, so Vulkan decodes
     * with copies; OpenGL can render MediaCodec frames directly.
     */
    fun select(
        videoOutput: String,
        hardwareDecoding: Boolean,
        deviceSupportsVulkan: Boolean,
        shadersEnabled: Boolean,
        buildMapsMediaCodecIntoVulkan: Boolean = false,
    ): MpvRenderBackend {
        val vo = if (videoOutput == "gpu" || videoOutput == "gpu-next") videoOutput else "gpu-next"
        val vulkan = shadersEnabled && deviceSupportsVulkan && vo == "gpu-next"
        val hwdec = when {
            !hardwareDecoding -> "no"
            !vulkan || buildMapsMediaCodecIntoVulkan -> "mediacodec,mediacodec-copy,no"
            else -> "mediacodec-copy,no"
        }
        return if (vulkan) {
            MpvRenderBackend(vo = vo, gpuApi = "vulkan", gpuContext = "androidvk", hwdec = hwdec)
        } else {
            MpvRenderBackend(vo = vo, gpuApi = "opengl", gpuContext = "android", hwdec = hwdec)
        }
    }
}
