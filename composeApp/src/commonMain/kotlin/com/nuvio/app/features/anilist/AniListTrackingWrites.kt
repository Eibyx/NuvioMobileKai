/*
 * deliver() follows Aniyomi's TrackEpisode (https://github.com/aniyomiorg/aniyomi,
 * app/src/main/java/eu/kanade/domain/track/anime/interactor/TrackEpisode.kt) and the status rules
 * of its Anilist tracker (data/track/anilist/Anilist.kt).
 *
 * Copyright © 2015 Javier Tomás
 * Copyright © 2024 Mihon Open Source Project
 * Copyright © 2024 Aniyomi Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Nuvio: saved per-season links, queue outcomes instead of exceptions.
 */
package com.nuvio.app.features.anilist

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingListWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResolution
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AniListTrackingWrites internal constructor(
    private val sync: AniListSyncRepository,
    private val api: AniListApiClient,
    private val resolver: AniListIdResolver,
) : TrackingScrobbler, TrackingHistoryWriter, TrackingListWriter {
    override val providerId = TrackingProviderId.ANILIST
    private val writeLock = Mutex()

    /** Every watched episode goes through it; set by [AniListTracker]. */
    internal var queue: AniListSendQueue? = null

    /**
     * Whether a title AniList could not match is worth telling the user about: anime ids, the
     * Anime genre, Japanese animation. Everything else is skipped quietly. Set by [AniListTracker].
     */
    internal var looksLikeAnime: (TrackingMediaReference) -> Boolean = { true }

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        when (action) {
            // Starting to watch binds the title and season, so a missing match is known before the end.
            TrackingScrobbleAction.START -> ensureLinked(profileId, event.media)
            TrackingScrobbleAction.STOP -> if (event.progressPercent >= COMPLETION_THRESHOLD_PERCENT) {
                enqueueWatched(profileId, event.media)
            }
            else -> Unit
        }
    }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        scope(profileId)
        // A whole season marked at once: bind each title and season first, so every episode is
        // queued under its own AniList part (Part 1 gets E11, Part 2 gets E23) and the queue keeps
        // only the highest episode of each part, one update per entry.
        if (items.size > 1) {
            items.map { it.media }.distinctBy { it.toPendingSend().key }.forEach { media -> bindQuietly(media) }
        }
        items.forEach { item -> enqueueWatched(profileId, item.media) }
        // Delivery is asynchronous; failures and unknown titles surface in the AniList status.
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = 0)
    }

    /** Queues [media] as watched; the queue sends it right away when it can. */
    internal suspend fun enqueueWatched(profileId: Int, media: TrackingMediaReference) {
        val queue = queue ?: return
        if (!sync.isAuthenticatedFor(profileId)) return
        val pending = media.toPendingSend()
        // A split season queues each part on its own (see AniListPendingSend.partMediaId).
        val part = queue.linkFor(pending.key)
            ?.takeIf { !it.absolute && it.parts.size > 1 }
            ?.parts
            ?.lastOrNull { it.firstEpisode <= (pending.episode ?: 1) }
        queue.enqueue(pending.copy(partMediaId = part?.mediaId))
    }

    /**
     * Binds [media]'s title and season to AniList once, when playback starts. If nothing matches,
     * the user is told right away so they can link it by hand before the episode ends.
     */
    internal suspend fun ensureLinked(profileId: Int, media: TrackingMediaReference) {
        val queue = queue ?: return
        if (!sync.isAuthenticatedFor(profileId)) return
        val pending = media.toPendingSend()
        try {
            when (bindLink(pending.key, media)) {
                is LinkOutcome.Linked -> Unit
                LinkOutcome.Missing -> if (looksLikeAnime(media)) {
                    queue.reportLinkNeeded(pending.key, pending.title, "no AniList entry matches this title and season")
                } else {
                    InAppLogger.debug(TAG, "${pending.title} is not on AniList; not an anime, nothing to report")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Offline or AniList busy: the link is made when the episode is sent.
            InAppLogger.info(TAG, "Could not link ${pending.title} yet: ${error.toDelivery().reasonText()}")
        }
    }

    /** Binds [media]'s title and season if it can; failures are left for delivery to retry. */
    private suspend fun bindQuietly(media: TrackingMediaReference) {
        val pending = media.toPendingSend()
        try {
            bindLink(pending.key, media)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            InAppLogger.info(TAG, "Could not link ${pending.title} before queueing: ${error.toDelivery().reasonText()}")
        }
    }

    private sealed interface LinkOutcome {
        data class Linked(val link: AniListLink) : LinkOutcome
        data object Missing : LinkOutcome
    }

    /**
     * The saved link of [key], or a new one from the mapping. A saved automatic link is only
     * renewed when [media]'s episode lies past its last part (a new cour AniList added later).
     */
    private suspend fun bindLink(key: String, media: TrackingMediaReference): LinkOutcome {
        val queue = queue
        val saved = queue?.linkFor(key)
        val episode = media.episode?.number ?: 1
        if (saved != null && (saved.manual || saved.absolute || !saved.parts.endsBefore(episode))) {
            return LinkOutcome.Linked(saved)
        }
        val fresh = resolver.link(media, sync.currentSnapshot())
        if (fresh == null) return saved?.let { LinkOutcome.Linked(it) } ?: LinkOutcome.Missing
        if (fresh != saved?.copy(linkedAtMs = 0L)) queue?.setLink(fresh)
        return LinkOutcome.Linked(fresh)
    }

    /**
     * One attempt to send a queued episode, as Aniyomi's TrackEpisode does: read the entry from
     * AniList first, write only when the episode is past the saved progress, and never let a
     * closing screen cancel it halfway.
     */
    internal suspend fun deliver(profileId: Int, item: AniListPendingSend): AniListDelivery = withContext(NonCancellable) {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return@withContext AniListDelivery.Retry("profile changed")
        val reference = item.toReference()
        val target = try {
            when (val outcome = bindLink(item.key, reference)) {
                is LinkOutcome.Linked -> {
                    val link = outcome.link
                    val episode = item.episode ?: 1
                    // Past every part AniList had for this season (a part ARM does not know yet):
                    // never pile it onto the last part.
                    if (!link.absolute && reference.kind != TrackingMediaKind.MOVIE && link.parts.endsBefore(episode)) {
                        InAppLogger.info(TAG, "Not sending ${item.title} episode $episode: past the linked AniList parts")
                        return@withContext AniListDelivery.Skipped
                    }
                    resolver.targetFor(link, reference)
                }
                LinkOutcome.Missing -> return@withContext if (looksLikeAnime(reference)) {
                    AniListDelivery.NotFound("no AniList entry matches this title and season")
                } else {
                    AniListDelivery.Skipped
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withContext error.toDelivery()
        }
        try {
            writeLock.withLock {
                val (media, saved) = updateProgress(scope, target.mediaId, target.episode, onlyIfGreater = true, fresh = true)
                    ?: return@withLock AniListDelivery.NotFound("AniList has no entry ${target.mediaId}")
                if (saved == null) {
                    InAppLogger.info(TAG, "Nothing to send for ${media.displayTitle()} episode ${target.episode}: AniList already has it")
                    AniListDelivery.AlreadyUpToDate
                } else {
                    AniListDelivery.Sent(media.displayTitle(), saved.progress)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error.toDelivery()
        }
    }

    private fun AniListDelivery.reasonText(): String = when (this) {
        is AniListDelivery.Retry -> reason
        is AniListDelivery.NotFound -> reason
        else -> toString()
    }

    private fun Throwable.toDelivery(): AniListDelivery = when (this) {
        is AniListNotFoundException -> AniListDelivery.NotFound(message ?: "not found on AniList")
        is AniListApiException -> AniListDelivery.Retry(
            reason = if (status == 429) "AniList rate limit" else "AniList answered $status",
            retryAtMs = retryAtEpochMs,
        )
        // AniList tokens cannot be renewed: wait for the user to connect again, which retries at once.
        is AniListAuthException -> AniListDelivery.Retry(
            reason = AUTH_EXPIRED_REASON,
            retryAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds() + AUTH_RETRY_MS,
        )
        is AniListTransientException -> AniListDelivery.Retry(message ?: "network error")
        else -> AniListDelivery.Retry(InAppLogger.throwableSummary(this))
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        // Unmarking makes a later "watched" for these titles send again.
        items.forEach { media -> queue?.forgetDelivered(media.toPendingSend().key) }
        val targets = mutableMapOf<Long, Int>()
        var notFound = 0
        items.forEach { media ->
            val target = resolve(media)
            if (target == null) {
                notFound += 1
            } else {
                targets[target.mediaId] = minOf(targets[target.mediaId] ?: Int.MAX_VALUE, target.episode)
            }
        }
        writeLock.withLock {
            targets.forEach { (mediaId, episode) ->
                val (media, current) = currentEntry(scope, mediaId) ?: return@forEach
                current ?: return@forEach
                val progress = current.effectiveProgress()
                if (progress < episode) return@forEach
                val nextProgress = (episode - 1).coerceAtLeast(0)
                val nextStatus = when {
                    current.status == AniListListStatus.COMPLETED && nextProgress > 0 -> AniListListStatus.CURRENT
                    current.status == AniListListStatus.COMPLETED -> AniListListStatus.PLANNING
                    else -> current.status
                }
                val saved = api.saveEntry(scope, mediaId, status = nextStatus, progress = nextProgress)
                sync.applyEntry(scope, saved, media)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    override suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        val resolutions = mutableListOf<TrackingMutationResolution>()
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                setStatus(scope, target.mediaId, aniListStatusFor(destination))
                resolutions += TrackingMutationResolution(listStatus = destination, mediaKind = TrackingMediaKind.ANIME)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound, resolutions = resolutions)
    }

    override suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                deleteEntry(scope, target.mediaId)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    internal suspend fun setStatus(scope: AniListAuthScope, mediaId: Long, status: String) {
        val (media, current) = currentEntry(scope, mediaId) ?: throw AniListNotFoundException("AniList media not found")
        val total = media.totalEpisodeCount()
        val progress = when {
            status == AniListListStatus.COMPLETED && total > 0 -> total
            else -> null
        }
        if (current?.status == status && progress == null) return
        val saved = api.saveEntry(scope, mediaId, status = status, progress = progress)
        sync.applyEntry(scope, saved, media)
    }

    internal suspend fun deleteEntry(scope: AniListAuthScope, mediaId: Long) {
        val (_, current) = currentEntry(scope, mediaId) ?: return
        current ?: return
        api.deleteEntry(scope, current.id)
        sync.removeEntry(scope, mediaId)
    }

    internal suspend fun setScore(scope: AniListAuthScope, mediaId: Long, scoreRaw: Int) {
        writeLock.withLock {
            val (media, current) = currentEntry(scope, mediaId) ?: throw AniListNotFoundException("AniList media not found")
            if (current == null && scoreRaw <= 0) return
            val saved = api.saveEntry(
                scope,
                mediaId,
                status = if (current == null) AniListListStatus.COMPLETED else null,
                progress = if (current == null && media.totalEpisodeCount() > 0) media.totalEpisodeCount() else null,
                scoreRaw = scoreRaw,
            )
            sync.applyEntry(scope, saved, media)
        }
    }

    internal suspend fun resolve(media: TrackingMediaReference): AniListTarget? = try {
        resolver.resolve(media, sync.currentSnapshot())
    } catch (error: CancellationException) {
        throw error
    } catch (error: AniListAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Saves [episode] as watched under the rules of [aniListProgressUpdate]. Returns null when AniList
     * has no such media, and a null entry when nothing needed saving.
     */
    private suspend fun updateProgress(
        scope: AniListAuthScope,
        mediaId: Long,
        episode: Int,
        onlyIfGreater: Boolean,
        fresh: Boolean = false,
    ): Pair<AniListMedia, AniListEntry?>? {
        val (media, current) = (if (fresh) api.mediaById(scope, mediaId) else currentEntry(scope, mediaId)) ?: return null
        val update = aniListProgressUpdate(
            current = current?.takeIf { onlyIfGreater },
            episode = episode,
            totalEpisodes = media.totalEpisodeCount(),
        ) ?: return media to null
        InAppLogger.info(
            TAG,
            "Saving ${media.displayTitle()} progress ${update.progress} status ${update.status} " +
                "(was ${current?.progress ?: 0} ${current?.status ?: "no entry"})",
        )
        val saved = api.saveEntry(scope, mediaId, status = update.status, progress = update.progress)
        sync.applyEntry(scope, saved, media)
        return media to saved
    }

    private suspend fun currentEntry(scope: AniListAuthScope, mediaId: Long): Pair<AniListMedia, AniListEntry?>? {
        val snapshot = sync.currentSnapshot()
        snapshot?.entries?.firstOrNull { it.mediaId == mediaId }?.let { entry ->
            entry.media?.let { return it to entry }
        }
        return api.mediaById(scope, mediaId)
    }

    private fun scope(profileId: Int): AniListAuthScope = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("AniList profile changed")
    }

    companion object {
        const val COMPLETION_THRESHOLD_PERCENT = 80.0
        private const val TAG = "Sync/AniList"
        internal const val AUTH_EXPIRED_REASON = "AniList sign-in expired"
        private const val AUTH_RETRY_MS = 6L * 60L * 60L * 1_000L
    }
}

internal fun TrackingMediaReference.toPendingSend(): AniListPendingSend {
    val contentType = catalog?.contentType
        ?: if (kind == TrackingMediaKind.MOVIE) "movie" else "series"
    val contentId = catalog?.contentId ?: ids.imdb ?: stableKey
    val season = episode?.season
    return AniListPendingSend(
        key = aniListSendKey(contentId, contentType, season),
        title = title?.takeIf(String::isNotBlank) ?: contentId,
        contentType = contentType,
        contentId = contentId,
        videoId = catalog?.videoId,
        season = season,
        episode = episode?.number,
    )
}

internal fun AniListPendingSend.toReference(): TrackingMediaReference = buildTrackingMediaReference(
    contentType = contentType,
    parentMetaId = contentId,
    videoId = videoId,
    title = title,
    seasonNumber = season,
    episodeNumber = episode,
)

