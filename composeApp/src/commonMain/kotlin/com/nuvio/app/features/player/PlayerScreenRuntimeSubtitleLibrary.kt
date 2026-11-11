package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.cloudstorage.CloudStreams
import com.nuvio.app.features.subtitlelibrary.LibraryEntry
import com.nuvio.app.features.subtitlelibrary.LibraryQuery
import com.nuvio.app.features.subtitlelibrary.LibraryRedaction
import com.nuvio.app.features.subtitlelibrary.LibrarySearchResult
import com.nuvio.app.features.subtitlelibrary.PreparedLibrarySubtitle
import com.nuvio.app.features.subtitlelibrary.SubtitleFolder
import com.nuvio.app.features.subtitlelibrary.SubtitleFolderLink
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryPlatform
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryStore
import com.nuvio.app.features.subtitlelibrary.SubtitleOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.subtitle_folders_engine_no_fonts
import nuvio.composeapp.generated.resources.subtitle_folders_failed
import nuvio.composeapp.generated.resources.subtitle_folders_fonts
import nuvio.composeapp.generated.resources.subtitle_folders_linked
import nuvio.composeapp.generated.resources.subtitle_folders_origin_drive
import nuvio.composeapp.generated.resources.subtitle_folders_origin_storage
import nuvio.composeapp.generated.resources.subtitle_folders_origin_terabox
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

private const val TAG = "Subtitles/Folders"
internal const val LIBRARY_SUBTITLE_ID_PREFIX = "kai-lib:"

/** A subtitle found in the user's subtitle folders (a cached `file://` copy). */
internal val AddonSubtitle.isLibrarySubtitle: Boolean
    get() = id.startsWith(LIBRARY_SUBTITLE_ID_PREFIX)

/** The folder subtitles of the episode playing now; another episode starts without them. */
internal val PlayerScreenRuntime.currentLibrarySubtitles: List<AddonSubtitle>
    get() = if (librarySubtitlesVideoId == activeVideoId) librarySubtitles else emptyList()

/** The in-player folder browser ("Buscar en mis carpetas…" / "Vincular carpeta…"). */
internal data class SubtitleLibraryBrowserState(
    val linking: Boolean,
    val root: SubtitleFolder? = null,
    /** Folders opened from the root, deepest last. */
    val stack: List<LibraryEntry> = emptyList(),
    val entries: List<LibraryEntry>? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
)


/**
 * Looks for the episode in the user's subtitle folders. Runs in the player's own coroutine (it is
 * cancelled when the player closes or the episode changes); nothing runs in the background.
 */
