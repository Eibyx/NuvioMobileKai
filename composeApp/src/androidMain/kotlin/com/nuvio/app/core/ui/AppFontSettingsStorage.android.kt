package com.nuvio.app.core.ui

import android.content.Context
import android.content.SharedPreferences

actual object AppFontSettingsStorage {
    private const val preferencesName = "nuvio_app_font_settings"
    private const val fontKey = "app_font"
    private const val useSystemFontScaleKey = "use_system_font_scale"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadFont(): String? = preferences?.getString(fontKey, null)

    actual fun saveFont(font: String) {
        preferences?.edit()?.putString(fontKey, font)?.apply()
    }

    actual fun loadUseSystemFontScale(): Boolean? =
        preferences?.takeIf { it.contains(useSystemFontScaleKey) }?.getBoolean(useSystemFontScaleKey, false)

    actual fun saveUseSystemFontScale(enabled: Boolean) {
        preferences?.edit()?.putBoolean(useSystemFontScaleKey, enabled)?.apply()
    }
}
