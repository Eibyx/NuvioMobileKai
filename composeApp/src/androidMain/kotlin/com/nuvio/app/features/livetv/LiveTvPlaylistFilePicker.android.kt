package com.nuvio.app.features.livetv

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLiveTvPlaylistFilePicker(
    onPlaylistLoaded: (fileName: String?, content: String) -> Unit,
    onError: (String) -> Unit,
): LiveTvPlaylistFilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Copied byte by byte into the app's storage, off the main thread: the playlist keeps
            // the copy's path, so a list of hundreds of megabytes is never held in memory whole.
            runCatching {
                withContext(Dispatchers.IO) {
                    val folder = File(context.filesDir, LOCAL_PLAYLIST_FOLDER).apply { mkdirs() }
                    val copy = File(folder, "${UUID.randomUUID()}.m3u")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Failed to read M3U file.")
                    input.use { source -> copy.outputStream().use { target -> source.copyTo(target) } }
                    if (copy.length() == 0L) {
                        copy.delete()
                        error("The M3U file is empty.")
                    }
                    uri.displayName(context) to (LOCAL_PLAYLIST_FILE_PREFIX + copy.path)
                }
            }.onSuccess { (name, source) ->
                onPlaylistLoaded(name, source)
            }.onFailure { error ->
                onError(error.message ?: "Failed to read M3U file.")
            }
        }
    }

    return remember(launcher) {
        LiveTvPlaylistFilePicker(canPickFiles = true) {
            launcher.launch(
                arrayOf(
                    "application/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "audio/mpegurl",
                    "audio/x-mpegurl",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
        }
    }
}

private fun Uri.displayName(context: android.content.Context): String? {
    val cursor = context.contentResolver.query(this, null, null, null, null) ?: return lastPathSegment
    return cursor.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else lastPathSegment
    }
}

private const val LOCAL_PLAYLIST_FOLDER = "livetv-playlists"
