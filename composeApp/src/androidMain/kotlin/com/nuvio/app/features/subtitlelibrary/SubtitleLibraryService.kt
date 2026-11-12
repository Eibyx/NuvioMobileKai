package com.nuvio.app.features.subtitlelibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.SharedOkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "Subtitles/Folders"

/** Reads only the header, the table directory and the `name` table of a font file. */
internal class FileFontBytes(private val file: RandomAccessFile) : FontBytes {
    override val size: Long = file.length()
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "out of range" }
        val out = ByteArray(length)
        file.seek(offset)
        file.readFully(out)
        return out
    }
}

internal fun SfntNames.read(file: File): List<FontFaceNames> =
    runCatching { RandomAccessFile(file, "r").use { read(FileFontBytes(it)) } }.getOrDefault(emptyList())

/**
 * The user's subtitle folders, searched on demand from the player (no background work): finds the
 * episode's subtitle in each enabled root, copies it and the fonts it really uses into the cache,
 * and remembers listings for ten minutes. Nothing here writes to a folder or to the cloud.
 *
 * Ported from NuvioArcKai's SubtitleLibraryService (GPL-3.0) for Mobile: settings per profile in
 * [SubtitleLibraryStore], cloud through [com.nuvio.app.features.cloudstorage.CloudStorageRepository],
 * local folders through the Storage Access Framework only.
 */
internal object SubtitleLibraryService {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val listings = LinkedHashMap<String, CachedListing>()
    private val faceIndex = LinkedHashMap<String, List<FontFaceNames>>()
    private var context: Context? = null
    private var diskCache: LibraryDiskCache? = null
    private var listingDir: File? = null
    private val client by lazy { SharedOkHttpClient.base.newBuilder().followRedirects(true).followSslRedirects(true).build() }

    private const val LISTING_TTL_MS = 10 * 60 * 1000L
    private const val FORCED_RELIST_MIN_AGE_MS = 60 * 1000L
    private const val MAX_MEMORY_LISTINGS = 64
    private const val MAX_FACE_INDEX = 256
    private const val MAX_CANDIDATES_PER_ROOT = 4
    private const val MAX_SEASON_FOLDERS = 12
    private const val MAX_SUBTITLE_BYTES = 50L * 1024 * 1024
    /** Fonts downloaded to find the used ones in one folder, at most. */
    private const val MAX_FONT_FETCH_BYTES = 80L * 1024 * 1024
    private const val FONT_FETCH_PARALLELISM = 4

    @Serializable
    private class CachedListing(val atEpochMs: Long, val entries: List<LibraryEntry>)

    fun initialize(context: Context) {
        val app = context.applicationContext
        this.context = app
        listingDir = File(app.cacheDir, "kai-subtitle-library-listings")
        diskCache = LibraryDiskCache(
            root = File(app.cacheDir, "kai-subtitle-library"),
            maxBytes = { SubtitleLibraryStore.current().cacheLimitMb.toLong() * 1024 * 1024 },
        )
    }

    private fun requireContext(): Context = context ?: throw LibrarySourceException(LibraryUnavailable.FAILED, "not initialized")

    private fun cache(): LibraryDiskCache = diskCache ?: throw LibrarySourceException(LibraryUnavailable.FAILED, "not initialized")

    internal fun sourceFor(folder: SubtitleFolder): LibrarySource? = when (folder.originKind) {
        SubtitleOrigin.TERABOX, SubtitleOrigin.DRIVE -> CloudLibrarySource(folder, client)
        SubtitleOrigin.LOCAL -> SafLibrarySource(folder, requireContext())
        null -> null
    }

    // ---- Listings -------------------------------------------------------------------------------

    internal suspend fun list(source: LibrarySource, folderId: String, folderPath: String, force: Boolean = false): List<LibraryEntry> {
        val key = "${source.folder.id}|$folderId"
        val now = System.currentTimeMillis()
        // force re-reads a listing older than a minute; a fresher one is as good as a new read.
        val maxAge = if (force) FORCED_RELIST_MIN_AGE_MS else LISTING_TTL_MS
        run {
            synchronized(lock) { listings[key] }?.takeIf { now - it.atEpochMs < maxAge }?.let { return it.entries }
            readListing(key)?.takeIf { now - it.atEpochMs < maxAge }?.let { cached ->
                synchronized(lock) { listings[key] = cached }
                return cached.entries
            }
        }
        val entries = source.list(folderId, folderPath)
        val cached = CachedListing(now, entries)
        synchronized(lock) {
            listings[key] = cached
            while (listings.size > MAX_MEMORY_LISTINGS) listings.remove(listings.keys.first())
        }
        writeListing(key, cached)
        return entries
    }

