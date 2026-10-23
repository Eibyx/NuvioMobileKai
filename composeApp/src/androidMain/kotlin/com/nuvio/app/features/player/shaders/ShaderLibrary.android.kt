package com.nuvio.app.features.player.shaders

import android.content.Context
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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.shader_chains_export_done
import nuvio.composeapp.generated.resources.shader_chains_export_failed
import nuvio.composeapp.generated.resources.shader_chains_import_done
import nuvio.composeapp.generated.resources.shader_chains_import_invalid
import nuvio.composeapp.generated.resources.shader_import_none
import nuvio.composeapp.generated.resources.shader_import_result
import nuvio.composeapp.generated.resources.shader_import_rejected
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Player/Shaders"

internal actual object ImportedShaderFiles {
    private val descriptions = ConcurrentHashMap<String, List<String>>()

    private fun folder(forced: Boolean): File? =
        ShaderFiles.bundledFolder()?.parentFile?.let { File(it, if (forced) "imported_forced" else "imported") }

    actual fun save(storedName: String, prepared: PreparedShader): Boolean = runCatching {
        val normal = folder(false)?.apply { mkdirs() } ?: return false
        val forced = folder(true)?.apply { mkdirs() } ?: return false
        File(normal, storedName).writeText(prepared.content)
        val variant = File(forced, storedName)
        if (prepared.forced != null) variant.writeText(prepared.forced) else variant.delete()
        descriptions.remove(File(normal, storedName).absolutePath)
        true
    }.getOrElse {
        InAppLogger.warn(TAG, "imported shader not stored: ${InAppLogger.throwableSummary(it)}")
        false
    }

    actual fun delete(storedName: String) {
        folder(false)?.let { File(it, storedName).delete() }
        folder(true)?.let { File(it, storedName).delete() }
    }

    actual fun resolve(shader: ImportedShader, forced: Boolean): ChainShader? {
        val normal = folder(false)?.let { File(it, shader.fileName) }?.takeIf { it.isFile } ?: return null
        val variant = if (forced) folder(true)?.let { File(it, shader.fileName) }?.takeIf { it.isFile } else null
        val file = variant ?: normal
        return ChainShader(
            id = if (variant != null) "${shader.id}#forced" else shader.id,
            title = shader.title,
            path = file.absolutePath,
            passDescriptions = descriptions.getOrPut(file.absolutePath) {
                runCatching { ShaderValidation.passDescriptions(file.readText()) }.getOrDefault(emptyList())
            },
            upscaler = shader.upscaler,
            chroma = false,
            forced = variant != null,
        )
    }
}

@Composable
internal actual fun rememberShaderLibraryActions(onMessage: (String) -> Unit): ShaderLibraryActions? {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)

    fun importAll(files: suspend () -> List<Pair<Uri, String>>) {
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                val imported = mutableListOf<String>()
                val rejected = mutableListOf<Pair<String, ShaderImportProblem>>()
                files().forEach { (uri, name) ->
                    val size = context.sizeOf(uri)
                    val content = if (size in 1..ShaderImport.MAX_BYTES) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                    } else {
                        null
                    }
                    when (val check = ShaderLibrary.importShader(name, size, content)) {
                        is ShaderImportCheck.Accepted -> imported += check.title
                        is ShaderImportCheck.Rejected -> rejected += name to check.problem
                    }
                }
                ShaderImportSummary(imported, rejected)
            }
            InAppLogger.info(TAG, "shader import: ${summary.imported.size} imported, ${summary.rejected.size} skipped")
            message(importMessage(summary))
        }
    }

    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importAll { uris.map { it to (context.displayName(it) ?: it.lastPathSegment.orEmpty()) } }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        importAll { context.shaderFilesIn(tree) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(ShaderLibrary.exportChains().encodeToByteArray()) } != null }
                    .getOrDefault(false)
            }
            message(getString(if (written) Res.string.shader_chains_export_done else Res.string.shader_chains_export_failed))
        }
    }
    val chainsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes().takeIf { it.size <= 1_000_000 }?.decodeToString() }
                }.getOrNull()
                text?.let(ShaderLibrary::importChains)
            }
            message(if (added == null) getString(Res.string.shader_chains_import_invalid) else getString(Res.string.shader_chains_import_done, added))
        }
    }
    return remember(filesLauncher, folderLauncher, exportLauncher, chainsLauncher) {
        ShaderLibraryActions(
            importFiles = { filesLauncher.launch(arrayOf("*/*")) },
            importFolder = { folderLauncher.launch(null) },
            exportChains = { exportLauncher.launch("nuvio-shader-chains.json") },
            importChains = { chainsLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
    }
}

private suspend fun importMessage(summary: ShaderImportSummary): String {
    if (summary.imported.isEmpty() && summary.rejected.isEmpty()) return getString(Res.string.shader_import_none)
    val done = getString(Res.string.shader_import_result, summary.imported.size)
    if (summary.rejected.isEmpty()) return done
    return done + " " + getString(Res.string.shader_import_rejected, summary.rejected.size, summary.rejected.take(3).joinToString(", ") { it.first })
}

private fun Context.displayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun Context.sizeOf(uri: Uri): Long = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull() ?: runCatching { contentResolver.openInputStream(uri)?.use { it.available().toLong() } }.getOrNull() ?: 0L

/** Every .glsl and .hook file in [tree], at any depth. */
private fun Context.shaderFilesIn(tree: Uri): List<Pair<Uri, String>> {
    val found = mutableListOf<Pair<Uri, String>>()
    val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
    val visited = HashSet<String>()
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    while (pending.isNotEmpty()) {
        val folderId = pending.removeFirst()
        if (!visited.add(folderId)) continue
        runCatching {
            contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId), columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    when {
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR -> pending += id
                        ShaderValidation.extensionAllowed(name) -> found += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
            }
        }.onFailure { InAppLogger.warn(TAG, "a shader subfolder could not be listed: ${InAppLogger.throwableSummary(it)}") }
    }
    return found
}
