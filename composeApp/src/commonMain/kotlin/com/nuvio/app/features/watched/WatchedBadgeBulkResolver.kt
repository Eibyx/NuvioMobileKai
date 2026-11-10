package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.toSimklShowIdSiblings
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val BADGE_RESOLUTION_CONCURRENCY = 2
private const val AMBIGUOUS_MARKER = "__ambiguous__"

private val log = Logger.withTag("WatchedBadgeBulk")

/**
 * What each series' badge was last resolved from, so a pass only fetches the series whose
 * own watched episodes, completed progress or date changed. Without it every watched-state
 * change (a tracker sync, coming back to the app, this pass publishing its own result)
 * fetched the meta of every watched series again from the addons.
 */
internal object WatchedBadgeResolutionMemory {
    private const val FAILED_RETRY_MS = 10 * 60 * 1000L

    private var scopeKey: String? = null
    private val resolved = mutableMapOf<String, ResolvedBadge>()
    private val failedUntil = mutableMapOf<String, Long>()

    data class ResolvedBadge(val fingerprint: Int, val metaId: String)

    fun enterScope(key: String) {
        if (scopeKey == key) return
        scopeKey = key
        resolved.clear()
        failedUntil.clear()
    }

    fun resolved(contentId: String): ResolvedBadge? = resolved[contentId]

    fun isBackingOff(contentId: String, nowMs: Long): Boolean = (failedUntil[contentId] ?: 0L) > nowMs

    fun recordResolved(contentId: String, badge: ResolvedBadge) {
        resolved[contentId] = badge
        failedUntil.remove(contentId)
    }

    fun recordFailed(contentId: String, nowMs: Long) {
        failedUntil[contentId] = nowMs + FAILED_RETRY_MS
    }

    fun clear() {
        scopeKey = null
        resolved.clear()
        failedUntil.clear()
    }
}

/** The inputs a series' badge depends on: its watched episodes, completed progress and the date. */
internal fun watchedBadgeFingerprint(
    ids: Set<String>,
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    todayIsoDate: String,
): Int {
    val watched = watchedItems
        .filter { it.id in ids }
        .map { "${it.id}:${it.season}:${it.episode}" }
        .sorted()
    val completed = progressEntries
        .filter { it.parentMetaId in ids && it.isEffectivelyCompleted }
        .map { it.videoId }
        .sorted()
    return listOf(watched, completed, todayIsoDate).hashCode()
}

