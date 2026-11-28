package com.nuvio.app.features.artwork

import platform.Foundation.NSUserDefaults

internal actual object ArtworkChoiceStorage {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual fun loadChoices(): String? = defaults.stringForKey("nuvio_artwork_choices")

    actual fun saveChoices(value: String) {
        defaults.setObject(value, forKey = "nuvio_artwork_choices")
    }

    actual fun loadFanartKey(): String? = defaults.stringForKey("nuvio_artwork_fanart_key")

    actual fun saveFanartKey(value: String?) {
        if (value == null) defaults.removeObjectForKey("nuvio_artwork_fanart_key") else defaults.setObject(value, forKey = "nuvio_artwork_fanart_key")
    }
}
