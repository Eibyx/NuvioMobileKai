package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the app draws dark or light; the player and artwork heroes stay dark either way. */
enum class AppearanceMode {
    DARK,
    LIGHT,
    SYSTEM,
}

/** The appearance follows the device, so it is kept per device and not synced. */
object AppearanceSettingsRepository {
    private val _mode = MutableStateFlow(AppearanceMode.DARK)
    val mode: StateFlow<AppearanceMode> = _mode.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _mode.value = AppearanceSettingsStorage.loadMode()
            ?.let { stored -> AppearanceMode.entries.firstOrNull { it.name == stored } }
            ?: AppearanceMode.DARK
    }

    fun setMode(mode: AppearanceMode) {
        ensureLoaded()
        if (_mode.value == mode) return
        _mode.value = mode
        AppearanceSettingsStorage.saveMode(mode.name)
    }
}

expect object AppearanceSettingsStorage {
    fun loadMode(): String?
    fun saveMode(mode: String)
}
