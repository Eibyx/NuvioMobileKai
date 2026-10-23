package com.nuvio.app.features.player.shaders

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "Player/Shaders"

/** The title playing, as far as shaders care. */
data class ShaderContent(
    val contentId: String? = null,
    /** The series (or movie) the preset is remembered for. */
    val seriesId: String? = null,
    val isAnime: Boolean = false,
)

/** Why the shaders are off for the current video even though the setting is on. */
enum class ShaderSuspension { BatterySaver, Performance }

/** What the current playback changes on top of the settings. */
data class ShaderRuntimeInputs(
    val content: ShaderContent = ShaderContent(),
    /** Picked in the player for this video; "auto" goes back to the settings. */
    val presetOverride: String? = null,
    /** The panel's master switch for this video; null follows the settings. */
    val enabledOverride: Boolean? = null,
    /** The panel's "Apply always" for this video; null follows the settings. */
    val applyAlwaysOverride: Boolean? = null,
    /** Lowered by the thermal and frame-drop guard. */
    val qualityCap: ShaderQuality? = null,
    /** The guard turned "Apply always" off for this video. */
    val applyAlwaysSuspended: Boolean = false,
    val suspension: ShaderSuspension? = null,
    /** Hold-to-compare: the original picture while held. */
    val comparing: Boolean = false,
    /** The chain being put together in the player's "Combine" tab, in order. */
    val liveChain: List<String>? = null,
)

/** mpv options that go with the shaders. */
data class MpvVideoOptions(
    val deband: DebandLevel = DebandLevel.Off,
    /** null leaves mpv's "fast" profile scalers (bilinear). */
    val scale: FinalScaler? = null,
    /** True when a chroma shader replaces mpv's chroma scaler. */
    val chromaShader: Boolean = false,
)

/** The chain and options for the current playback, worked out from the settings and [ShaderRuntimeInputs]. */
data class ShaderPlan(
    val active: Boolean,
    val presetId: String?,
    val quality: ShaderQuality,
    val applyAlways: Boolean,
    val entries: List<ChainEntry>,
    val options: MpvVideoOptions,
)

object ShaderController {
    /** The preset id that means "follow the settings" (Automatic or the manual preset). */
    const val AUTOMATIC = "auto"

    /** The chain of the "Combine" tab while it is being made. */
    const val LIVE_CHAIN_ID = "__live__"

    private val mutableInputs = MutableStateFlow(ShaderRuntimeInputs())
    val inputs: StateFlow<ShaderRuntimeInputs> = mutableInputs.asStateFlow()

    private val mutablePlan = MutableStateFlow<ShaderPlan?>(null)
    val plan: StateFlow<ShaderPlan?> = mutablePlan.asStateFlow()

    /** Resolves a shader id, bundled or imported, to its installed file. */
    var resolver: (ChainEntry) -> ChainShader? = ShaderLibrary::resolve

    /** A new video: everything the previous one changed is forgotten. */
    fun startPlayback(content: ShaderContent) {
        ShaderPerformanceGovernor.reset()
        mutableInputs.value = ShaderRuntimeInputs(content = content)
        refresh()
    }

    fun stopPlayback() {
        mutableInputs.value = ShaderRuntimeInputs()
        mutablePlan.value = null
        ShaderPlaybackSession.requestChain(emptyList())
    }

    fun updateInputs(transform: (ShaderRuntimeInputs) -> ShaderRuntimeInputs) {
        mutableInputs.update(transform)
        refresh()
    }

    /** Picks a preset in the player; remembered for the series when that setting is on. */
    fun pickPreset(presetId: String) {
        updateInputs { it.copy(presetOverride = presetId, liveChain = null) }
        val seriesId = mutableInputs.value.content.seriesId
        if (seriesId != null && presetId != AUTOMATIC) ShaderSettingsRepository.rememberForSeries(seriesId, presetId)
        InAppLogger.info(TAG, "preset picked in the player: $presetId")
    }

    fun setComparing(comparing: Boolean) {
        if (mutableInputs.value.comparing == comparing) return
        updateInputs { it.copy(comparing = comparing) }
        InAppLogger.info(TAG, if (comparing) "compare: original picture" else "compare: shaders back")
    }

