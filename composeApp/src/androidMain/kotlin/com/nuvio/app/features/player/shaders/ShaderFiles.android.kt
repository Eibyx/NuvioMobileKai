package com.nuvio.app.features.player.shaders

import android.content.Context
import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Player/Shaders"
private const val PREFERENCES = "nuvio_shader_files"
private const val KEY_INSTALLED = "installed"

/** Bump when the install processing (precision pass, forced variants) changes. */
private const val INSTALL_VERSION = 1

/**
 * Copies the bundled shaders out of the APK's assets, each optimized for mobile GPUs, with the
 * "Apply always" variant of every upscaler next to it.
 */
internal actual object ShaderFiles {
    private var context: Context? = null
    private val descriptions = ConcurrentHashMap<String, List<String>>()

    actual val isSupported: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installation: Job? = null

    /** Remembers the app context and installs the bundled shaders in the background. */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        if (installation == null) installation = scope.launch { install() }
    }

    /** Waits for the background installation, so a player opening right away finds the files. */
    suspend fun awaitInstalled() {
        installation?.join()
    }

    private fun root(): File? = context?.filesDir?.let { File(it, "shaders") }

    fun bundledFolder(): File? = root()?.let { File(it, "bundled") }

    fun forcedFolder(): File? = root()?.let { File(it, "forced") }

    private fun stamp() = "$INSTALL_VERSION:" + ShaderCatalog.shaders.joinToString(",") { it.file }.hashCode()

    /** Installs the bundled shaders when this app version has not yet. */
    suspend fun install() = withContext(Dispatchers.IO) {
        val context = context ?: return@withContext
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val bundled = bundledFolder() ?: return@withContext
        val forced = forcedFolder() ?: return@withContext
        val complete = ShaderCatalog.shaders.all { File(bundled, it.file).isFile }
        if (preferences.getString(KEY_INSTALLED, null) == stamp() && complete) return@withContext
        bundled.deleteRecursively()
        forced.deleteRecursively()
        bundled.mkdirs()
        forced.mkdirs()
        var installed = 0
        var variants = 0
        for (shader in ShaderCatalog.shaders) {
            runCatching {
                val content = context.assets.open("${ShaderCatalog.ASSET_FOLDER}/${shader.file}").use { it.readBytes().decodeToString() }
                val prepared = ShaderInstall.prepare(content, shader.upscaler)
                File(bundled, shader.file).writeText(prepared.content)
                prepared.forced?.let {
                    File(forced, shader.file).writeText(it)
                    variants++
                }
                installed++
            }.onFailure { InAppLogger.warn(TAG, "bundled shader ${shader.file} not installed: ${InAppLogger.throwableSummary(it)}") }
        }
        descriptions.clear()
        preferences.edit().putString(KEY_INSTALLED, stamp()).apply()
        InAppLogger.info(TAG, "installed $installed bundled shaders ($variants with an Apply always variant), mobile precision applied")
    }

    actual fun resolve(shader: BundledShader, forced: Boolean): ChainShader? {
        val normal = bundledFolder()?.let { File(it, shader.file) }?.takeIf { it.isFile } ?: return null
        val variant = if (forced) forcedFolder()?.let { File(it, shader.file) }?.takeIf { it.isFile } else null
        val file = variant ?: normal
        return ChainShader(
            id = if (variant != null) "${shader.id}#forced" else shader.id,
            title = shader.title,
            path = file.absolutePath,
            passDescriptions = passDescriptions(file),
            upscaler = shader.upscaler,
            chroma = shader.chroma,
            forced = variant != null,
        )
    }

    fun passDescriptions(file: File): List<String> =
        descriptions.getOrPut(file.absolutePath) {
            runCatching { ShaderValidation.passDescriptions(file.readText()) }.getOrDefault(emptyList())
        }
}
