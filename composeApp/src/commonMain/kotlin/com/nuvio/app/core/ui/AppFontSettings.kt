package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppFont {
    /** JetBrains Sans, bundled with the app. */
    NUVIO,

    /** The platform's default font. */
    SYSTEM,
}

data class AppFontSettings(
    val font: AppFont = AppFont.NUVIO,
    val useSystemFontScale: Boolean = false,
)

/** App font choices. They describe the device, so they are kept per device and not synced. */
object AppFontSettingsRepository {
    private val _settings = MutableStateFlow(AppFontSettings())
    val settings: StateFlow<AppFontSettings> = _settings.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _settings.value = AppFontSettings(
            font = AppFontSettingsStorage.loadFont()
                ?.let { stored -> AppFont.entries.firstOrNull { it.name == stored } }
                ?: AppFont.NUVIO,
            useSystemFontScale = AppFontSettingsStorage.loadUseSystemFontScale() ?: false,
        )
    }

    fun setFont(font: AppFont) {
        ensureLoaded()
        if (_settings.value.font == font) return
        _settings.value = _settings.value.copy(font = font)
        AppFontSettingsStorage.saveFont(font.name)
    }

    fun setUseSystemFontScale(enabled: Boolean) {
        ensureLoaded()
        if (_settings.value.useSystemFontScale == enabled) return
        _settings.value = _settings.value.copy(useSystemFontScale = enabled)
        AppFontSettingsStorage.saveUseSystemFontScale(enabled)
    }
}

expect object AppFontSettingsStorage {
    fun loadFont(): String?
    fun saveFont(font: String)
    fun loadUseSystemFontScale(): Boolean?
    fun saveUseSystemFontScale(enabled: Boolean)
}
