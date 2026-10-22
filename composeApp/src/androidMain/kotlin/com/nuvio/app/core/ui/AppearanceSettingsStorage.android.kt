package com.nuvio.app.core.ui

import android.content.Context
import android.content.SharedPreferences

actual object AppearanceSettingsStorage {
    private const val preferencesName = "nuvio_appearance_settings"
    private const val modeKey = "appearance_mode"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadMode(): String? = preferences?.getString(modeKey, null)

    actual fun saveMode(mode: String) {
        preferences?.edit()?.putString(modeKey, mode)?.apply()
    }
}
