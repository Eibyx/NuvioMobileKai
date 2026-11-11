package com.nuvio.app.features.subtitlelibrary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nuvio.app.features.cloudstorage.CloudFailure
import com.nuvio.app.features.cloudstorage.CloudFile
import com.nuvio.app.features.cloudstorage.CloudFolderRef
import com.nuvio.app.features.cloudstorage.CloudStorageException
import com.nuvio.app.features.cloudstorage.CloudStorageRepository
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class LibrarySourceException(val reason: LibraryUnavailable, message: String? = null) : IOException(message)

/** Read-only access to one configured root, whatever the origin. Folder ids are origin-specific. */
internal interface LibrarySource {
    val folder: SubtitleFolder
    /** The root's own folder id, as [list] takes it. */
    val rootId: String get() = folder.folderId
    suspend fun list(folderId: String, folderPath: String): List<LibraryEntry>
    /** Opens a file for reading; the caller closes it. */
    suspend fun open(entry: LibraryEntry): InputStream
    /** Throws [LibrarySourceException] when the root cannot be read now (USB unplugged…). */
    suspend fun checkAvailable()
}

/**
 * TeraBox and Google Drive through the accounts already connected in Cloud storage; nothing is
 * written to the cloud and the accounts' video index is not touched. Ported from NuvioArcKai (GPL-3.0).
 */
internal class CloudLibrarySource(
    override val folder: SubtitleFolder,
    private val client: OkHttpClient,
) : LibrarySource {
    private val accountId: String
        get() = folder.accountId ?: throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)

    override suspend fun checkAvailable() {
        CloudStorageRepository.ensureLoaded()
        if (CloudStorageRepository.account(accountId) == null || CloudStorageRepository.provider(accountId) == null) {
            throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        }
    }

    override suspend fun list(folderId: String, folderPath: String): List<LibraryEntry> = try {
        CloudStorageRepository.listAll(accountId, CloudFolderRef(id = folderId, path = folderPath.ifEmpty { folderId }))
            .map { item ->
                LibraryEntry(
                    id = item.id,
                    name = item.name,
                    isFolder = item.isFolder,
                    sizeBytes = item.sizeBytes,
                    modifiedEpochMs = item.modifiedEpochSec * 1000,
                    path = item.path,
                )
            }
    } catch (error: CloudStorageException) {
        throw if (error.failure == CloudFailure.NOT_FOUND || error.failure == CloudFailure.AUTH) {
            LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        } else {
            LibrarySourceException(LibraryUnavailable.FAILED, error.failure.name)
        }
    }

    override suspend fun open(entry: LibraryEntry): InputStream = withContext(Dispatchers.IO) {
        val provider = CloudStorageRepository.provider(accountId)
            ?: throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        val file = CloudFile(
            id = entry.id,
            path = entry.path,
            name = entry.name,
            sizeBytes = entry.sizeBytes,
            modifiedEpochSec = entry.modifiedEpochMs / 1000,
            parentPath = entry.path.substringBeforeLast('/', ""),
        )
        var attempt = 0
        while (true) {
            val source = provider.resolve(file, forceRefresh = attempt > 0)
            val request = Request.Builder().url(source.url).apply { source.headers.forEach { (k, v) -> header(k, v) } }.build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) return@withContext response.body.byteStream()
            val code = response.code
            response.close()
            // An expired link: one more try with a fresh one.
            if ((code == 403 || code == 401 || code == 410) && attempt == 0) {
                attempt++
                continue
            }
            throw IOException("HTTP $code")
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }
}

/**
 * A tree picked with the system's document picker: internal storage, an SD card or a USB drive.
 * Ported from NuvioArcKai (GPL-3.0).
 */
internal class SafLibrarySource(
    override val folder: SubtitleFolder,
    private val context: Context,
) : LibrarySource {
    private val treeUri: Uri = Uri.parse(folder.folderId)
    private val rootDocumentId: String? = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

    override suspend fun checkAvailable() = withContext(Dispatchers.IO) {
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!granted) throw LibrarySourceException(LibraryUnavailable.PERMISSION_MISSING)
        val documentId = rootDocumentId ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        val readable = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        if (!readable) throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
    }

    override suspend fun list(folderId: String, folderPath: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val documentId = (if (folderId == folder.folderId) rootDocumentId else folderId)
            ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(children, columns, null, null, null)
        } catch (error: Exception) {
            throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED, error::class.java.simpleName)
        } ?: throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    add(
                        LibraryEntry(
                            id = id,
                            name = it.getString(1) ?: continue,
                            isFolder = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            sizeBytes = if (it.isNull(3)) 0 else it.getLong(3),
                            modifiedEpochMs = if (it.isNull(4)) 0 else it.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun open(entry: LibraryEntry): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id))
            ?: throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
    }
}
