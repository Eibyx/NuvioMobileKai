/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copying a folder of fonts for mpv's sub-fonts-dir follows mpvRx's
 * FontPreferencesUtils.copyFontsFromDirectory (https://github.com/Riteshp2001/mpvRx), extended to
 * walk every subfolder, skip identical copies and invalid files, and keep repeated names apart.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Font files a folder may hold. */
internal val FONT_FILE_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

/**
 * Fonts copied from the user's font directory live next to the imported default fonts, so mpv
 * finds both through one `sub-fonts-dir`. This prefix keeps them out of the default font list.
 */
internal const val DIRECTORY_FONT_PREFIX = "dir_"

internal fun hasFontFileExtension(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in FONT_FILE_EXTENSIONS

/** What a font file found in the folder becomes. */
internal sealed interface FontPlacement {
    data class Copy(val fileName: String) : FontPlacement
    /** The same font (same bytes) is already there, under this name. */
    data class Identical(val fileName: String) : FontPlacement
    data object NotAFont : FontPlacement
}

/**
 * Names the fonts of a nested folder in one flat folder: identical files are copied once, and
 * different fonts with the same name ("Regular.ttf" in two families) get " (2)", " (3)"...
 * [existing] maps the names already there to their content hash.
 */
internal class FlatFontNamer(existing: Map<String, String> = emptyMap()) {
    private val hashByName = existing.toMutableMap()
    private val nameByHash = existing.entries.associate { (name, hash) -> hash to name }.toMutableMap()

    fun place(originalName: String, contentHash: String, isFont: Boolean): FontPlacement {
        if (!isFont) return FontPlacement.NotAFont
        nameByHash[contentHash]?.let { return FontPlacement.Identical(it) }
        val extension = originalName.substringAfterLast('.', "ttf").lowercase()
        val stem = originalName.substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' || it == '.') it else '_' }
            .joinToString("")
            .trim()
            .take(80)
            .ifBlank { "font" }
        var candidate = "$DIRECTORY_FONT_PREFIX$stem.$extension"
        var index = 2
        while (hashByName.keys.any { it.equals(candidate, ignoreCase = true) }) {
            candidate = "$DIRECTORY_FONT_PREFIX$stem ($index).$extension"
            index++
        }
        hashByName[candidate] = contentHash
        nameByHash[contentHash] = candidate
        return FontPlacement.Copy(candidate)
    }
}

/** True when [bytes] is a TrueType/OpenType font or collection with a readable family name. */
internal fun isValidFontFile(bytes: ByteArray): Boolean =
    bytes.size >= 12 && OpenTypeNameReader.familyName(bytes) != null

data class FontDirectoryProgress(
    val found: Int,
    val processed: Int,
    val copied: Int,
    val skipped: Int,
    val copiedBytes: Long,
)

data class FontDirectoryState(
    /** The folder the user chose, as the system names it; null when none is set. */
    val folder: String? = null,
    val fontCount: Int = 0,
    val totalBytes: Long = 0L,
    val progress: FontDirectoryProgress? = null,
    val lastResult: FontDirectoryProgress? = null,
)

/** Platform side: the folder picker, the recursive copy and the clean-up. */
internal expect object SubtitleFontDirectoryPlatform {
    val isSupported: Boolean
    fun folderLabel(): String?
    fun hasFolder(): Boolean
    suspend fun copyFromFolder(onProgress: (FontDirectoryProgress) -> Unit): FontDirectoryProgress?
    fun clear()
    /** Number and total size of the fonts copied from the folder. */
    fun summary(): Pair<Int, Long>
}

/** Opens the system folder picker; [onPicked] runs once the folder is remembered. */
@Composable
internal expect fun rememberFontDirectoryPicker(onPicked: () -> Unit): () -> Unit

object SubtitleFontDirectoryRepository {
    private val mutableState = MutableStateFlow(FontDirectoryState())
    val state: StateFlow<FontDirectoryState> = mutableState.asStateFlow()

    val isSupported: Boolean get() = SubtitleFontDirectoryPlatform.isSupported

    fun refresh() {
        val (count, bytes) = SubtitleFontDirectoryPlatform.summary()
        mutableState.value = mutableState.value.copy(
            folder = SubtitleFontDirectoryPlatform.folderLabel(),
            fontCount = count,
            totalBytes = bytes,
        )
    }

    /** Copies (again) every font of the chosen folder and its subfolders. */
    suspend fun reload() {
        if (!SubtitleFontDirectoryPlatform.hasFolder() || mutableState.value.progress != null) return
        mutableState.value = mutableState.value.copy(progress = FontDirectoryProgress(0, 0, 0, 0, 0L))
        val result = try {
            SubtitleFontDirectoryPlatform.copyFromFolder { progress ->
                mutableState.value = mutableState.value.copy(progress = progress)
            }
        } finally {
            mutableState.value = mutableState.value.copy(progress = null)
        }
        mutableState.value = mutableState.value.copy(lastResult = result)
        refresh()
    }

    fun clear() {
        SubtitleFontDirectoryPlatform.clear()
        mutableState.value = FontDirectoryState()
        refresh()
    }
}

/** "12.3 MB" style sizes for the font directory summary. */
internal fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = kotlin.math.round(value * 10) / 10
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$text ${units[unit]}"
}