    fun invalidate(rootId: String?) {
        synchronized(lock) {
            if (rootId == null) listings.clear() else listings.keys.removeAll { it.startsWith("$rootId|") }
        }
        listingDir?.listFiles()?.forEach { file ->
            if (rootId == null || file.name.startsWith(hash(rootId).take(12))) file.delete()
        }
    }

    private fun listingFile(key: String): File? =
        listingDir?.let { File(it, "${hash(key.substringBefore('|')).take(12)}-${hash(key).take(24)}.json") }

    private fun readListing(key: String): CachedListing? = runCatching {
        val file = listingFile(key)?.takeIf { it.isFile } ?: return null
        json.decodeFromString(CachedListing.serializer(), file.readText())
    }.getOrNull()

    private fun writeListing(key: String, listing: CachedListing) {
        runCatching {
            val file = listingFile(key) ?: return
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.part")
            temporary.writeText(json.encodeToString(CachedListing.serializer(), listing))
            temporary.renameTo(file)
        }
    }

    // ---- Search ---------------------------------------------------------------------------------

    suspend fun search(query: LibraryQuery): LibrarySearchResult = withContext(Dispatchers.IO) {
        val settings = SubtitleLibraryStore.current()
        val roots = settings.enabledFolders
        if (roots.isEmpty() || query.episode == null && query.titles.isEmpty()) return@withContext LibrarySearchResult()
        val subtitles = mutableListOf<PreparedLibrarySubtitle>()
        val unavailable = mutableListOf<Pair<SubtitleFolder, LibraryUnavailable>>()
        val needsLink = mutableListOf<SubtitleFolder>()
        val animeFolders = mutableMapOf<String, LibraryEntry>()
        for (root in roots) {
            coroutineContext.ensureActive()
            val source = sourceFor(root) ?: continue
            try {
                source.checkAvailable()
                val pick = query.episodeKey?.let { settings.picks[it] }?.takeIf { it.rootId == root.id }
                if (pick != null) {
                    val entry = LibraryEntry(pick.fileId, pick.fileName, false, pick.sizeBytes, pick.modifiedEpochMs, pick.filePath)
                    val folder = LibraryEntry(pick.folderEntryId, "", true, path = pick.folderPath)
                    subtitles += prepare(source, EpisodeSubtitle(entry, emptyList(), folder), query, picked = true)
                    continue
                }
                val link = query.seriesKey?.let { settings.links[it] }?.takeIf { it.rootId == root.id }
                var match = LibraryMatcher.animeFolder(list(source, source.rootId, root.folderPath), query.titles, link?.folderEntryId)
                if (match !is AnimeFolderMatch.Found) {
                    // The series folder may have been uploaded after the listing was cached: read it again.
                    match = LibraryMatcher.animeFolder(list(source, source.rootId, root.folderPath, force = true), query.titles, link?.folderEntryId)
                    InAppLogger.info(TAG, "${root.origin} series folder not in the cached listing; read again: ${match::class.simpleName}")
                }
                when (match) {
                    is AnimeFolderMatch.Found -> {
                        animeFolders[root.id] = match.folder
                        fun episodeCandidates(files: List<EpisodeSubtitle>) =
                            LibraryMatcher.episodeSubtitles(files, query.season, query.episode, query.absoluteEpisode)
                        var candidates = episodeCandidates(episodeTree(source, match.folder, root))
                        if (candidates.isEmpty()) {
                            // The episode's subtitle may be new: read the series folder again.
                            candidates = episodeCandidates(episodeTree(source, match.folder, root, force = true))
                            InAppLogger.info(TAG, "${root.origin} episode not in the cached listing; read again: ${candidates.size} found")
                        }
                        candidates.take(MAX_CANDIDATES_PER_ROOT)
                            .forEach { candidate -> subtitles += prepare(source, candidate, query, picked = false) }
                    }
                    is AnimeFolderMatch.Ambiguous -> needsLink += root
                    AnimeFolderMatch.None -> needsLink += root
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LibrarySourceException) {
                InAppLogger.info(TAG, "${root.origin} folder unavailable: ${error.reason}")
                unavailable += root to error.reason
            } catch (error: Exception) {
                InAppLogger.warn(TAG, "${root.origin} folder not read: ${error::class.java.simpleName} ${LibraryRedaction.text(error.message)}")
                unavailable += root to LibraryUnavailable.FAILED
            }
        }
        InAppLogger.info(TAG, "search found ${subtitles.size} subtitles; unavailable=${unavailable.size} needsLink=${needsLink.size}")
        LibrarySearchResult(subtitles, unavailable, needsLink, animeFolders)
    }

    /** Subtitles in the anime folder and in its season folders (`Temporada 2`, `Season 2`, `S2`). */
    private suspend fun episodeTree(
        source: LibrarySource,
        anime: LibraryEntry,
        root: SubtitleFolder,
        force: Boolean = false,
    ): List<EpisodeSubtitle> {
        val entries = list(source, anime.id, anime.path.ifEmpty { "${root.folderPath.trimEnd('/')}/${anime.name}" }, force)
        val here = entries.filter { !it.isFolder && LibraryMatcher.isSubtitleName(it.name) }
            .map { EpisodeSubtitle(it, emptyList(), anime) }
        val seasons = entries.filter { it.isFolder && LibraryMatcher.seasonOfFolder(it.name) != null }
            .take(MAX_SEASON_FOLDERS)
            .flatMap { season ->
                list(source, season.id, season.path, force)
                    .filter { !it.isFolder && LibraryMatcher.isSubtitleName(it.name) }
                    .map { EpisodeSubtitle(it, listOf(season.name), season) }
            }
        return here + seasons
    }

    suspend fun browse(root: SubtitleFolder, folderId: String?, folderPath: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val source = sourceFor(root) ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        source.checkAvailable()
        list(source, folderId ?: source.rootId, folderPath)
            .filter { it.isFolder || LibraryMatcher.isSubtitleName(it.name) }
            .sortedWith(compareByDescending<LibraryEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    suspend fun preparePicked(root: SubtitleFolder, folder: LibraryEntry, file: LibraryEntry, query: LibraryQuery): PreparedLibrarySubtitle =
        withContext(Dispatchers.IO) {
            val source = sourceFor(root) ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
            val prepared = prepare(source, EpisodeSubtitle(file, emptyList(), folder), query, picked = true)
            query.episodeKey?.let { key ->
                SubtitleLibraryStore.pick(
                    key,
                    SubtitleFilePick(root.id, file.id, file.name, folder.id, folder.path, file.path, file.sizeBytes, file.modifiedEpochMs),
                )
            }
            prepared
        }

    // ---- Download and fonts ---------------------------------------------------------------------

    private suspend fun prepare(
        source: LibrarySource,
        candidate: EpisodeSubtitle,
        query: LibraryQuery,
        picked: Boolean,
    ): PreparedLibrarySubtitle {
        val root = source.folder
        val original = fetch(source, candidate.file, MAX_SUBTITLE_BYTES)
        val bytes = original.readBytes()
        val isAss = LibraryMatcher.isAssName(candidate.file.name)
        // Text parsers (ExoPlayer) expect UTF-8; mpv reads either. One UTF-8 copy serves both.
        val file = if (AssFontNames.isUtf16(bytes)) utf8Copy(original, AssFontNames.decode(bytes)) else original
        var fonts = emptyList<LibraryFont>()
        var missing = emptySet<String>()
        var scriptLanguage: String? = null
        if (isAss) {
            val script = AssFontNames.decode(bytes)
            scriptLanguage = AssFontNames.scriptLanguage(script)
            val used = AssFontNames.usedFontNames(script)
            if (used.isNotEmpty()) {
                val plan = fontsFor(source, candidate.folder, used)
                fonts = plan.first
                missing = plan.second
            }
        }
        val language = LibraryMatcher.languageOf(candidate.file.name)
            ?: scriptLanguage?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
            // The user's own folders: an untagged file is taken to be in their preferred language.
            ?: query.preferredLanguage?.takeIf { it.isNotBlank() && it != "none" }
            ?: "und"
        InAppLogger.info(TAG, "prepared a ${root.origin} subtitle (${fonts.size} fonts, ${missing.size} missing)")
        return PreparedLibrarySubtitle(root, candidate.file.name, candidate.file.id, language, file.absolutePath, isAss, fonts, missing, picked)
    }

    private fun utf8Copy(original: File, text: String): File {
        val target = File(original.parentFile, original.nameWithoutExtension + ".u8." + original.extension)
        if (target.isFile && target.length() > 0 && target.lastModified() >= original.lastModified()) return target
        val temporary = File(target.parentFile, target.name + ".part")
        temporary.writeText(text.removePrefix("﻿"), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return original
        }
        return target
    }

    /** Fonts of the subtitle's own folder (and its Fonts/Fuentes/Attachments folder) that the script uses. */
    private suspend fun fontsFor(source: LibrarySource, folder: LibraryEntry, used: Set<String>): Pair<List<LibraryFont>, Set<String>> {
        val siblings = runCatching { list(source, folder.id, folder.path) }
            .getOrElse { if (it is CancellationException) throw it; emptyList() }
        val fontEntries = siblings.filter { !it.isFolder && SfntNames.isFontName(it.name) } +
            siblings.filter { it.isFolder && LibraryMatcher.isFontFolder(it.name) }.flatMap { sub ->
                runCatching { list(source, sub.id, sub.path) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                    .filter { !it.isFolder && SfntNames.isFontName(it.name) }
            }
        if (fontEntries.isEmpty()) return emptyList<LibraryFont>() to used
        val limits = FontLimits()
        val byName = fontEntries.associateBy { it.name }
        val ordered = FontSelection.fetchOrder(used, fontEntries.map { it.name }).mapNotNull { byName[it] }
        val candidates = mutableListOf<FontCandidate>()
        val files = mutableMapOf<String, File>()
        val readFaces = mutableListOf<FontFaceNames>()
        var fetched = 0L
        // Fetched a few at a time: a cloud font needs its own download link and request.
        var index = 0
        while (index < ordered.size) {
            coroutineContext.ensureActive()
            // Once every name is found, only files that look like a used family (its bold, its italic) follow.
            val covered = FontSelection.allCovered(used, readFaces)
            val batch = mutableListOf<LibraryEntry>()
            var budget = fetched
            while (index < ordered.size && batch.size < FONT_FETCH_PARALLELISM) {
                val entry = ordered[index]
                if (covered && !FontSelection.looksLike(used, entry.name)) {
                    index = ordered.size
                    break
                }
                index++
                if (entry.sizeBytes > limits.maxFileBytes) continue
                if (budget + entry.sizeBytes > MAX_FONT_FETCH_BYTES) {
                    index = ordered.size
                    break
                }
                budget += entry.sizeBytes
                batch += entry
            }
            if (batch.isEmpty()) break
            val downloaded = coroutineScope {
                batch.map { entry ->
                    async {
                        entry to runCatching { fetch(source, entry, limits.maxFileBytes) }
                            .getOrElse { if (it is CancellationException) throw it; null }
                    }
                }.awaitAll()
            }
            for ((entry, file) in downloaded) {
                file ?: continue
                fetched += file.length()
                val faces = synchronized(lock) { faceIndex[file.name] } ?: SfntNames.read(file).also { faces ->
                    synchronized(lock) {
                        faceIndex[file.name] = faces
                        while (faceIndex.size > MAX_FACE_INDEX) faceIndex.remove(faceIndex.keys.first())
                    }
                }
                readFaces += faces
                files[file.name] = file
                candidates += FontCandidate(file.name, entry.name, file.length(), faces.ifEmpty { null })
            }
        }
        val plan = FontSelection.plan(used, candidates, limits)
        return plan.selected.mapNotNull { selected -> files[selected.key]?.let { LibraryFont(selected.fileName, it.absolutePath) } } to plan.missing
    }

    /** The cached copy of [entry], downloaded or copied when it changed or is not cached yet. */
    private suspend fun fetch(source: LibrarySource, entry: LibraryEntry, maxBytes: Long): File {
        val diskCache = cache()
        val key = diskCache.keyFor("${source.folder.id}|${entry.id}", entry.sizeBytes, entry.modifiedEpochMs, entry.name)
        diskCache.get(source.folder.origin, key)?.let { return it }
        if (entry.sizeBytes > maxBytes) throw IOException("file too large")
        return withContext(Dispatchers.IO) {
            source.open(entry).use { input ->
                diskCache.put(source.folder.origin, key) { output -> copyCapped(input, output, maxBytes) }
            }
        }
    }

    /** True for a `file://` URL of a subtitle prepared from the user's subtitle folders. */
    fun isLibraryUrl(url: String?): Boolean {
        val root = context?.let { File(it.cacheDir, "kai-subtitle-library").absolutePath + "/" } ?: return false
        return url != null && url.removePrefix("file://").startsWith(root)
    }

    fun cacheBytes(): Long = diskCache?.totalBytes() ?: 0L

    fun clearCache() {
        diskCache?.clear()
        invalidate(null)
        synchronized(lock) { faceIndex.clear() }
    }

    fun release(folder: SubtitleFolder) {
        if (folder.originKind != SubtitleOrigin.LOCAL) return
        runCatching {
            requireContext().contentResolver.releasePersistableUriPermission(Uri.parse(folder.folderId), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        invalidate(folder.id)
    }

    private fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("file too large")
            output.write(buffer, 0, read)
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
