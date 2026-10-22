/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * The external subtitle flow (validate by extension, add the file once, keep it selected under its
 * own name) follows mpvRx's PlayerViewModel.addSubtitleSuspend and SubtitleTracksSheet
 * (https://github.com/Riteshp2001/mpvRx).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** Subtitle formats "Add external subtitles" accepts, checked by file extension. */
internal val EXTERNAL_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

/** Larger files are not subtitles (a VobSub .sub is binary and is rejected anyway). */
internal const val MAX_EXTERNAL_SUBTITLE_BYTES = 20L * 1024L * 1024L

internal fun externalSubtitleExtension(fileName: String?): String? =
    fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in EXTERNAL_SUBTITLE_EXTENSIONS }

/**
 * A subtitle file the user picked, copied into the app's cache.
 * - [rawPath]: the bytes as picked, for mpv (it detects the encoding itself, `sub-codepage=auto`).
 * - [utf8Path]: converted to UTF-8 (and MicroDVD .sub to SubRip), for ExoPlayer.
 */
data class ImportedSubtitle(
    val id: String,
    val displayName: String,
    val language: String?,
    val rawPath: String,
    val utf8Path: String,
)

enum class ExternalSubtitleRejection { UnsupportedFormat, NotText, TooLarge, ReadFailed }

/** Opens the system file picker (all files, in the last folder used) and imports the pick. */
@Composable
internal expect fun rememberExternalSubtitlePicker(
    onImported: (ImportedSubtitle) -> Unit,
    onRejected: (ExternalSubtitleRejection) -> Unit,
): (() -> Unit)?

/** The language a file name carries before its extension: "Movie.es.srt", "Show.S01E02.eng.ass". */
internal fun externalSubtitleLanguage(fileName: String): String? {
    val stem = fileName.substringBeforeLast('.')
    val tag = stem.substringAfterLast('.', "").substringAfterLast('_').substringAfterLast('-').trim()
    if (tag.length !in 2..3 || !tag.all { it.isLetter() }) return null
    // Only tags that name a language the app knows ("HDR", "WEB" are not languages).
    return normalizeLanguageCode(tag)?.takeIf { languageLabelResForCode(it) != null }
}

/**
 * Text of a subtitle file in any of the encodings these files come in: UTF-8 (with or without
 * BOM), UTF-16 with BOM, and otherwise Windows-1252, which also covers ISO-8859-1.
 * Returns null for binary data (VobSub .sub, images).
 */
internal fun decodeSubtitleBytes(bytes: ByteArray): String? {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return bytes.decodeToString(3, bytes.size).takeIf { it.isText() }
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return decodeUtf16(bytes, 2, littleEndian = true).takeIf { it.isText() }
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return decodeUtf16(bytes, 2, littleEndian = false).takeIf { it.isText() }
    }
    if (bytes.any { it == 0.toByte() }) return null
    val utf8 = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    if (utf8 != null) return utf8.takeIf { it.isText() }
    return decodeWindows1252(bytes).takeIf { it.isText() }
}

private fun String.isText(): Boolean {
    if (isEmpty()) return false
    val sample = take(4_000)
    val control = sample.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
    return control * 50 < sample.length
}

private fun decodeUtf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
    val builder = StringBuilder((bytes.size - offset) / 2)
    var index = offset
    while (index + 1 < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val second = bytes[index + 1].toInt() and 0xFF
        builder.append((if (littleEndian) (second shl 8) or first else (first shl 8) or second).toChar())
        index += 2
    }
    return builder.toString()
}

/** Windows-1252: ISO-8859-1 plus typographic characters in 0x80..0x9F. */
private val WINDOWS_1252_HIGH = charArrayOf(
    '€', '�', '‚', 'ƒ', '„', '…', '†', '‡',
    'ˆ', '‰', 'Š', '‹', 'Œ', '�', 'Ž', '�',
    '�', '‘', '’', '“', '”', '•', '–', '—',
    '˜', '™', 'š', '›', 'œ', '�', 'ž', 'Ÿ',
)

internal fun decodeWindows1252(bytes: ByteArray): String {
    val chars = CharArray(bytes.size) { index ->
        val value = bytes[index].toInt() and 0xFF
        if (value in 0x80..0x9F) WINDOWS_1252_HIGH[value - 0x80] else value.toChar()
    }
    return chars.concatToString()
}

private val MICRO_DVD_LINE = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""")

/** True for MicroDVD text (`{start}{end}Text`), the usual text .sub. */
internal fun isMicroDvd(text: String): Boolean =
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(5).any { MICRO_DVD_LINE.matches(it) }

/**
 * MicroDVD frames to SubRip, which ExoPlayer reads. The frame rate comes from the usual
 * `{1}{1}23.976` first line, else 23.976. `|` breaks lines and `{y:i}`-style tags are dropped.
 */
internal fun microDvdToSrt(text: String): String {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    var fps = 23.976
    val cues = mutableListOf<Triple<Long, Long, String>>()
    lines.forEachIndexed { index, line ->
        val match = MICRO_DVD_LINE.matchEntire(line) ?: return@forEachIndexed
        val (startText, endText, body) = match.destructured
        if (index == 0 && startText == endText) {
            body.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..120.0 }?.let {
                fps = it
                return@forEachIndexed
            }
        }
        val start = startText.toLong()
        val end = endText.toLongOrNull()?.takeIf { it > start } ?: (start + (fps * 3).toLong())
        val cleaned = body.replace(Regex("""\{[^}]*\}"""), "").replace('|', '\n').trim()
        if (cleaned.isNotEmpty()) cues += Triple(start, end, cleaned)
    }
    return buildString {
        cues.forEachIndexed { index, (start, end, body) ->
            append(index + 1).append('\n')
            append(srtTime(start / fps)).append(" --> ").append(srtTime(end / fps)).append('\n')
            append(body).append("\n\n")
        }
    }
}

private fun srtTime(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong().coerceAtLeast(0L)
    val hours = totalMs / 3_600_000
    val minutes = totalMs / 60_000 % 60
    val secs = totalMs / 1_000 % 60
    val millis = totalMs % 1_000
    return "${hours.pad(2)}:${minutes.pad(2)}:${secs.pad(2)},${millis.pad(3)}"
}

private fun Long.pad(length: Int) = toString().padStart(length, '0')

/** The text ExoPlayer is given: UTF-8, MicroDVD turned into SubRip. Null when not a subtitle. */
internal fun playableSubtitleText(extension: String, bytes: ByteArray): Pair<String, String>? {
    val text = decodeSubtitleBytes(bytes)?.removePrefix("﻿") ?: return null
    return if (extension == "sub") {
        when {
            isMicroDvd(text) -> "srt" to microDvdToSrt(text)
            // Some .sub files are SubRip under another name.
            "-->" in text -> "srt" to text
            else -> null
        }
    } else {
        extension to text
    }
}

/** The entry an imported file gets in the subtitle list: its own name, under its language. */
internal fun ImportedSubtitle.toAddonSubtitle(useRawFile: Boolean, sourceName: String): AddonSubtitle =
    AddonSubtitle(
        id = id,
        url = "file://" + if (useRawFile) rawPath else utf8Path,
        language = language ?: "",
        display = displayName,
        addonName = sourceName,
    )
