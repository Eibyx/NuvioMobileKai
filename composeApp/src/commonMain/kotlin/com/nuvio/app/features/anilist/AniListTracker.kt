package com.nuvio.app.features.anilist

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.parseAnimeIdRef
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anilist_link_saved
import nuvio.composeapp.generated.resources.anilist_sync_toast_link_needed
import nuvio.composeapp.generated.resources.anilist_sync_toast_not_found
import nuvio.composeapp.generated.resources.anilist_sync_toast_synced
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch

object AniListTracker : TrackingAuthProvider {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)
    internal val store = AniListAuthStore(PlatformAniListAuthPersistence, activeProfile.value)
    internal val api = AniListApiClient(store)
    private val resolver = AniListIdResolver(api)
    val sync = AniListSyncRepository(PlatformAniListSyncStorage, store, api, resolver, activeProfile, coroutineScope)
    val writes = AniListTrackingWrites(sync, api, resolver)
    val progressProvider = AniListTrackingProgressProvider(sync, store, activeProfile, coroutineScope, ::ensureLoaded)
    val watchedProvider = AniListWatchedSyncAdapter(sync, writes, store, activeProfile)
    val libraryProvider = AniListTrackingLibraryProvider(sync, writes, ::ensureLoaded)
    internal val ratingProvider = AniListRatingsProvider(sync, writes)
    val authState: StateFlow<AniListAuthState> = store.state
    private val authenticated = MutableStateFlow(store.state.value.isAuthenticated)
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long get() = store.scope().generation
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.ANILIST,
        "AniList",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.RATINGS,
        ),
    )

    /** Watched episodes on their way to AniList; see [AniListSendQueue]. */
    internal val queue = AniListSendQueue(
        storage = PlatformAniListQueueStorage,
        deliver = { profileId, item -> writes.deliver(profileId, item) },
        scope = coroutineScope,
        background = { delayMs -> PlatformAniListSyncScheduler.schedule(delayMs) },
    )

    /** AniList → Nuvio for anime; see [AniListWatchedImporter]. */
    internal val importer = AniListWatchedImporter(sync, api, queue, resolver, coroutineScope)

    init {
        writes.queue = queue
        writes.looksLikeAnime = ::looksLikeAnime
        resolver.storedLink = { reference -> queue.linkFor(reference.toPendingSend().key) }
        coroutineScope.launch {
            store.state.collectLatest { state ->
                authenticated.value = state.scope.profileId == activeProfile.value && state.isAuthenticated
                if (state.isAuthenticated && state.scope.profileId == activeProfile.value) {
                    // Opening the app (or switching profile) sends whatever is still pending.
                    queue.open(state.scope.profileId, process = false)
                    queue.retryNow("App opened or AniList connected")
                }
            }
        }
        importer.start()
        coroutineScope.launch {
            importer.keys.collect { keys ->
                WatchedRepository.setImportedWatchedKeys(TrackingProviderId.ANILIST, keys)
            }
        }
        coroutineScope.launch {
            NetworkStatusRepository.uiState
                .map { it.condition }
                .distinctUntilChanged()
                .collect { condition ->
                    if (condition == NetworkCondition.Online && queue.state.value.pending.isNotEmpty()) {
                        queue.retryNow("Back online")
                    }
                }
        }
        coroutineScope.launch {
            queue.events.collect { event ->
                when (event) {
                    is AniListSyncEvent.Synced -> if (queue.state.value.notifyOnSync) {
                        NuvioToastController.show(getString(Res.string.anilist_sync_toast_synced, event.episode))
                    }
                    is AniListSyncEvent.NotFound ->
                        NuvioToastController.show(getString(Res.string.anilist_sync_toast_not_found, event.title))
                    is AniListSyncEvent.LinkNeeded ->
                        NuvioToastController.show(getString(Res.string.anilist_sync_toast_link_needed, event.title))
                }
            }
        }
    }

    /**
     * The single trigger: an episode just counted as watched (passing the threshold while playing,
     * moving on to the next episode, or marked by hand) goes to AniList now.
     */
    fun onEpisodeWatched(profileId: Int, entry: WatchProgressEntry) {
        if (!store.state.value.isAuthenticated) return
        coroutineScope.launch {
            writes.enqueueWatched(
                profileId,
                buildTrackingMediaReference(
                    contentType = entry.parentMetaType,
                    parentMetaId = entry.parentMetaId,
                    videoId = entry.videoId,
                    title = entry.title,
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber,
                ),
            )
        }
    }

    /** For "Link with AniList": anime matching [query], best match first. */
    suspend fun searchAnime(query: String): List<AniListMedia> = api.searchAnime(query)

    /** Opening an anime's page: its AniList entries are read again and imported; see [AniListWatchedImporter.refreshTitle]. */
    fun refreshTitle(meta: com.nuvio.app.features.details.MetaDetails) {
        if (!store.state.value.isAuthenticated) return
        coroutineScope.launch {
            try {
                importer.refreshTitle(meta)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                InAppLogger.info("Sync/AniList", "AniList refresh of ${meta.name} failed: ${InAppLogger.throwableSummary(error)}")
            }
        }
    }

    /** "Sync now" in the AniList sheet. */
    fun syncTitle(meta: com.nuvio.app.features.details.MetaDetails) {
        if (!store.state.value.isAuthenticated) return
        coroutineScope.launch {
            try {
                importer.syncTitle(meta)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                InAppLogger.info("Sync/AniList", "AniList sync of ${meta.name} failed: ${InAppLogger.throwableSummary(error)}")
            }
        }
    }

    fun linkKey(contentId: String, contentType: String, season: Int?): String =
        aniListSendKey(contentId, contentType, season)

    internal fun linkFor(key: String): AniListLink? = queue.linkFor(key)

    /** The AniList entry the list sync already maps [contentId] to, if the user's list has it. */
    fun knownMediaTitle(contentId: String): String? {
        val projection = sync.currentProjection()
        val mediaId = projection.mediaIdFor(contentId) ?: return null
        return projection.entryByMediaId[mediaId]?.media?.displayTitle()
    }

    /**
     * Anime ids, the Anime genre or Japanese animation. Only these get a "not found on AniList"
     * warning; a regular series that AniList does not list is skipped quietly.
     */
    internal fun looksLikeAnime(reference: TrackingMediaReference): Boolean {
        val pending = reference.toPendingSend()
        if (parseAnimeIdRef(pending.contentId) != null || reference.ids.mal != null || reference.ids.anilist != null) return true
        if (knownMediaTitle(pending.contentId) != null) return true
        val meta = MetaDetailsRepository.peek(pending.contentType, pending.contentId) ?: return false
        if (meta.genres.any { it.equals("Anime", ignoreCase = true) }) return true
        val japanese = listOfNotNull(meta.country, meta.language).any {
            it.contains("Japan", ignoreCase = true) || it.equals("JP", ignoreCase = true) || it.equals("ja", ignoreCase = true)
        }
        return japanese && meta.genres.any { it.equals("Animation", ignoreCase = true) }
    }

    /**
     * "Link with AniList": [media] becomes the entry of [key]'s title and season and wins over the
     * mapping. When it is one of the season's automatic parts it keeps that part's episode offset.
     */
    fun link(key: String, media: AniListMedia) {
        coroutineScope.launch {
            val automatic = queue.linkFor(key)?.parts?.firstOrNull { it.mediaId == media.id }
            queue.setLink(
                AniListLink(
                    key = key,
                    parts = listOf(
                        AniListLinkPart(
                            mediaId = media.id,
                            title = media.displayTitle(),
                            firstEpisode = automatic?.firstEpisode ?: 1,
                            episodes = media.episodes,
                        ),
                    ),
                    manual = true,
                ),
            )
            NuvioToastController.show(getString(Res.string.anilist_link_saved, media.displayTitle()))
        }
    }

    fun unlink(key: String) {
        coroutineScope.launch { queue.removeLink(key) }
    }

    /**
     * Sends what is pending from the background job (the app may have been closed). Returns true
     * while something is still waiting, so the job retries later.
     */
    internal suspend fun sendPendingInBackground(profileHint: Int?): Boolean {
        // Started by the system without the app's UI: profiles are not loaded yet, so use the
        // profile whose queue was opened last.
        if (profileHint != null && ProfileRepository.state.value.profiles.isEmpty() && activeProfile.value != profileHint) {
            activeProfile.value = profileHint
            store.selectProfile(profileHint)
        } else {
            ensureLoaded()
        }
        val state = store.state.value
        if (!state.isAuthenticated) return false
        queue.open(state.scope.profileId, process = false)
        queue.process()
        return queue.state.value.pending.isNotEmpty()
    }

    fun retryPendingNow() {
        coroutineScope.launch { queue.retryNow("Retry now pressed") }
    }

    fun setNotifyOnSync(enabled: Boolean) {
        coroutineScope.launch { queue.setNotifyOnSync(enabled) }
    }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerHistoryWriter(writes)
        TrackingProviderRegistry.registerListWriter(writes)
        TrackingProviderRegistry.registerScrobbler(writes)
        TrackingProviderRegistry.registerProgressProvider(progressProvider)
        TrackingProviderRegistry.registerWatchedProvider(watchedProvider)
        TrackingProviderRegistry.registerLibraryProvider(libraryProvider)
        TrackingProviderRegistry.registerRatingProvider(ratingProvider)
    }

    fun hasRequiredCredentials(): Boolean = AniListConfig.isConfigured

    fun connect(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) return null
        if (store.state.value.isAuthenticated) return null
        store.beginAuthorization(store.scope())
        return authorizationUrl()
    }

    fun pendingAuthorizationUrl(): String? =
        if (hasRequiredCredentials() && store.state.value.awaitingApproval) authorizationUrl() else connect()

    fun cancelAuthorization() {
        store.cancelAuthorization(store.scope())
    }

    fun disconnect() {
        store.clearAuth(store.scope())
        authenticated.value = false
    }

    fun syncNow() {
        coroutineScope.launch {
            try {
                sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun handleAuthCallback(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith(AniListConfig.REDIRECT_URI, ignoreCase = true)) return false
        val remainder = trimmed.substring(AniListConfig.REDIRECT_URI.length)
        if (remainder.isNotEmpty() && remainder.first() !in setOf('#', '?', '/')) return false
        ensureLoaded()
        val scope = store.scope()
        if (!store.isPending()) return true
        val parameters = parseCallbackParameters(remainder)
        if (parameters["error"] != null) {
            store.cancelAuthorization(scope, AniListAuthError.ACCESS_DENIED)
            return true
        }
        val token = parameters["access_token"]?.takeIf(String::isNotBlank)
        if (token == null) {
            store.cancelAuthorization(scope, AniListAuthError.INVALID_CALLBACK)
            return true
        }
        val expiresIn = parameters["expires_in"]?.toLongOrNull()?.takeIf { it > 0L }
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val expiresAt = expiresIn?.let { nowMs + it * 1_000L }
        if (!store.authorize(token, expiresAt, scope)) return true
        val authorizedScope = store.scope()
        coroutineScope.launch {
            store.setBusy(true, authorizedScope)
            try {
                val viewer = api.viewer(authorizedScope)
                store.saveViewer(viewer, authorizedScope)
                sync.refresh(TrackingRefreshIntent.INVALIDATED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                store.setBusy(false, store.scope())
            }
        }
        return true
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        store.refreshExpiry()
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun onProfileChanged() {
        activeProfile.value = ProfileRepository.activeProfileId
        store.selectProfile(activeProfile.value)
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun clearLocalState() {
        store.clearAllProfiles()
        PlatformAniListSyncStorage.clearAll()
        authenticated.value = false
    }

    override fun removeStoredProfile(profileId: Int) {
        store.removeProfile(profileId)
        val scope = store.scope()
        coroutineScope.launch {
            try {
                PlatformAniListSyncStorage.remove(profileId) { store.checkScope(scope) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private fun authorizationUrl(): String =
        "${AniListConfig.AUTHORIZE_URL}?client_id=${AniListConfig.CLIENT_ID.encodeURLParameter()}&response_type=token"

    private fun parseCallbackParameters(remainder: String): Map<String, String> {
        val parts = buildList {
            remainder.substringAfter('?', "").substringBefore('#').takeIf(String::isNotBlank)?.let(::add)
            remainder.substringAfter('#', "").takeIf(String::isNotBlank)?.let(::add)
        }
        return parts.flatMap { it.split('&') }
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                if (key.isBlank()) return@mapNotNull null
                val value = pair.substringAfter('=', "")
                key to runCatching { value.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(value)
            }
            .toMap()
    }
}
