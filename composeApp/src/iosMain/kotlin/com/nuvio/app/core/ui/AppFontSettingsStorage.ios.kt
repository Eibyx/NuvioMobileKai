package com.nuvio.app.core.ui

import platform.Foundation.NSUserDefaults

actual object AppFontSettingsStorage {
    private const val fontKey = "app_font"
    private const val useSystemFontScaleKey = "use_system_font_scale"

    actual fun loadFont(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(fontKey)

    actual fun saveFont(font: String) {
        NSUserDefaults.standardUserDefaults.setObject(font, forKey = fontKey)
    }

    actual fun loadUseSystemFontScale(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(useSystemFontScaleKey) == null) null else defaults.boolForKey(useSystemFontScaleKey)
    }

    actual fun saveUseSystemFontScale(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = useSystemFontScaleKey)
    }
}
