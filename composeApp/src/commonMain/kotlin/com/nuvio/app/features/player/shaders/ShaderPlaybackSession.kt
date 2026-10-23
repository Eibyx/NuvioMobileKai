package com.nuvio.app.features.player.shaders

import com.nuvio.app.core.logging.InAppLogger
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.shader_warning_failed
import nuvio.composeapp.generated.resources.shader_warning_failed_unknown
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "Player/Shaders"

/** What mpv actually runs with, read back after the file opens. */
data class ActiveRenderBackend(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val hwdecCurrent: String,
)

/** Something the player tells the user about its shaders. */
sealed interface ShaderWarning {
    /** mpv could not compile or load [title]; it left the chain and playback goes on. */
    data class ShaderFailed(val title: String) : ShaderWarning

    /** A shader error mpv did not attribute to a file. */
    data object UnknownShaderFailed : ShaderWarning
}

suspend fun shaderWarningText(warning: ShaderWarning): String = when (warning) {
    is ShaderWarning.ShaderFailed -> getString(Res.string.shader_warning_failed, warning.title)
    ShaderWarning.UnknownShaderFailed -> getString(Res.string.shader_warning_failed_unknown)
}

data class ShaderPlaybackState(
    /** True while mpv plays; with ExoPlayer the shaders do not apply. */
    val mpvActive: Boolean = false,
    val backend: ActiveRenderBackend? = null,
    /** The chain mpv was given, without the shaders that failed. */
    val appliedChain: List<ChainShader> = emptyList(),
    val failedIds: Set<String> = emptySet(),
    val statuses: List<ShaderPassStatus> = emptyList(),
    /** GPU time of every pass of the last frame (shaders, scaling, OSD), in milliseconds. */
    val frameCostMs: Double = 0.0,
)

/**
 * The bridge between what the user picks (settings, the player's panel) and the mpv engine. The
 * UI requests a chain; the engine applies it, reports which shaders run, what they cost and
 * which failed. Shaders that fail are left out until the chain is requested again.
 */
object ShaderPlaybackSession {
    /** The global shader switch; mpv picks its renderer (Vulkan or OpenGL) from it when a video opens. */
    val enabled = MutableStateFlow(false)

    private val requested = MutableStateFlow<List<ChainShader>>(emptyList())
    val requestedChain: StateFlow<List<ChainShader>> = requested.asStateFlow()

    private val mutableState = MutableStateFlow(ShaderPlaybackState())
    val state: StateFlow<ShaderPlaybackState> = mutableState.asStateFlow()

    private val mutableWarnings = MutableSharedFlow<ShaderWarning>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val warnings: SharedFlow<ShaderWarning> = mutableWarnings.asSharedFlow()

    /** Asks the engine to run [chain]; a new request gives failed shaders another chance. */
    fun requestChain(chain: List<ChainShader>) {
        if (chain == requested.value) return
        requested.value = chain
        mutableState.update { it.copy(failedIds = it.failedIds.filterTo(mutableSetOf()) { id -> chain.none { s -> s.id == id } }) }
    }

    /** The chain the engine should give mpv: the requested one without failed shaders. */
    fun effectiveChain(): List<ChainShader> {
        val failed = mutableState.value.failedIds
        return requested.value.filter { it.id !in failed }
    }

    fun onMpvStarted() {
        mutableState.update { ShaderPlaybackState(mpvActive = true, failedIds = it.failedIds) }
    }

    fun onMpvStopped() {
        mutableState.value = ShaderPlaybackState()
    }

    fun onChainApplied(chain: List<ChainShader>) {
        mutableState.update { it.copy(appliedChain = chain) }
    }

    fun onBackend(backend: ActiveRenderBackend) {
        if (mutableState.value.backend == backend) return
        mutableState.update { it.copy(backend = backend) }
        InAppLogger.info(
            TAG,
            "render vo=${backend.vo} gpu-api=${backend.gpuApi} gpu-context=${backend.gpuContext} hwdec-current=${backend.hwdecCurrent}",
        )
    }

    fun onStatuses(statuses: List<ShaderPassStatus>, frameCostMs: Double) {
        mutableState.update { it.copy(statuses = statuses, frameCostMs = frameCostMs) }
    }

    /** [shader] failed to compile or load: it leaves the chain and the user is told. */
    fun onShaderFailed(shader: ChainShader, message: String) {
        if (shader.id in mutableState.value.failedIds) return
        mutableState.update { it.copy(failedIds = it.failedIds + shader.id) }
        InAppLogger.warn(TAG, "shader failed, removed from the chain: ${shader.title} (${message.take(160)})")
        mutableWarnings.tryEmit(ShaderWarning.ShaderFailed(shader.title))
    }

    fun onUnknownShaderFailure(message: String) {
        InAppLogger.warn(TAG, "shader error without a file name: ${message.take(160)}")
        mutableWarnings.tryEmit(ShaderWarning.UnknownShaderFailed)
    }
}
