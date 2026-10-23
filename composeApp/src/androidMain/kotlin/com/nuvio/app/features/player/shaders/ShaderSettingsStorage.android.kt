package com.nuvio.app.features.player.shaders

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ShaderSettingsStorage {
    private const val PREFERENCES = "nuvio_shader_settings"
    private const val PAYLOAD = "shader_settings_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? = preferences?.getString(ProfileScopedKey.of(PAYLOAD), null)

    actual fun savePayload(payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(PAYLOAD), payload)?.apply()
    }
}
