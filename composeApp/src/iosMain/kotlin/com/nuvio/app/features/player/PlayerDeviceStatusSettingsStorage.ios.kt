package com.nuvio.app.features.player

import platform.Foundation.NSUserDefaults

internal actual object PlayerDeviceStatusSettingsStorage {
    private const val enabledKey = "player_device_status_enabled"

    actual fun loadEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(enabledKey) == null) null else defaults.boolForKey(enabledKey)
    }

    actual fun saveEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = enabledKey)
    }
}
