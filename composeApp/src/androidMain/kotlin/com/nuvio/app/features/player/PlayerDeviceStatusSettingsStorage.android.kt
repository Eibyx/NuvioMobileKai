package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences

internal actual object PlayerDeviceStatusSettingsStorage {
    private const val preferencesName = "nuvio_player_device_status"
    private const val enabledKey = "device_status_enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? =
        preferences?.takeIf { it.contains(enabledKey) }?.getBoolean(enabledKey, false)

    actual fun saveEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(enabledKey, enabled)?.apply()
    }
}
