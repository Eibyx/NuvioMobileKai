package com.nuvio.app.features.details

import platform.Foundation.NSUserDefaults

internal actual object FavoritePeopleStorage {
    private fun payloadKey(profileId: Int) = "favorite_people_payload_$profileId"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey(profileId))
    }
}
