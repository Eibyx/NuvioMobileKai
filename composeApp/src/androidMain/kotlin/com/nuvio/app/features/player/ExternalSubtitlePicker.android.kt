/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Picking and loading an external subtitle follows mpvRx's PlayerViewModel.addSubtitleSuspend
 * (https://github.com/Riteshp2001/mpvRx): the name is read from the provider, the format is checked
 * by extension, and the same file is never added twice.
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
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

private const val TAG = "Player/Subtitles"
private const val PREFERENCES = "nuvio_external_subtitles"
private const val LAST_DOCUMENT = "last_document"

/** Opens the picker in the folder of the file picked last time. */
private class OpenSubtitleDocument(private val context: Context) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            lastPickedDocument(this@OpenSubtitleDocument.context)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

private fun lastPickedDocument(context: Context): Uri? =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(LAST_DOCUMENT, null)
        ?.let(Uri::parse)

private fun rememberPickedDocument(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(LAST_DOCUMENT, uri.toString()).apply()
}

@Composable
internal actual fun rememberExternalSubtitlePicker(
    onImported: (ImportedSubtitle) -> Unit,
    onRejected: (ExternalSubtitleRejection) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)
    val currentOnRejected by rememberUpdatedState(onRejected)
    val launcher = rememberLauncherForActivityResult(remember(context) { OpenSubtitleDocument(context.applicationContext) }) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { ExternalSubtitleFiles.import(context.applicationContext, uri) }
            result.fold(
                onSuccess = { imported ->
                    rememberPickedDocument(context.applicationContext, uri)
                    currentOnImported(imported)
                },
                onFailure = { error ->
                    val reason = (error as? ExternalSubtitleImportException)?.reason ?: ExternalSubtitleRejection.ReadFailed
                    InAppLogger.warn(TAG, "External subtitle rejected: $reason (${InAppLogger.throwableSummary(error)})")
                    currentOnRejected(reason)
                },
            )
        }
    }
    // "*/*": many file managers tag .ass/.ssa/.sub as octet-stream; the extension is checked instead.
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

internal class ExternalSubtitleImportException(val reason: ExternalSubtitleRejection) : Exception(reason.name)

/** The cache folder imported subtitles live in; mpv hides its own tracks from here (see the engine). */
internal object ExternalSubtitleFiles {
    private const val DIRECTORY = "external_subtitles"

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun isImported(context: Context, path: String?): Boolean =
        path != null && path.removePrefix("file://").startsWith(directory(context).absolutePath + "/")

    fun import(context: Context, uri: Uri): Result<ImportedSubtitle> = runCatching {
        val name = uri.displayName(context) ?: "subtitle"
        val extension = externalSubtitleExtension(name)
            ?: throw ExternalSubtitleImportException(ExternalSubtitleRejection.UnsupportedFormat)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_EXTERNAL_SUBTITLE_BYTES) throw ExternalSubtitleImportException(ExternalSubtitleRejection.TooLarge)
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: throw ExternalSubtitleImportException(ExternalSubtitleRejection.ReadFailed)
        val (playableExtension, text) = playableSubtitleText(extension, bytes)
            ?: throw ExternalSubtitleImportException(ExternalSubtitleRejection.NotText)

        // Same content, same entry: picking the file again selects it instead of adding a copy.
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val folder = directory(context)
        val stem = name.substringBeforeLast('.').replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").take(80)
        val raw = File(folder, "$hash-$stem.$extension")
        val utf8 = File(folder, "$hash-$stem.utf8.$playableExtension")
        if (!raw.exists()) raw.writeBytes(bytes)
        if (!utf8.exists()) utf8.writeText(text, Charsets.UTF_8)
        InAppLogger.info(TAG, "Imported external subtitle ${raw.name} (${bytes.size} bytes, $extension)")
        ImportedSubtitle(
            id = "local:$hash",
            displayName = name,
            language = externalSubtitleLanguage(name),
            rawPath = raw.absolutePath,
            utf8Path = utf8.absolutePath,
        )
    }

    /** Text of an imported file for the ASS engine and the sidecar renderer, which fetch by URL. */
    fun readText(url: String): String? {
        if (!url.startsWith("file://")) return null
        return runCatching { File(url.removePrefix("file://")).readText(Charsets.UTF_8) }.getOrNull()
    }
}

private fun Uri.displayName(context: Context): String? {
    val cursor = runCatching { context.contentResolver.query(this, null, null, null, null) }.getOrNull()
        ?: return lastPathSegment?.substringAfterLast('/')
    return cursor.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else lastPathSegment?.substringAfterLast('/')
    }
}
