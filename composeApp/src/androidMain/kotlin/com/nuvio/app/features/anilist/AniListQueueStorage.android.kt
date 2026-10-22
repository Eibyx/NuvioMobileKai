package com.nuvio.app.features.anilist

import android.content.Context
import android.content.SharedPreferences

internal actual object PlatformAniListQueueStorage : AniListQueueStorage {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_anilist_queue", Context.MODE_PRIVATE)
    }

    actual override fun load(profileId: Int): String? = preferences?.getString("profile.$profileId", null)

    actual override fun save(profileId: Int, payload: String) {
        // commit(): a send queued just before the process dies must still be on disk.
        preferences?.edit()?.putString("profile.$profileId", payload)?.commit()
    }

    actual override fun lastProfile(): Int? =
        preferences?.getInt("last_profile", -1)?.takeIf { it > 0 }

    actual override fun saveLastProfile(profileId: Int) {
        preferences?.edit()?.putInt("last_profile", profileId)?.apply()
    }
}
