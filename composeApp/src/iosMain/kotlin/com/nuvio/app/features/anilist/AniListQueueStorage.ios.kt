package com.nuvio.app.features.anilist

import platform.Foundation.NSUserDefaults

internal actual object PlatformAniListQueueStorage : AniListQueueStorage {
    private val preferences = NSUserDefaults(suiteName = "nuvio_anilist_queue")

    actual override fun load(profileId: Int): String? = preferences.stringForKey("profile.$profileId")

    actual override fun save(profileId: Int, payload: String) {
        preferences.setObject(payload, forKey = "profile.$profileId")
    }

    actual override fun lastProfile(): Int? =
        preferences.integerForKey("last_profile").toInt().takeIf { it > 0 }

    actual override fun saveLastProfile(profileId: Int) {
        preferences.setInteger(profileId.toLong(), forKey = "last_profile")
    }
}

/** iOS has no background job for this; the queue runs when the app opens or the network returns. */
internal actual object PlatformAniListSyncScheduler {
    actual fun schedule(delayMs: Long) = Unit
}
