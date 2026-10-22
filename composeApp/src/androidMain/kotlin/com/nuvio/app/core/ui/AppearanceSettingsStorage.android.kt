package com.nuvio.app.core.ui

import android.content.Context
import android.content.SharedPreferences

actual object AppearanceSettingsStorage {
    private const val preferencesName = "nuvio_appearance_settings"
    private const val modeKey = "appearance_mode"
    private const val windowBackgroundKey = "window_background"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadMode(): String? = preferences?.getString(modeKey, null)

    actual fun saveMode(mode: String) {
        preferences?.edit()?.putString(modeKey, mode)?.apply()
    }

    /** The window colour last drawn, so the next launch starts on it before Compose draws. */
    fun loadWindowBackground(): Int? =
        preferences?.takeIf { it.contains(windowBackgroundKey) }?.getInt(windowBackgroundKey, 0)

    fun saveWindowBackground(color: Int) {
        val preferences = preferences ?: return
        if (preferences.contains(windowBackgroundKey) && preferences.getInt(windowBackgroundKey, 0) == color) return
        preferences.edit().putInt(windowBackgroundKey, color).apply()
    }
}
