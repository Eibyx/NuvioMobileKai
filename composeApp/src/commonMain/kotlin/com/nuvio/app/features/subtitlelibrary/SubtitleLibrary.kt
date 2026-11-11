package com.nuvio.app.features.subtitlelibrary

import androidx.compose.runtime.Composable

/** Why a configured folder could not be read this time. */
enum class LibraryUnavailable { USB_DISCONNECTED, ACCOUNT_DISCONNECTED, PERMISSION_MISSING, FAILED }

/** What the player asks for: the episode and every title it may be filed under. */
internal data class LibraryQuery(
    /** `anilist:…`, `kitsu:…`, `tt…`, `tmdb:…`: what a manual folder link is remembered by. */
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val absoluteEpisode: Int?,
    val titles: List<String>,
    val preferredLanguage: String?,
) {
    val episodeKey: String? get() = seriesKey?.let { "$it:${season ?: 0}:${episode ?: 0}" }
}

/** A font of a subtitle's folder, copied into the cache. */
internal data class LibraryFont(val name: String, val path: String)

/** A subtitle copied into the cache with the fonts it uses, ready for the player (`file://`). */
internal data class PreparedLibrarySubtitle(
    val root: SubtitleFolder,
    val fileName: String,
    val fileId: String,
    val language: String,
    /** Absolute path of the cached copy (UTF-8). */
    val path: String,
    val isAss: Boolean,
    val fonts: List<LibraryFont>,
    val missingFonts: Set<String>,
    val picked: Boolean,
) {
    val url: String get() = "file://$path"
}

internal data class LibrarySearchResult(
    val subtitles: List<PreparedLibrarySubtitle> = emptyList(),
    /** Roots that could not be read now (USB unplugged, account disconnected…). */
    val unavailable: List<Pair<SubtitleFolder, LibraryUnavailable>> = emptyList(),
    /** Roots where no single folder fits the title: "Vincular carpeta…". */
    val needsLink: List<SubtitleFolder> = emptyList(),
    /** The anime folder found in each root (root id → folder), for "Buscar en mis carpetas…". */
    val animeFolders: Map<String, LibraryEntry> = emptyMap(),
)

/** A storage volume (internal storage, SD card, USB drive) offered directly in the folder picker. */
data class LibraryVolume(val id: String, val description: String, val removable: Boolean)

/**
 * The user's subtitle folders on this platform: searched on demand from the player, never in the
 * background. Android only; on iOS [isSupported] is false and the feature is not shown.
 */
internal expect object SubtitleLibraryPlatform {
    val isSupported: Boolean
    suspend fun search(query: LibraryQuery): LibrarySearchResult
    /** The entries of a folder for the in-player browser; [folderId] null means the root. */
    suspend fun browse(root: SubtitleFolder, folderId: String?, folderPath: String): List<LibraryEntry>
    suspend fun preparePicked(root: SubtitleFolder, folder: LibraryEntry, file: LibraryEntry, query: LibraryQuery): PreparedLibrarySubtitle
    /** "Actualizar": forgets the listings of one root (or all). */
    fun invalidate(rootId: String?)
    fun cacheBytes(): Long
    fun clearCache()
    /** Tells the players which fonts go with a prepared subtitle (by its `file://` URL). */
    fun registerFonts(subtitleUrl: String, fonts: List<LibraryFont>)
    /** New episode or stream: nothing of the previous one may reach the next. */
    fun clearFonts()
    /** Removes the folder's permission (local folders) when the user removes it. */
    fun release(folder: SubtitleFolder)
}

/** Opens the system's document picker for a folder; [onPicked] gets the new root. */
internal interface SubtitleFolderPicker {
    fun pickFolder()
    /** Storage volumes the picker can open directly (API 29+); empty elsewhere. */
    fun volumes(): List<LibraryVolume>
    fun pickVolume(volume: LibraryVolume)
}

@Composable
internal expect fun rememberSubtitleFolderPicker(onPicked: (SubtitleFolder) -> Unit): SubtitleFolderPicker
