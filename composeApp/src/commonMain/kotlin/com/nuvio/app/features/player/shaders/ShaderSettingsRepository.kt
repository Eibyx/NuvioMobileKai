package com.nuvio.app.features.player.shaders

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

private const val TAG = "Player/Shaders"

internal expect object ShaderSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

private val settingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun decodeShaderSettings(payload: String?): ShaderSettings =
    payload?.let { runCatching { settingsJson.decodeFromString(ShaderSettings.serializer(), it) }.getOrNull() } ?: ShaderSettings()

internal fun encodeShaderSettings(settings: ShaderSettings): String =
    settingsJson.encodeToString(ShaderSettings.serializer(), settings)

/** The shader settings of the active profile. */
object ShaderSettingsRepository {
    private val mutableSettings = MutableStateFlow(ShaderSettings())
    val settings: StateFlow<ShaderSettings> = mutableSettings.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        mutableSettings.value = ShaderSettings()
        ShaderPlaybackSession.enabled.value = false
    }

    fun update(transform: (ShaderSettings) -> ShaderSettings) {
        ensureLoaded()
        val before = mutableSettings.value
        val after = transform(before)
        if (after == before) return
        mutableSettings.value = after
        ShaderSettingsStorage.savePayload(encodeShaderSettings(after))
        ShaderPlaybackSession.enabled.value = after.enabled
        logChange(before, after)
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setApplyAlways(applyAlways: Boolean) = update { it.copy(applyAlways = applyAlways) }
    fun setMode(mode: ShaderMode) = update { it.copy(mode = mode) }
    fun setAnimePreset(presetId: String) = update { it.copy(animePreset = presetId) }
    fun setGeneralPreset(presetId: String) = update { it.copy(generalPreset = presetId) }
    fun setManualPreset(presetId: String) = update { it.copy(manualPreset = presetId, mode = ShaderMode.Manual) }
    fun setQuality(quality: ShaderQuality) = update { it.copy(quality = quality) }
    fun setSharpen(sharpen: SharpenExtra) = update { it.copy(sharpen = sharpen) }
    fun setColor(color: ColorExtra) = update { it.copy(color = color) }
    fun setDeband(deband: DebandLevel) = update { it.copy(deband = deband) }
    fun setFinalScaler(scaler: FinalScaler) = update { it.copy(finalScaler = scaler) }
    fun setRememberPerSeries(remember: Boolean) = update { it.copy(rememberPerSeries = remember) }
    fun setDisableOnBatterySaver(disable: Boolean) = update { it.copy(disableOnBatterySaver = disable) }

    /** Remembers [presetId] for [seriesId] when "Remember per series" is on. */
    fun rememberForSeries(seriesId: String, presetId: String) = update { settings ->
        if (!settings.rememberPerSeries) settings else settings.copy(seriesPresets = settings.seriesPresets + (seriesId to presetId))
    }

    private fun loadFromDisk() {
        hasLoaded = true
        mutableSettings.value = decodeShaderSettings(ShaderSettingsStorage.loadPayload())
        ShaderPlaybackSession.enabled.value = mutableSettings.value.enabled
    }

    /** Logs what changed; settings hold no personal data, series ids aside, which stay out. */
    private fun logChange(before: ShaderSettings, after: ShaderSettings) {
        val changes = buildList {
            if (before.enabled != after.enabled) add("enabled=${after.enabled}")
            if (before.applyAlways != after.applyAlways) add("applyAlways=${after.applyAlways}")
            if (before.mode != after.mode) add("mode=${after.mode}")
            if (before.animePreset != after.animePreset) add("animePreset=${after.animePreset}")
            if (before.generalPreset != after.generalPreset) add("generalPreset=${after.generalPreset}")
            if (before.manualPreset != after.manualPreset) add("manualPreset=${after.manualPreset}")
            if (before.quality != after.quality) add("quality=${after.quality}")
            if (before.sharpen != after.sharpen) add("sharpen=${after.sharpen}")
            if (before.color != after.color) add("color=${after.color}")
            if (before.deband != after.deband) add("deband=${after.deband}")
            if (before.finalScaler != after.finalScaler) add("finalScaler=${after.finalScaler}")
            if (before.rememberPerSeries != after.rememberPerSeries) add("rememberPerSeries=${after.rememberPerSeries}")
            if (before.disableOnBatterySaver != after.disableOnBatterySaver) add("disableOnBatterySaver=${after.disableOnBatterySaver}")
            if (before.seriesPresets.size != after.seriesPresets.size) add("seriesPresets=${after.seriesPresets.size}")
            if (before.chains != after.chains) add("chains=${after.chains.size}")
            if (before.imported != after.imported) add("imported=${after.imported.size}")
        }
        if (changes.isNotEmpty()) InAppLogger.info(TAG, "settings: ${changes.joinToString(" ")}")
    }
}
