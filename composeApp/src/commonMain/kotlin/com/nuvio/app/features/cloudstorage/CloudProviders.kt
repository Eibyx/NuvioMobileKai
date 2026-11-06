package com.nuvio.app.features.cloudstorage

import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** The index of one account: the video files under its selected folders. */
@Serializable
internal data class CloudIndexSnapshot(
    val files: List<CloudFile> = emptyList(),
    val folderIds: List<String> = emptyList(),
    /** Drive: the changes page token to continue from. */
    val cursor: String? = null,
    /** Drive: id → path of every indexed folder, to place changed files. */
    val folderPaths: Map<String, String> = emptyMap(),
    val builtAtEpochMs: Long = 0L,
)

internal interface CloudProvider {
    /** Checks the session and returns a label for the account (e-mail, or null). */
    suspend fun verify(): String?

    /** A folder's subfolders and videos, for the folder picker. Null is the root. */
    suspend fun browse(folder: CloudFolderRef?): List<CloudEntry>

    /** Indexes [folders]; reuses [previous] when the provider can tell what changed. */
    suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot

    /** Where to read [file] from right now. [forceRefresh] asks for a new link after a failure. */
    suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource

    /** Revokes what can be revoked at the provider. Local secrets are deleted by the caller. */
    suspend fun disconnect() = Unit
}

internal const val CLOUD_INDEX_MAX_FILES = 20_000
private const val MAX_FOLDERS = 3_000
private const val MAX_DEPTH = 12

internal class TeraBoxCloudProvider(
    private val api: TeraBoxApi,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CloudProvider {
    private val links = mutableMapOf<String, Pair<String, Long>>()

    override suspend fun verify(): String? {
        api.checkLogin()
        return null
    }

    override suspend fun browse(folder: CloudFolderRef?): List<CloudEntry> =
        api.list(folder?.path ?: "/")
            .filter { it.isDir || it.name.isCloudVideoName() }
            .map { CloudEntry(id = it.path, path = it.path, name = it.name, isFolder = it.isDir, sizeBytes = it.sizeBytes) }
            .sortedWith(compareByDescending<CloudEntry> { it.isFolder }.thenBy { it.name.lowercase() })

    /** TeraBox has no change feed, so every sync walks the selected folders again. */
    override suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot {
        val files = mutableListOf<CloudFile>()
        val queue = ArrayDeque(folders.map { teraBoxPath(it.path) to 0 })
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty() && files.size < CLOUD_INDEX_MAX_FILES && seen.size < MAX_FOLDERS) {
            val (path, depth) = queue.removeFirst()
            if (!seen.add(path)) continue
            api.list(path).forEach { item ->
                when {
                    item.isDir -> if (depth < MAX_DEPTH) queue.addLast(item.path to depth + 1)
                    item.category == 1 || item.name.isCloudVideoName() -> files += CloudFile(
                        id = item.fsId.toString(),
                        path = item.path,
                        name = item.name,
                        sizeBytes = item.sizeBytes,
                        modifiedEpochSec = item.modifiedEpochSec,
                        parentPath = item.path.substringBeforeLast('/', ""),
                        mimeType = cloudMimeTypeFor(item.name),
                    )
                }
            }
        }
        return CloudIndexSnapshot(files = files, folderIds = folders.map { it.id }, builtAtEpochMs = nowMs())
    }

    /**
     * A download link, as rclone-extra's `Object.Open` gets it, sent with the same headers.
     * Links are reused for a while and replaced on [forceRefresh] (a 403/expired link).
     */
    override suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource {
        val cached = links[file.id]?.takeIf { !forceRefresh && nowMs() - it.second < LINK_REUSE_MS }?.first
        val link = cached ?: api.downloadLink(file.path, file.id.toLongOrNull() ?: throw CloudStorageException(CloudFailure.NOT_FOUND))
            .also { links[file.id] = it to nowMs() }
        return CloudPlaybackSource(url = link, headers = api.requestHeaders, sizeBytes = file.sizeBytes)
    }

    private companion object {
        const val LINK_REUSE_MS = 20 * 60 * 1000L
    }
}

