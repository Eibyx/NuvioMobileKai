package com.nuvio.app.features.player

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.shaders.ActiveRenderBackend
import com.nuvio.app.features.player.shaders.ChainShader
import com.nuvio.app.features.player.shaders.DebandLevel
import com.nuvio.app.features.player.shaders.MpvVideoOptions
import com.nuvio.app.features.player.shaders.MpvRenderBackend
import com.nuvio.app.features.player.shaders.MpvRenderBackendPolicy
import com.nuvio.app.features.player.shaders.MpvRenderPass
import com.nuvio.app.features.player.shaders.ShaderFailureDetector
import com.nuvio.app.features.player.shaders.ShaderPassReport
import com.nuvio.app.features.player.shaders.ShaderPlaybackSession
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

private const val TAG = "Player/Shaders"
private const val PREFERENCES = "nuvio_mpv_renderer"
private const val KEY_VULKAN_FAILED = "vulkan_failed"

/** Vulkan 1.1, the version libplacebo needs. */
private const val VULKAN_1_1 = 0x401000

internal object MpvRenderer {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private fun preferences() = context?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** True when the device offers Vulkan 1.1 and Vulkan never failed to start mpv's video output here. */
    fun deviceSupportsVulkan(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (preferences()?.getBoolean(KEY_VULKAN_FAILED, false) == true) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)
    }

    fun rememberVulkanFailed() {
        preferences()?.edit()?.putBoolean(KEY_VULKAN_FAILED, true)?.apply()
    }

    fun select(context: Context, videoOutput: AndroidLibmpvVideoOutput, hardwareDecoding: Boolean, shadersEnabled: Boolean) =
        MpvRenderBackendPolicy.select(
            videoOutput = videoOutput.mpvValue,
            hardwareDecoding = hardwareDecoding,
            deviceSupportsVulkan = deviceSupportsVulkan(context),
            shadersEnabled = shadersEnabled,
        )
}

/**
 * Runs the shader chain on mpv: gives it the files, reads which passes ran and what they cost
 * (`vo-passes`), and takes out shaders mpv fails to compile. Every call runs on the libmpv thread.
 */
internal class MpvShaderController(private val mpv: MPVLib, private var backend: MpvRenderBackend) {
    private var applied: List<ChainShader>? = null
    private var appliedOptions: MpvVideoOptions? = null
    private var unattributedError: String? = null
    private var backendLogged = false
    private var fellBackToOpenGl = false

    fun applyOptions() {
        mpv.setOptionString("vo", backend.vo)
        mpv.setOptionString("gpu-api", backend.gpuApi)
        mpv.setOptionString("gpu-context", backend.gpuContext)
        mpv.setOptionString("hwdec", backend.hwdec)
        InAppLogger.info(
            TAG,
            "requested vo=${backend.vo} gpu-api=${backend.gpuApi} gpu-context=${backend.gpuContext} hwdec=${backend.hwdec}",
        )
    }

    /** Gives mpv the effective chain when it changed; mpv swaps shaders live, keeping the position. */
    fun syncChain() {
        val chain = ShaderPlaybackSession.effectiveChain()
        if (chain == applied) return
        val paths = chain.joinToString(":") { it.path }
        runCatching { mpv.setPropertyString("glsl-shaders", paths) }
            .onFailure { InAppLogger.warn(TAG, "glsl-shaders could not be set: ${InAppLogger.throwableSummary(it)}") }
        applied = chain
        ShaderPlaybackSession.onChainApplied(chain)
        InAppLogger.info(TAG, "chain applied: ${if (chain.isEmpty()) "none" else chain.joinToString(" → ") { it.title }}")
    }

    /**
     * Deband and the main scalers. Without shaders, mpv's "fast" profile values (bilinear, no
     * deband). With a chroma shader (KrigBilateral, CfL) cscale is what default-shader-pack uses.
     */
    fun syncOptions() {
        val options = ShaderPlaybackSession.requestedOptions.value
        if (options == appliedOptions) return
        val deband = options.deband
        val scale = options.scale?.mpvValue ?: "bilinear"
        runCatching {
            mpv.setPropertyString("deband", if (deband == DebandLevel.Off) "no" else "yes")
            if (deband != DebandLevel.Off) {
                mpv.setPropertyString("deband-iterations", deband.iterations.toString())
                mpv.setPropertyString("deband-threshold", deband.threshold.toString())
                mpv.setPropertyString("deband-range", deband.range.toString())
                mpv.setPropertyString("deband-grain", "0")
            }
            mpv.setPropertyString("scale", scale)
            mpv.setPropertyString("cscale", if (options.chromaShader) "mitchell" else scale)
            mpv.setPropertyString("dscale", if (options.scale != null) "mitchell" else "bilinear")
        }.onFailure { InAppLogger.warn(TAG, "video options could not be set: ${InAppLogger.throwableSummary(it)}") }
        appliedOptions = options
        InAppLogger.info(TAG, "video options: deband=$deband scale=$scale chromaShader=${options.chromaShader}")
    }

