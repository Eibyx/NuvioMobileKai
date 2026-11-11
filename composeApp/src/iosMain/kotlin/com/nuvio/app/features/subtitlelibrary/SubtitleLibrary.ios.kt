package com.nuvio.app.features.subtitlelibrary

import androidx.compose.runtime.Composable

/** Not offered on iOS: the "My subtitle folders" section is hidden. */
internal actual object SubtitleLibraryPlatform {
    actual val isSupported: Boolean = false
    actual suspend fun search(query: LibraryQuery): LibrarySearchResult = LibrarySearchResult()
    actual suspend fun browse(root: SubtitleFolder, folderId: String?, folderPath: String): List<LibraryEntry> = emptyList()
    actual suspend fun preparePicked(
        root: SubtitleFolder,
        folder: LibraryEntry,
        file: LibraryEntry,
        query: LibraryQuery,
    ): PreparedLibrarySubtitle = throw UnsupportedOperationException("Subtitle folders are Android only")
    actual fun invalidate(rootId: String?) = Unit
    actual fun cacheBytes(): Long = 0L
    actual fun clearCache() = Unit
    actual fun registerFonts(subtitleUrl: String, fonts: List<LibraryFont>) = Unit
    actual fun clearFonts() = Unit
    actual fun release(folder: SubtitleFolder) = Unit
}

@Composable
internal actual fun rememberSubtitleFolderPicker(onPicked: (SubtitleFolder) -> Unit): SubtitleFolderPicker =
    object : SubtitleFolderPicker {
        override fun pickFolder() = Unit
        override fun volumes(): List<LibraryVolume> = emptyList()
        override fun pickVolume(volume: LibraryVolume) = Unit
    }
