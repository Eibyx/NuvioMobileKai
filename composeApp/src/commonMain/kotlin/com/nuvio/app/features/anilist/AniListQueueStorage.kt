package com.nuvio.app.features.anilist

/** Keeps each profile's pending AniList sends, so they survive a closed app, a crash or no network. */
internal interface AniListQueueStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)

    /** The profile whose queue was opened last, for the background job of a freshly started app. */
    fun lastProfile(): Int? = null
    fun saveLastProfile(profileId: Int) = Unit
}

internal expect object PlatformAniListQueueStorage : AniListQueueStorage {
    override fun load(profileId: Int): String?
    override fun save(profileId: Int, payload: String)
    override fun lastProfile(): Int?
    override fun saveLastProfile(profileId: Int)
}

/** Runs the AniList queue again once the network is up, even after the app is closed. */
internal expect object PlatformAniListSyncScheduler {
    fun schedule(delayMs: Long)
}
