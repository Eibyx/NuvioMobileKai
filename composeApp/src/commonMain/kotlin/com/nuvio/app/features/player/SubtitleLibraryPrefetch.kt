package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.subtitlelibrary.LibraryQuery
import com.nuvio.app.features.subtitlelibrary.LibrarySearchResult
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryPlatform
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryStore
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Looks for the episode in the user's subtitle folders while its streams are being fetched, so the
 * subtitle and its fonts are in the cache when the player opens. One episode at a time: opening
 * another one cancels the previous search. Only started from the streams screen, never on its own.
 */
internal object SubtitleLibraryPrefetch {
    private const val TAG = "Subtitles/Folders"
    private const val VALID_MS = 10 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Pending(val videoId: String, val startedAtMs: Long, val work: Deferred<Pair<LibraryQuery, LibrarySearchResult>?>)

    private var pending: Pending? = null

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun start(
        type: String,
        videoId: String,
        parentMetaId: String,
        title: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
    ) {
        if (!SubtitleLibraryPlatform.isSupported || type == "live" || videoId.isBlank()) return
        if (SubtitleLibraryStore.current().enabledFolders.isEmpty()) return
        val current = pending
        if (current != null && current.videoId == videoId && nowMs() - current.startedAtMs < VALID_MS) return
        current?.work?.cancel()
        val preferredLanguage = PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage
        pending = Pending(
            videoId = videoId,
            startedAtMs = nowMs(),
            work = scope.async {
                try {
                    val query = buildLibraryQuery(type, videoId, parentMetaId, title, season, episode, isMovie, preferredLanguage)
                    query to SubtitleLibraryPlatform.search(query)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    InAppLogger.warn(TAG, "prefetch failed: ${error::class.simpleName}")
                    null
                }
            },
        )
        InAppLogger.info(TAG, "prefetch started while the streams load")
    }

    /** The prefetched result for [videoId] (waiting for it if still running); null when there is none. */
    suspend fun take(videoId: String): Pair<LibraryQuery, LibrarySearchResult>? {
        val current = pending ?: return null
        if (current.videoId != videoId || nowMs() - current.startedAtMs > VALID_MS) return null
        return try {
            current.work.await()
        } catch (error: CancellationException) {
            if (current.work.isCancelled) null else throw error
        }
    }
}
