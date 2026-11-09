package com.nuvio.app.features.livetv

/**
 * A local M3U copied into the app's own storage. The playlist's source is then this prefix and
 * the copy's path, not the file's text: lists of over a hundred megabytes neither fit in memory
 * as one string nor belong in the settings.
 */
internal const val LOCAL_PLAYLIST_FILE_PREFIX = "kai-file:"

internal fun String.localPlaylistFilePath(): String? =
    takeIf { it.startsWith(LOCAL_PLAYLIST_FILE_PREFIX) }?.removePrefix(LOCAL_PLAYLIST_FILE_PREFIX)

internal expect object LiveTvLocalPlaylistFiles {
    /** Reads the copy at [path] line by line, never as a whole. */
    fun <T> useLines(path: String, block: (Sequence<String>) -> T): T

    fun delete(path: String)
}