    /** Runs [ids] as made in the "Combine" tab, live. */
    fun setLiveChain(ids: List<String>) = updateInputs { it.copy(liveChain = ids) }

    /** Recomputes the plan and hands the chain and options to the engine. */
    fun refresh() {
        val settings = ShaderSettingsRepository.settings.value
        val plan = plan(settings, mutableInputs.value)
        val previous = mutablePlan.value
        mutablePlan.value = plan
        val chain = if (plan.active) resolveChain(plan.entries) else emptyList()
        ShaderPlaybackSession.requestChain(chain)
        ShaderPlaybackSession.requestOptions(plan.options)
        val changed = previous == null || previous.presetId != plan.presetId || previous.active != plan.active ||
            previous.quality != plan.quality || previous.applyAlways != plan.applyAlways
        if (changed) {
            InAppLogger.info(
                TAG,
                "preset applied: ${plan.presetId ?: "none"} active=${plan.active} quality=${plan.quality} " +
                    "applyAlways=${plan.applyAlways} shaders=${chain.size}",
            )
        }
    }

    /** Resolves [entries] to files; a shader in the chain twice gets a distinct id per copy. */
    fun resolveChain(entries: List<ChainEntry>): List<ChainShader> {
        val seen = HashMap<String, Int>()
        return entries.mapNotNull(resolver).map { shader ->
            val count = (seen[shader.id] ?: 0) + 1
            seen[shader.id] = count
            if (count == 1) shader else shader.copy(id = "${shader.id}@$count")
        }
    }

    /** The preset for [content]: the series' remembered one, then Automatic or the manual preset. */
    fun presetFor(settings: ShaderSettings, inputs: ShaderRuntimeInputs): String {
        inputs.presetOverride?.takeIf { it != AUTOMATIC }?.let { return it }
        val seriesId = inputs.content.seriesId
        if (settings.rememberPerSeries && seriesId != null && inputs.presetOverride == null) {
            settings.seriesPresets[seriesId]?.let { return it }
        }
        return when (settings.mode) {
            ShaderMode.Automatic -> if (inputs.content.isAnime) settings.animePreset else settings.generalPreset
            ShaderMode.Manual -> settings.manualPreset
        }
    }

    /** A shader's name for the panel. */
    var titleOf: (String) -> String = ShaderLibrary::title

    /** Bundled shaders, then the imported ones. */
    var traitsOf: (String) -> ShaderPresets.ShaderTraits? = ShaderLibrary::traits

    fun plan(settings: ShaderSettings, inputs: ShaderRuntimeInputs): ShaderPlan {
        val enabled = (inputs.enabledOverride ?: settings.enabled) && ShaderFiles.isSupported
        val quality = listOfNotNull(settings.quality, inputs.qualityCap).minByOrNull { it.ordinal } ?: settings.quality
        val applyAlways = (inputs.applyAlwaysOverride ?: settings.applyAlways) && !inputs.applyAlwaysSuspended
        val active = enabled && inputs.suspension == null && !inputs.comparing
        val live = inputs.liveChain
        val presetId = when {
            !enabled -> null
            live != null -> ShaderPresets.CHAIN_PREFIX + LIVE_CHAIN_ID
            else -> presetFor(settings, inputs)
        }
        val resolveSettings = if (live != null) {
            settings.copy(chains = settings.chains + ShaderChainDefinition(LIVE_CHAIN_ID, "", live))
        } else {
            settings
        }
        val entries = presetId?.let { ShaderPresets.resolve(it, resolveSettings, quality, applyAlways, traits = traitsOf) }.orEmpty()
        val options = if (active) {
            MpvVideoOptions(
                deband = settings.deband,
                scale = settings.finalScaler,
                chromaShader = entries.any { ShaderCatalog.shader(it.shaderId)?.chroma == true },
            )
        } else {
            MpvVideoOptions()
        }
        return ShaderPlan(active, presetId, quality, applyAlways, entries, options)
    }
}
