/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copying a folder of fonts for mpv's sub-fonts-dir follows mpvRx's
 * FontPreferencesUtils.copyFontsFromDirectory (https://github.com/Riteshp2001/mpvRx), which only
 * read the first level. This version walks every subfolder at any depth.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nuvio.app.features.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.aiostreams.FontFile
import com.nuvio.app.features.player.aiostreams.fontNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

private const val TAG = "Player/Fonts"
private const val PREFERENCES = "nuvio_font_directory"
private const val KEY_TREE = "tree"
private const val KEY_LABEL = "label"
private const val INDEX_FILE = "subtitle_font_directory.json"
private const val MAX_FONT_FILE_BYTES = 64L * 1024L * 1024L

@Serializable
private data class DirectoryFont(
    val file: String,
    val hash: String,
    /** The names libass matches against (see fontNames), for the ExoPlayer ASS engine. */
    val names: List<String> = emptyList(),
)

private val indexJson = Json { ignoreUnknownKeys = true }

internal actual object SubtitleFontDirectoryPlatform {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    actual val isSupported: Boolean = true

    private fun preferences() = context?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun fontsFolder(): File? = SubtitleFontFiles.directoryPath()?.let(::File)

    private fun indexFile(): File? = context?.filesDir?.let { File(it, INDEX_FILE) }

    actual fun folderLabel(): String? = preferences()?.getString(KEY_LABEL, null)

    actual fun hasFolder(): Boolean = preferences()?.getString(KEY_TREE, null) != null

    /** Keeps read access to [tree] across restarts and remembers it as the font directory. */
    fun rememberFolder(tree: Uri) {
        val context = context ?: return
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { InAppLogger.warn(TAG, "No lasting access to the font folder: ${InAppLogger.throwableSummary(it)}") }
        preferences()?.edit()
            ?.putString(KEY_TREE, tree.toString())
            ?.putString(KEY_LABEL, readableFolder(tree))
            ?.apply()
    }

    actual suspend fun copyFromFolder(onProgress: (FontDirectoryProgress) -> Unit): FontDirectoryProgress? =
        withContext(Dispatchers.IO) {
            val context = context ?: return@withContext null
            val tree = preferences()?.getString(KEY_TREE, null)?.let(Uri::parse) ?: return@withContext null
            val target = fontsFolder()?.apply { mkdirs() } ?: return@withContext null
            InAppLogger.info(TAG, "Reading fonts from ${folderLabel()}")

            // Every subfolder, at any depth.
            val found = mutableListOf<Pair<Uri, String>>()
            val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val folderId = pending.removeFirst()
                if (!visited.add(folderId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                runCatching {
                    context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                pending += id
                            } else if (hasFontFileExtension(name)) {
                                found += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                            }
                        }
                    }
                }.onFailure { InAppLogger.warn(TAG, "Could not list a font subfolder: ${InAppLogger.throwableSummary(it)}") }
                onProgress(FontDirectoryProgress(found.size, 0, 0, 0, 0L))
            }

            val previous = readIndex()
            val namer = FlatFontNamer(previous.associate { it.file to it.hash })
            val kept = LinkedHashMap<String, DirectoryFont>()
            var copied = 0
            var skipped = 0
            var copiedBytes = 0L
            found.forEachIndexed { index, (uri, name) ->
                coroutineContext.ensureActive()
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> readAtMost(input, MAX_FONT_FILE_BYTES) }
                }.getOrNull()
                if (bytes == null || bytes.size > MAX_FONT_FILE_BYTES) {
                    skipped++
                } else {
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    when (val placement = namer.place(name, hash, isValidFontFile(bytes))) {
                        is FontPlacement.Copy -> {
                            val file = File(target, placement.fileName)
                            val written = runCatching {
                                val temp = File(target, placement.fileName + ".tmp")
                                temp.writeBytes(bytes)
                                temp.renameTo(file) || run { temp.delete(); false }
                            }.getOrDefault(false)
                            if (written) {
                                kept[placement.fileName] = DirectoryFont(placement.fileName, hash, fontNames(bytes)?.toList().orEmpty())
                                copied++
                                copiedBytes += bytes.size
                            } else {
                                skipped++
                            }
                        }
                        is FontPlacement.Identical -> {
                            if (placement.fileName in kept) {
                                // The same font twice in the folder: copied once.
                                skipped++
                            } else {
                                // Copied by an earlier run: kept, and written again if it went missing.
                                val file = File(target, placement.fileName)
                                if (!file.isFile) runCatching { file.writeBytes(bytes) }
                                kept[placement.fileName] = previous.firstOrNull { it.file == placement.fileName }
                                    ?: DirectoryFont(placement.fileName, hash, fontNames(bytes)?.toList().orEmpty())
                            }
                        }
                        FontPlacement.NotAFont -> skipped++
                    }
                }
                onProgress(FontDirectoryProgress(found.size, index + 1, copied, skipped, copiedBytes))
            }

            // Fonts that are no longer in the folder go too.
            target.listFiles()
                ?.filter { it.isFile && it.name.startsWith(DIRECTORY_FONT_PREFIX) && it.name !in kept }
                ?.forEach { it.delete() }
            writeIndex(kept.values.toList())
            val result = FontDirectoryProgress(found.size, found.size, copied, skipped, copiedBytes)
            InAppLogger.info(
                TAG,
                "Font directory: ${found.size} font files found, $copied copied, ${kept.size} in use, $skipped skipped",
            )
            result
        }

    actual fun clear() {
        fontsFolder()?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(DIRECTORY_FONT_PREFIX) }
            ?.forEach { it.delete() }
        indexFile()?.delete()
        val tree = preferences()?.getString(KEY_TREE, null)?.let(Uri::parse)
        if (tree != null) {
            runCatching { context?.contentResolver?.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        preferences()?.edit()?.clear()?.apply()
        InAppLogger.info(TAG, "Font directory cleared")
    }

    actual fun summary(): Pair<Int, Long> {
        val files = fontsFolder()?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(DIRECTORY_FONT_PREFIX) && hasFontFileExtension(it.name) }
            .orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** The directory fonts, with the names libass matches, for the ExoPlayer ASS engine. */
    fun fontFiles(): List<FontFile> {
        val folder = fontsFolder() ?: return emptyList()
        return readIndex().mapNotNull { font ->
            File(folder, font.file).takeIf { it.isFile }?.let { FontFile(it, font.names.toSet().takeIf { names -> names.isNotEmpty() }) }
        }
    }

    private fun readIndex(): List<DirectoryFont> =
        indexFile()?.takeIf { it.isFile }
            ?.let { file -> runCatching { indexJson.decodeFromString(ListSerializer(DirectoryFont.serializer()), file.readText()) }.getOrNull() }
            .orEmpty()

    private fun writeIndex(fonts: List<DirectoryFont>) {
        runCatching { indexFile()?.writeText(indexJson.encodeToString(ListSerializer(DirectoryFont.serializer()), fonts)) }
    }

    /** "primary:Fonts/Anime" → "/storage/emulated/0/Fonts/Anime"; other volumes keep their id. */
    private fun readableFolder(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        return if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0/$path" else "/storage/$volume/$path"
    }
}

/** The whole stream, or null when it is longer than [limit]. */
private fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(chunk)
        if (read < 0) return buffer.toByteArray()
        total += read
        if (total > limit) return null
        buffer.write(chunk, 0, read)
    }
}

@Composable
internal actual fun rememberFontDirectoryPicker(onPicked: () -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        SubtitleFontDirectoryPlatform.rememberFolder(tree)
        currentOnPicked()
    }
    return remember(launcher) { { launcher.launch(null) } }
}