suspend fun resolveWatchedBadgesBulk(
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
    scopeKey: String = "",
    nowMs: () -> Long = { WatchedClock.nowEpochMs() },
    fetchMeta: suspend (String) -> MetaDetails? = { contentId ->
        MetaDetailsRepository.fetch(type = "series", id = contentId, cacheResult = false)
    },
): Boolean = withContext(Dispatchers.Default) {
    WatchedBadgeResolutionMemory.enterScope(scopeKey)
    val touchedSeriesIds = buildSet {
        watchedItems.forEach { item ->
            if (item.type.isSeriesLikeWatchedType() && item.season != null && item.episode != null) {
                add(item.id)
            }
        }
        progressEntries.forEach { entry ->
            if (entry.parentMetaType.isSeriesLikeWatchedType() && entry.isEpisode && entry.isEffectivelyCompleted) {
                add(entry.parentMetaId)
            }
        }
        WatchedRepository.baseFullyWatchedSeriesKeys().mapNotNullTo(this, ::extractContentIdFromWatchedKey)
    }
    if (touchedSeriesIds.isEmpty()) return@withContext true

    // Use the full watchedKeys from UI state which includes extra keys from
    // provider alternate IDs (e.g. Simkl anime alternate MAL/Kitsu keys).
    val watchedKeys = WatchedRepository.uiState.value.watchedKeys

    val pendingIds = touchedSeriesIds.filter { contentId ->
        val previous = WatchedBadgeResolutionMemory.resolved(contentId)
        val unchanged = previous != null && previous.fingerprint == watchedBadgeFingerprint(
            ids = setOf(contentId, previous.metaId),
            watchedItems = watchedItems,
            progressEntries = progressEntries,
            todayIsoDate = todayIsoDate,
        )
        !unchanged && !WatchedBadgeResolutionMemory.isBackingOff(contentId, nowMs())
    }
    if (pendingIds.isEmpty()) {
        expandFullyWatchedWithSiblings()
        return@withContext true
    }

    log.i { "Bulk badge resolution starting: ${pendingIds.size}/${touchedSeriesIds.size} series changed" }

    val semaphore = Semaphore(BADGE_RESOLUTION_CONCURRENCY)
    val resolvedStates = linkedMapOf<String, Boolean>()
    val resolvedBadges = linkedMapOf<String, WatchedBadgeResolutionMemory.ResolvedBadge>()
    var failures = 0

    try {
        for (contentId in pendingIds) {
            semaphore.withPermit {
                val meta = try {
                    fetchMeta(contentId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (meta == null) {
                    failures++
                    WatchedBadgeResolutionMemory.recordFailed(contentId, nowMs())
                } else {
                    val isFullyWatched = WatchedRepository.calculateFullyWatchedSeriesState(
                        meta = meta,
                        todayIsoDate = todayIsoDate,
                        isEpisodeWatched = { episode ->
                            val keys = watchedItemKeys(meta.type, meta.id, episode.season, episode.episode)
                            if (keys.any(watchedKeys::contains)) {
                                true
                            } else {
                                val episodeNumber = episode.episode
                                if (episodeNumber != null) {
                                    com.nuvio.app.features.simkl.SimklAnimeWatchedFallback.isWatched(episode.id, episodeNumber)
                                } else {
                                    false
                                }
                            }
                        },
                        isEpisodeCompleted = { episode ->
                            val playbackId = meta.episodePlaybackId(episode)
                            progressEntries.any { entry ->
                                entry.videoId == playbackId && entry.isEffectivelyCompleted
                            }
                        },
                    )
                    resolvedStates[watchedItemKey(meta.type, meta.id)] = isFullyWatched
                    resolvedBadges[contentId] = WatchedBadgeResolutionMemory.ResolvedBadge(
                        fingerprint = watchedBadgeFingerprint(
                            ids = setOf(contentId, meta.id),
                            watchedItems = watchedItems,
                            progressEntries = progressEntries,
                            todayIsoDate = todayIsoDate,
                        ),
                        metaId = meta.id,
                    )
                }
            }
            yield()
        }
    } finally {
        // Keep what was resolved even when the pass is cancelled (a new watched state
        // restarts it), so the next pass does not fetch those series again.
        WatchedRepository.updateFullyWatchedSeriesStates(resolvedStates)
        resolvedBadges.forEach { (contentId, badge) -> WatchedBadgeResolutionMemory.recordResolved(contentId, badge) }
    }
    log.i { "Bulk badge resolution complete: resolved ${resolvedBadges.size}/${pendingIds.size}, failed $failures" }
    InAppLogger.debug(
        "Watched/Badges",
        "checked ${pendingIds.size}/${touchedSeriesIds.size} changed series, resolved ${resolvedBadges.size}, failed $failures",
    )

    // Sibling expansion
    expandFullyWatchedWithSiblings()
    // Failed series are retried after a pause, not on every watched-state change.
    true
}

fun expandFullyWatchedWithSiblings() {
    val siblingMap = getActiveProviderSiblingMap()
    if (siblingMap.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Use base keys (without previous sibling expansion) to avoid feedback loops
    val baseKeys = WatchedRepository.baseFullyWatchedSeriesKeys()
    if (baseKeys.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Compute only the sibling-derived keys (not including base keys themselves)
    val siblingKeys = buildSet {
        for (key in baseKeys) {
            val contentId = extractContentIdFromWatchedKey(key) ?: continue
            val siblings = siblingMap[contentId] ?: continue
            siblings.forEach { siblingId ->
                if (siblingId != contentId && !siblingId.startsWith(AMBIGUOUS_MARKER)) {
                    val siblingKey = rebuildWatchedKeyWithSiblingId(key, siblingId)
                    if (siblingKey != null) add(siblingKey)
                }
            }
        }
    }

    if (siblingKeys != WatchedRepository.currentExpandedSiblingKeys()) {
        log.i { "Sibling expansion: ${baseKeys.size} base keys -> +${siblingKeys.size} sibling keys" }
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(siblingKeys)
    }
}

private fun getActiveProviderSiblingMap(): Map<String, Set<String>> {
    val source = TrackingSettingsRepository.uiState.value.watchProgressSource
    val effectiveSource = effectiveWatchProgressSource(
        requestedSource = source,
        isProviderAuthenticated = { providerId ->
            com.nuvio.app.features.tracking.TrackingProviderRegistry.isAuthenticated(providerId)
        },
    )
    return effectiveSource.providerId?.let {
        com.nuvio.app.features.tracking.TrackingProviderRegistry.progressProvider(it)?.showIdSiblings()
    }.orEmpty()
}

private fun extractContentIdFromWatchedKey(key: String): String? {
    // Format: "type:contentId:season:episode"
    // Split from the end to handle contentIds with colons (like "tmdb:123")
    val parts = key.split(':')
    if (parts.size < 4) return null
    // Last two parts are season and episode (-1:-1)
    // First part is type, everything in between is contentId
    val type = parts.first()
    val season = parts[parts.size - 2]
    val episode = parts.last()
    if (season.toIntOrNull() == null || episode.toIntOrNull() == null) return null
    val contentId = parts.subList(1, parts.size - 2).joinToString(":")
    return contentId.takeIf { it.isNotBlank() }
}

private fun rebuildWatchedKeyWithSiblingId(originalKey: String, siblingId: String): String? {
    val parts = originalKey.split(':')
    if (parts.size < 4) return null
    val type = parts.first()
    return watchedItemKey(type = type, id = siblingId)
}

private fun String.isSeriesLikeWatchedType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")
