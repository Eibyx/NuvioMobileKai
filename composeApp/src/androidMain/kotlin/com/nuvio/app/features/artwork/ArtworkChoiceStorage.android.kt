package com.nuvio.app.features.artwork

import android.content.Context
import android.content.SharedPreferences

internal actual object ArtworkChoiceStorage {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("nuvio_artwork_choices", Context.MODE_PRIVATE)
    }

    actual fun loadChoices(): String? = preferences?.getString("choices", null)

    actual fun saveChoices(value: String) {
        preferences?.edit()?.putString("choices", value)?.apply()
    }

    actual fun loadFanartKey(): String? = preferences?.getString("fanart_key", null)

    actual fun saveFanartKey(value: String?) {
        preferences?.edit()?.apply { if (value == null) remove("fanart_key") else putString("fanart_key", value) }?.apply()
    }
}
