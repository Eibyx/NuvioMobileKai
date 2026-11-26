package com.nuvio.app.features.subtitledownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes extracted scripts and fonts to the device: under Download/Nuvio/Subtitles through
 * MediaStore by default, or into a folder the user picked (SAF). Fonts go once per folder: a font
 * already saved there with the same SHA-256 is not written again.
 */
internal object SubtitleStore {
    const val ROOT = "Nuvio/Subtitles"
    private lateinit var context: Context
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var index: FontIndex? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    @Serializable
    private data class FontIndex(
        /** Folder key → SHA-256 → the name the font is saved under there. */
        val folders: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
    )

    class Written(val name: String, val reused: Boolean, val location: String)

    /** Where an episode's files go: show, season and episode folders under the root. */
    fun episodeFolders(show: String, season: Int?, episode: Int?, episodeTitle: String?): List<String> = buildList {
        add(safe(show).ifBlank { "Nuvio" })
        if (season != null) add("Temporada ${season.toString().padStart(2, '0')}")
        if (episode != null) add("Episodio ${episode.toString().padStart(2, '0')}")
    }

    /** The fonts folder of a season (or of a film), shared by its episodes. */
    fun fontFolders(show: String, season: Int?): List<String> = buildList {
        add(safe(show).ifBlank { "Nuvio" })
        if (season != null) add("Temporada ${season.toString().padStart(2, '0')}")
        add("Fonts")
    }

    suspend fun writeFile(treeUri: String?, folders: List<String>, name: String, bytes: ByteArray): Written = withContext(Dispatchers.IO) {
        if (treeUri != null) writeTree(treeUri, folders, name, bytes) else writeDownloads(folders, name, bytes)
    }

    /** Saves a font unless the same bytes are already in [folders]; name clashes with other bytes get a number. */
    suspend fun writeFont(treeUri: String?, folders: List<String>, originalName: String, bytes: ByteArray, sha256: String): Written = lock.withLock {
        val index = loadIndex()
        val key = (treeUri ?: "downloads") + "|" + folders.joinToString("/")
        val folder = index.folders.getOrPut(key) { mutableMapOf() }
        folder[sha256]?.let { saved ->
            if (exists(treeUri, folders, saved)) return@withLock Written(saved, true, location(treeUri, folders, saved))
        }
        val base = safe(originalName).ifBlank { "font-${sha256.take(8)}" }
        val stem = base.substringBeforeLast('.', base)
        val ext = base.substringAfterLast('.', "")
        var name = base
        var n = 2
        while (true) {
            val taken = folder.any { it.value == name && it.key != sha256 }
            if (!taken) {
                // A file of that name the index doesn't know (saved before, or by hand): the same font only if its bytes are.
                val onDisk = if (exists(treeUri, folders, name)) read(treeUri, folders, name) else null
                if (onDisk == null) break
                if (com.nuvio.app.features.subtitledownloader.engine.SubtitleSession.sha256Hex(onDisk) == sha256) {
                    folder[sha256] = name
                    saveIndex(index)
                    return@withLock Written(name, true, location(treeUri, folders, name))
                }
            }
            name = "$stem ($n)" + if (ext.isNotEmpty() && ext != base) ".$ext" else ""
            n++
        }
        val written = writeFile(treeUri, folders, name, bytes)
        folder[sha256] = written.name
        saveIndex(index)
        written
    }

    fun displayLanguage(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "Desconocido"
        val base = code.substringBefore('-').lowercase()
        val two = if (base.length == 3) Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language }.getOrNull() == base } ?: base else base
        val locale = Locale.forLanguageTag(code.replace('_', '-')).takeIf { it.language.isNotEmpty() && base.length == 2 } ?: Locale(two)
        val name = locale.getDisplayLanguage(locale).takeIf { it.isNotBlank() && it != two } ?: code
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    fun safe(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trimEnd('.').take(120)

    private fun writeDownloads(folders: List<String>, name: String, bytes: ByteArray): Written {
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, ROOT) + folders).joinToString("/") + "/"
        if (Build.VERSION.SDK_INT < 29) {
            // Before scoped storage the shared Download folder needs a permission the app doesn't hold.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), (listOf(ROOT) + folders).joinToString("/"))
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            return Written(name, false, File(dir, name).absolutePath)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw java.io.IOException("could not create $name")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("could not write $name")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        // MediaStore may have renamed it on a clash it couldn't see.
        val stored = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
        return Written(stored, false, relative + stored)
    }

    private fun writeTree(treeUri: String, folders: List<String>, name: String, bytes: ByteArray): Written {
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.takeIf { it.canWrite() }
            ?: throw java.io.IOException("the chosen folder can't be written")
        for (folder in folders) {
            dir = dir.findFile(folder)?.takeIf { it.isDirectory } ?: dir.createDirectory(folder) ?: throw java.io.IOException("could not create $folder")
        }
        val file = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: throw java.io.IOException("could not create $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("could not write $name")
        return Written(file.name ?: name, false, folders.joinToString("/") + "/" + (file.name ?: name))
    }

    private fun exists(treeUri: String?, folders: List<String>, name: String): Boolean = runCatching {
        if (treeUri != null) {
            var dir = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false
            for (folder in folders) dir = dir.findFile(folder) ?: return false
            return dir.findFile(name)?.length()?.let { it > 0 } == true
        }
        if (Build.VERSION.SDK_INT < 29) {
            return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), (listOf(ROOT) + folders + name).joinToString("/")).length() > 0
        }
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, ROOT) + folders).joinToString("/") + "/"
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { c -> c.moveToFirst() && c.getLong(0) > 0 } == true
    }.getOrDefault(false)

    private fun read(treeUri: String?, folders: List<String>, name: String): ByteArray? = runCatching {
        if (treeUri != null) {
            var dir = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
            for (folder in folders) dir = dir.findFile(folder) ?: return null
            val file = dir.findFile(name) ?: return null
            return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }
        if (Build.VERSION.SDK_INT < 29) {
            return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), (listOf(ROOT) + folders + name).joinToString("/")).readBytes()
        }
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, ROOT) + folders).joinToString("/") + "/"
        val uri = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null }
            ?: return null
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun location(treeUri: String?, folders: List<String>, name: String): String =
        if (treeUri != null) folders.joinToString("/") + "/" + name
        else (listOf(Environment.DIRECTORY_DOWNLOADS, ROOT) + folders + name).joinToString("/")

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "ass", "ssa" -> "text/x-ssa"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun indexFile() = File(context.filesDir, "subtitle_downloader/fonts_index.json")

    private fun loadIndex(): FontIndex = index ?: runCatching {
        json.decodeFromString(FontIndex.serializer(), indexFile().readText())
    }.getOrElse { FontIndex() }.also { index = it }

    private fun saveIndex(value: FontIndex) {
        runCatching {
            indexFile().parentFile?.mkdirs()
            indexFile().writeText(json.encodeToString(FontIndex.serializer(), value))
        }
    }
}
