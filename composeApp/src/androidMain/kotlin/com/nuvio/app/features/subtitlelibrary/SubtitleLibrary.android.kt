package com.nuvio.app.features.subtitlelibrary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.LibraryFontRegistry
import kotlin.random.Random

internal actual object SubtitleLibraryPlatform {
    actual val isSupported: Boolean = true
    actual suspend fun search(query: LibraryQuery): LibrarySearchResult = SubtitleLibraryService.search(query)
    actual suspend fun browse(root: SubtitleFolder, folderId: String?, folderPath: String): List<LibraryEntry> =
        SubtitleLibraryService.browse(root, folderId, folderPath)
    actual suspend fun preparePicked(
        root: SubtitleFolder,
        folder: LibraryEntry,
        file: LibraryEntry,
        query: LibraryQuery,
    ): PreparedLibrarySubtitle = SubtitleLibraryService.preparePicked(root, folder, file, query)
    actual fun invalidate(rootId: String?) = SubtitleLibraryService.invalidate(rootId)
    actual fun cacheBytes(): Long = SubtitleLibraryService.cacheBytes()
    actual fun clearCache() = SubtitleLibraryService.clearCache()
    actual fun registerFonts(subtitleUrl: String, fonts: List<LibraryFont>) = LibraryFontRegistry.register(subtitleUrl, fonts)
    actual fun clearFonts() = LibraryFontRegistry.clear()
    actual fun release(folder: SubtitleFolder) = SubtitleLibraryService.release(folder)
}

/**
 * Folders are picked with the system's document picker only (no storage permission): internal
 * storage, an SD card or a USB drive. On Android 10+ each storage volume can be opened directly.
 */
@Composable
internal actual fun rememberSubtitleFolderPicker(onPicked: (SubtitleFolder) -> Unit): SubtitleFolderPicker {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    fun keep(tree: Uri?, volume: String?) {
        tree ?: return
        val folder = runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SubtitleFolder(
                id = "local-" + Random.nextLong().toULong().toString(16),
                origin = SubtitleOrigin.LOCAL.id,
                displayName = folderName(context, tree),
                folderId = tree.toString(),
                volume = volume,
            )
        }.onFailure {
            InAppLogger.warn("Subtitles/Folders", "folder permission not kept: ${it::class.java.simpleName}")
        }.getOrNull() ?: return
        currentOnPicked(folder)
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> keep(tree, null) }
    val pendingVolume = remember { arrayOfNulls<String>(1) }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) keep(result.data?.data, pendingVolume[0])
    }
    return remember(context) {
        object : SubtitleFolderPicker {
            override fun pickFolder() {
                runCatching { treeLauncher.launch(null) }
            }

            override fun volumes(): List<LibraryVolume> {
                if (Build.VERSION.SDK_INT < 29) return emptyList()
                val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
                return manager.storageVolumes.mapIndexedNotNull { index, volume ->
                    if (volume.state != android.os.Environment.MEDIA_MOUNTED && volume.state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY) {
                        return@mapIndexedNotNull null
                    }
                    LibraryVolume(
                        id = volume.uuid ?: "primary-$index",
                        description = volume.getDescription(context),
                        removable = volume.isRemovable,
                    )
                }
            }

            override fun pickVolume(volume: LibraryVolume) {
                if (Build.VERSION.SDK_INT < 29) return pickFolder()
                val manager = context.getSystemService(StorageManager::class.java) ?: return pickFolder()
                val match = manager.storageVolumes.firstOrNull { (it.uuid ?: "") == volume.id }
                    ?: manager.storageVolumes.firstOrNull { it.isPrimary && volume.id.startsWith("primary") }
                    ?: return pickFolder()
                pendingVolume[0] = volume.description
                runCatching { volumeLauncher.launch(match.createOpenDocumentTreeIntent()) }.onFailure { pickFolder() }
            }
        }
    }
}

private fun folderName(context: Context, tree: Uri): String = runCatching {
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    context.contentResolver.query(
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Subtítulos"