internal suspend fun PlayerScreenRuntime.runSubtitleLibrarySearch() {
    val videoId = activeVideoId
    if (librarySubtitlesVideoId != videoId) {
        librarySubtitles = emptyList()
        libraryPrepared = emptyMap()
        libraryResult = null
        libraryWarnedIds.clear()
        SubtitleLibraryPlatform.clearFonts()
    }
    if (!SubtitleLibraryPlatform.isSupported || isLiveTvPlayback) return
    if (SubtitleLibraryStore.current().enabledFolders.isEmpty()) return
    librarySubtitlesVideoId = videoId
    librarySearching = true
    try {
        // Usually ready already: the search started while the streams were being fetched.
        val prefetched = SubtitleLibraryPrefetch.take(activeVideoId ?: parentMetaId)
        val query = prefetched?.first ?: buildLibraryQuery()
        libraryQuery = query
        val result = prefetched?.second ?: SubtitleLibraryPlatform.search(query)
        if (activeVideoId != videoId) return
        publishLibraryResult(result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        InAppLogger.warn(TAG, "search failed: ${error::class.simpleName} ${LibraryRedaction.text(error.message)}")
    } finally {
        librarySearching = false
    }
}

/** The episode and every title it may be filed under: the catalogue's, the file's and AniList/ARM aliases. */
private suspend fun PlayerScreenRuntime.buildLibraryQuery(): LibraryQuery = buildLibraryQuery(
    type = contentType?.takeIf { it.isNotBlank() } ?: parentMetaType,
    videoId = activeVideoId ?: parentMetaId,
    parentMetaId = parentMetaId,
    title = title,
    season = activeSeasonNumber,
    episode = activeEpisodeNumber,
    isMovie = isMoviePlayback,
    preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
)

/** The episode and every title it may be filed under: the catalogue's, the given one and AniList/ARM aliases. */
internal suspend fun buildLibraryQuery(
    type: String,
    videoId: String,
    parentMetaId: String,
    title: String,
    season: Int?,
    episode: Int?,
    isMovie: Boolean,
    preferredLanguage: String?,
): LibraryQuery {
    val request = runCatching { CloudStreams.request(type, videoId, season, episode) }
        .getOrElse { if (it is CancellationException) throw it; null }
    val titles = (listOfNotNull(request?.displayTitle, title.takeIf { it.isNotBlank() }) + request?.aliases.orEmpty().map { it.title })
        .filter { it.isNotBlank() }
        .distinct()
    return LibraryQuery(
        seriesKey = parentMetaId.takeIf { it.isNotBlank() },
        season = request?.season ?: season,
        episode = request?.episode ?: episode ?: if (isMovie) 1 else null,
        absoluteEpisode = request?.absoluteEpisode,
        titles = titles,
        preferredLanguage = preferredLanguage,
    )
}

private suspend fun originLabel(folder: SubtitleFolder): String = getString(
    when (folder.originKind) {
        SubtitleOrigin.TERABOX -> Res.string.subtitle_folders_origin_terabox
        SubtitleOrigin.DRIVE -> Res.string.subtitle_folders_origin_drive
        else -> Res.string.subtitle_folders_origin_storage
    },
)

private fun libraryId(prepared: PreparedLibrarySubtitle): String =
    LIBRARY_SUBTITLE_ID_PREFIX + "${prepared.root.id}|${prepared.fileId}".hashCode().toUInt().toString(16)

private suspend fun PreparedLibrarySubtitle.toAddonSubtitle(): AddonSubtitle {
    val fontsLabel = fonts.size.takeIf { it > 0 }?.let { getPluralString(Res.plurals.subtitle_folders_fonts, it, it) }
    return AddonSubtitle(
        id = libraryId(this),
        url = url,
        language = language,
        display = fileName,
        addonName = originLabel(root),
        label = listOfNotNull(fileName, fontsLabel).joinToString(" · "),
    )
}

private suspend fun PlayerScreenRuntime.publishLibraryResult(result: LibrarySearchResult, select: AddonSubtitle? = null) {
    val prepared = result.subtitles
    val entries = prepared.map { it.toAddonSubtitle() }
    prepared.forEach { SubtitleLibraryPlatform.registerFonts(it.url, it.fonts) }
    libraryPrepared = prepared.associateBy(::libraryId)
    librarySubtitles = entries
    libraryResult = result
    when {
        select != null -> entries.firstOrNull { it.id == select.id }?.let { selectAddonSubtitle(it) }
        !isUserExplicitSubtitleSelection -> tryAutoSelectLibrarySubtitle(preferredSubtitlePrimaryTarget())
    }
}

internal fun PlayerScreenRuntime.preferredSubtitlePrimaryTarget(): String? {
    val preferred = normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage)
    if (preferred == SubtitleLanguageOption.NONE || preferred == SubtitleLanguageOption.FORCED) return null
    return resolvePreferredSubtitleLanguageTargets(
        preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
    ).firstOrNull()
}

/**
 * "Elegir automáticamente mis subtítulos": a folder subtitle picked by hand for this episode, or
 * one in the preferred language, is chosen before the file's and the add-ons', and it replaces an
 * automatic choice even when it arrives later (cloud fonts take a while). A subtitle the user chose
 * is never replaced. Returns true when a folder subtitle is (now) the selection.
 */
internal fun PlayerScreenRuntime.tryAutoSelectLibrarySubtitle(primaryTarget: String?): Boolean {
    if (isUserExplicitSubtitleSelection) return false
    if (!SubtitleLibraryStore.current().autoSelect) return false
    val library = currentLibrarySubtitles
    if (library.isEmpty()) return false
    if (selectedAddonSubtitle?.isLibrarySubtitle == true) {
        preferredSubtitleSelectionApplied = true
        return true
    }
    val candidate = library.firstOrNull { libraryPrepared[it.id]?.picked == true }
        ?: primaryTarget?.let { target -> library.firstOrNull { SubtitleLanguageMatching.matchesLanguageCode(it.language, target) } }
        ?: return false
    val controller = playerController ?: return false
    preferredSubtitleSelectionApplied = true
    selectedAddonSubtitleId = candidate.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    controller.setSubtitleUri(candidate.url)
    controller.setSubtitleDelayMs(subtitleDelayMs)
    InAppLogger.info(TAG, "chose a folder subtitle automatically")
    onLibrarySubtitleSelected(candidate)
    return true
}

/**
 * The engine notice, once per subtitle when it is chosen: its fonts need libass on ExoPlayer.
 * Missing fonts only go to the log (the user asked not to see that notice).
 */
internal fun PlayerScreenRuntime.onLibrarySubtitleSelected(subtitle: AddonSubtitle) {
    val prepared = libraryPrepared[subtitle.id] ?: return
    if (!libraryWarnedIds.add(subtitle.id)) return
    val engineCanUseFonts = playerController?.playbackEngine != AndroidPlaybackEngine.ExoPlayer ||
        playerSettingsUiState.useLibass || playerSettingsUiState.useLibmpvAss
    if (prepared.missingFonts.isNotEmpty()) {
        InAppLogger.info(TAG, "fonts not found in the folder: ${prepared.missingFonts.size}")
    }
    if (prepared.isAss && prepared.fonts.isNotEmpty() && !engineCanUseFonts) {
        scope.launch { NuvioToastController.show(getString(Res.string.subtitle_folders_engine_no_fonts)) }
    }
}

