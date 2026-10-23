package com.nuvio.app.features.player.shaders

/** Shaders are Android-only: nothing to store on iOS. */
internal actual object ShaderSettingsStorage {
    actual fun loadPayload(): String? = null

    actual fun savePayload(payload: String) = Unit
}