    fun onFileLoaded() {
        backendLogged = false
    }

    /** Reads `vo-passes` and the sizes; reports the real backend once per file. */
    fun poll() {
        if (!backendLogged) {
            val vo = mpv.getPropertyString("current-vo")
            if (!vo.isNullOrBlank()) {
                backendLogged = true
                ShaderPlaybackSession.onBackend(
                    ActiveRenderBackend(
                        vo = vo,
                        gpuApi = mpv.getPropertyString("gpu-api").orEmpty().ifBlank { backend.gpuApi },
                        gpuContext = mpv.getPropertyString("current-gpu-context").orEmpty()
                            .ifBlank { mpv.getPropertyString("gpu-context").orEmpty() },
                        hwdecCurrent = mpv.getPropertyString("hwdec-current").orEmpty().ifBlank { "no" },
                    ),
                )
            }
        }
        val chain = applied ?: return
        val passes = runCatching { mpv.getPropertyNode("vo-passes") }.getOrNull()?.freshPasses().orEmpty()
        val videoWidth = mpv.getPropertyInt("video-params/w") ?: 0
        val videoHeight = mpv.getPropertyInt("video-params/h") ?: 0
        val osd = runCatching { mpv.getPropertyNode("osd-dimensions") }.getOrNull()
        val outputWidth = osd?.let { (it.int("w") ?: 0) - (it.int("ml") ?: 0) - (it.int("mr") ?: 0) } ?: 0
        val outputHeight = osd?.let { (it.int("h") ?: 0) - (it.int("mt") ?: 0) - (it.int("mb") ?: 0) } ?: 0
        val failed = ShaderPlaybackSession.state.value.failedIds
        val statuses = ShaderPassReport.statuses(chain, passes, videoWidth, videoHeight, outputWidth, outputHeight, failed)
        unattributedError?.let { message ->
            // mpv named no file: blame the shader that should run but has no passes, when there is one.
            val silent = statuses.filter { !it.active && it.inactiveReason != null }
            val candidate = silent.singleOrNull()?.let { status -> chain.firstOrNull { it.id == status.shaderId } }
            if (candidate != null && passes.isNotEmpty()) {
                ShaderPlaybackSession.onShaderFailed(candidate, message)
            } else {
                ShaderPlaybackSession.onUnknownShaderFailure(message)
            }
            unattributedError = null
            syncChain()
        }
        ShaderPlaybackSession.onStatuses(statuses, ShaderPassReport.totalCostMs(passes))
    }

    /** Called for every mpv log line (msg-level warn and above). */
    fun onLog(prefix: String, level: Int, text: String) {
        if (backend.gpuApi == "vulkan" && !fellBackToOpenGl && level <= 20 && prefix.startsWith("vo") &&
            (text.contains("video_out", ignoreCase = true) || text.contains("GPU context", ignoreCase = true))
        ) {
            fallBackToOpenGl(text)
            return
        }
        if (!ShaderFailureDetector.isShaderError(prefix, level, text)) return
        val chain = applied.orEmpty()
        if (chain.isEmpty()) return
        val shader = ShaderFailureDetector.failingShader(text, chain)
        if (shader != null) {
            ShaderPlaybackSession.onShaderFailed(shader, text)
            syncChain()
        } else {
            unattributedError = text
        }
    }

    /** Vulkan did not start: OpenGL from now on, here and in later playbacks. */
    private fun fallBackToOpenGl(message: String) {
        fellBackToOpenGl = true
        MpvRenderer.rememberVulkanFailed()
        val hwdec = if (backend.hwdec == "no") "no" else "mediacodec,mediacodec-copy,no"
        backend = backend.copy(gpuApi = "opengl", gpuContext = "android", hwdec = hwdec)
        InAppLogger.warn(TAG, "Vulkan video output failed, switching to OpenGL: ${message.take(160)}")
        runCatching {
            mpv.setPropertyString("gpu-api", "opengl")
            mpv.setPropertyString("gpu-context", "android")
            mpv.setPropertyString("hwdec", hwdec)
            // Setting vo again re-creates the video output with the new API.
            mpv.setPropertyString("vo", backend.vo)
        }.onFailure { InAppLogger.warn(TAG, "OpenGL fallback failed: ${InAppLogger.throwableSummary(it)}") }
        backendLogged = false
    }
}

private fun MPVNode.int(key: String): Int? = runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.long(key: String): Long = runCatching { this[key]?.asInt() }.getOrNull() ?: 0L

/** The passes of the last fresh frame: `{fresh: [{desc, last, avg, peak, ...}], redraw: [...]}`. */
private fun MPVNode.freshPasses(): List<MpvRenderPass> {
    val fresh = runCatching { this["fresh"]?.asArray() }.getOrNull() ?: return emptyList()
    return fresh.mapNotNull { pass ->
        val description = runCatching { pass["desc"]?.asString() }.getOrNull() ?: return@mapNotNull null
        MpvRenderPass(description, pass.long("last"), pass.long("avg"), pass.long("peak"))
    }
}