internal class GoogleDriveCloudProvider(
    private val api: GoogleDriveApi,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CloudProvider {
    override suspend fun verify(): String? = api.accountName()

    override suspend fun browse(folder: CloudFolderRef?): List<CloudEntry> {
        val parentId = folder?.id ?: "root"
        val parentPath = folder?.path?.trimEnd('/') ?: ""
        return api.children(parentId)
            .filter { it.isFolder || it.name.isCloudVideoName() || it.mimeType.startsWith("video/") }
            .map { CloudEntry(id = it.id, path = "$parentPath/${it.name}", name = it.name, isFolder = it.isFolder, sizeBytes = it.sizeBytes) }
            .sortedWith(compareByDescending<CloudEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    override suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot {
        val folderIds = folders.map { it.id }
        if (previous?.cursor != null && previous.folderIds == folderIds) {
            incremental(previous)?.let { return it }
        }
        return full(folders)
    }

    private suspend fun full(folders: List<CloudFolderRef>): CloudIndexSnapshot {
        // Take the change token first, so nothing that changes during the walk is missed later.
        val cursor = runCatching { api.startPageToken() }.getOrNull()
        val files = mutableListOf<CloudFile>()
        val folderPaths = mutableMapOf<String, String>()
        val queue = ArrayDeque(folders.map { Triple(it.id, it.path.trimEnd('/'), 0) })
        while (queue.isNotEmpty() && files.size < CLOUD_INDEX_MAX_FILES && folderPaths.size < MAX_FOLDERS) {
            val (id, path, depth) = queue.removeFirst()
            if (folderPaths.put(id, path) != null) continue
            api.children(id).forEach { item ->
                when {
                    item.isFolder -> if (depth < MAX_DEPTH) queue.addLast(Triple(item.id, "$path/${item.name}", depth + 1))
                    item.isVideo() -> files += item.toCloudFile(path)
                }
            }
        }
        return CloudIndexSnapshot(
            files = files,
            folderIds = folders.map { it.id },
            cursor = cursor,
            folderPaths = folderPaths,
            builtAtEpochMs = nowMs(),
        )
    }

    /** Applies Drive's change feed. Null when a folder moved or changed: then a full walk is safer. */
    private suspend fun incremental(previous: CloudIndexSnapshot): CloudIndexSnapshot? {
        val cursor = previous.cursor ?: return null
        val result = try {
            api.changes(cursor)
        } catch (error: CloudStorageException) {
            if (error.failure == CloudFailure.AUTH) throw error
            return null
        }
        val files = previous.files.associateBy { it.id }.toMutableMap()
        for (change in result.changes) {
            val item = change.file
            if (change.removed || item == null) {
                if (change.fileId in previous.folderPaths) return null
                files.remove(change.fileId)
                continue
            }
            if (item.isFolder) {
                val known = item.id in previous.folderPaths
                val underIndex = item.parents.any { it in previous.folderPaths }
                if (known || underIndex) return null
                continue
            }
            val parentPath = item.parents.firstNotNullOfOrNull { previous.folderPaths[it] }
            if (parentPath != null && item.isVideo()) {
                files[item.id] = item.toCloudFile(parentPath)
            } else {
                files.remove(item.id)
            }
        }
        return previous.copy(files = files.values.toList(), cursor = result.newStartPageToken, builtAtEpochMs = nowMs())
    }

    override suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource =
        CloudPlaybackSource(
            url = api.playbackUrl(file.id),
            headers = mapOf("Authorization" to "Bearer ${api.accessToken(forceRefresh)}"),
            sizeBytes = file.sizeBytes,
        )

    override suspend fun disconnect() = api.revoke()

    private fun DriveItem.isVideo(): Boolean = mimeType.startsWith("video/") || name.isCloudVideoName()

    private fun DriveItem.toCloudFile(parentPath: String) = CloudFile(
        id = id,
        path = "$parentPath/$name",
        name = name,
        sizeBytes = sizeBytes,
        modifiedEpochSec = modifiedEpochSec,
        parentPath = parentPath,
        width = width,
        height = height,
        durationMs = durationMs,
        mimeType = mimeType.takeIf { it.startsWith("video/") } ?: cloudMimeTypeFor(name),
    )
}
