package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the player shows the clock, battery and network over the video. Kept per device, off by default. */
object PlayerDeviceStatusSettingsRepository {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _enabled.value = PlayerDeviceStatusSettingsStorage.loadEnabled() ?: false
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        PlayerDeviceStatusSettingsStorage.saveEnabled(enabled)
    }
}

internal expect object PlayerDeviceStatusSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}