// ---- In-player browser ------------------------------------------------------------------------

internal fun PlayerScreenRuntime.openSubtitleLibraryBrowser(linking: Boolean) {
    val roots = SubtitleLibraryStore.current().enabledFolders
    val single = roots.singleOrNull()
    val anime = single?.let { libraryResult?.animeFolders?.get(it.id) }
    val state = SubtitleLibraryBrowserState(
        linking = linking,
        root = single,
        // Searching starts in the anime folder found, when there is one.
        stack = if (!linking && anime != null) listOf(anime) else emptyList(),
    )
    libraryBrowser = state
    if (single != null) loadSubtitleLibraryBrowser(state)
}

internal fun PlayerScreenRuntime.subtitleLibraryRoots(): List<SubtitleFolder> = SubtitleLibraryStore.current().enabledFolders

internal fun PlayerScreenRuntime.openSubtitleLibraryRoot(root: SubtitleFolder) {
    val state = libraryBrowser ?: return
    val next = state.copy(root = root, stack = emptyList(), entries = null, failed = false)
    libraryBrowser = next
    loadSubtitleLibraryBrowser(next)
}

internal fun PlayerScreenRuntime.openSubtitleLibraryEntry(entry: LibraryEntry) {
    val state = libraryBrowser ?: return
    if (entry.isFolder) {
        val next = state.copy(stack = state.stack + entry, entries = null, failed = false)
        libraryBrowser = next
        loadSubtitleLibraryBrowser(next)
    } else if (!state.linking) {
        pickLibraryFile(state, entry)
    }
}

internal fun PlayerScreenRuntime.subtitleLibraryBrowserUp() {
    val state = libraryBrowser ?: return
    val next = when {
        state.stack.isNotEmpty() -> state.copy(stack = state.stack.dropLast(1), entries = null, failed = false)
        subtitleLibraryRoots().size > 1 -> state.copy(root = null, entries = null, failed = false)
        else -> return
    }
    libraryBrowser = next
    if (next.root != null) loadSubtitleLibraryBrowser(next)
}

internal fun PlayerScreenRuntime.linkSubtitleLibraryFolder() {
    val state = libraryBrowser ?: return
    val root = state.root ?: return
    val folder = state.stack.lastOrNull() ?: return
    val seriesKey = parentMetaId.takeIf { it.isNotBlank() } ?: return
    SubtitleLibraryStore.link(seriesKey, SubtitleFolderLink(root.id, folder.id, folder.name, folder.path))
    libraryBrowser = null
    scope.launch {
        NuvioToastController.show(getString(Res.string.subtitle_folders_linked, folder.name))
        runSubtitleLibrarySearch()
    }
}

private fun PlayerScreenRuntime.loadSubtitleLibraryBrowser(state: SubtitleLibraryBrowserState) {
    val root = state.root ?: return
    val folder = state.stack.lastOrNull()
    scope.launch {
        val entries = try {
            SubtitleLibraryPlatform.browse(root, folder?.id, folder?.path ?: root.folderPath)
                .filter { !state.linking || it.isFolder }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "browse failed: ${error::class.simpleName}")
            null
        }
        if (libraryBrowser?.root?.id != root.id || libraryBrowser?.stack != state.stack) return@launch
        libraryBrowser = libraryBrowser?.copy(entries = entries.orEmpty(), failed = entries == null)
    }
}

private fun PlayerScreenRuntime.pickLibraryFile(state: SubtitleLibraryBrowserState, file: LibraryEntry) {
    val root = state.root ?: return
    val folder = state.stack.lastOrNull() ?: LibraryEntry(root.folderId, root.displayName, true, path = root.folderPath)
    val query = libraryQuery ?: return
    libraryBrowser = state.copy(busy = true)
    scope.launch {
        try {
            val prepared = SubtitleLibraryPlatform.preparePicked(root, folder, file, query)
            libraryBrowser = null
            val current = libraryResult ?: LibrarySearchResult()
            val merged = current.copy(subtitles = listOf(prepared) + current.subtitles.filterNot { it.path == prepared.path })
            librarySubtitlesVideoId = activeVideoId
            publishLibraryResult(merged, select = prepared.toAddonSubtitle())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "pick failed: ${error::class.simpleName} ${LibraryRedaction.text(error.message)}")
            libraryBrowser = libraryBrowser?.copy(busy = false)
            NuvioToastController.show(getString(Res.string.subtitle_folders_failed))
        }
    }
}
