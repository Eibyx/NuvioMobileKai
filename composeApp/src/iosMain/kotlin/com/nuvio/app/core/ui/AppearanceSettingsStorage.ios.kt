package com.nuvio.app.core.ui

import platform.Foundation.NSUserDefaults

actual object AppearanceSettingsStorage {
    private const val modeKey = "appearance_mode"

    actual fun loadMode(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(modeKey)

    actual fun saveMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = modeKey)
    }
}
