package com.nuvio.app.features.player.shaders

import androidx.compose.runtime.Composable
import com.nuvio.app.core.logging.InAppLogger
import kotlin.random.Random

private const val TAG = "Player/Shaders"

/** Where imported shaders live on the device. */
internal expect object ImportedShaderFiles {
    /** Writes [prepared] (and its forced variant) under [storedName]; false when it could not. */
    fun save(storedName: String, prepared: PreparedShader): Boolean

    fun delete(storedName: String)

    fun resolve(shader: ImportedShader, forced: Boolean): ChainShader?
}

/** The pickers of the settings' "Own shaders" section; null where shaders are not offered. */
class ShaderLibraryActions(
    val importFiles: () -> Unit,
    val importFolder: () -> Unit,
    val exportChains: () -> Unit,
    val importChains: () -> Unit,
)

/** [onMessage] gets the outcome of each action, ready to show. */
@Composable
internal expect fun rememberShaderLibraryActions(onMessage: (String) -> Unit): ShaderLibraryActions?

/** What one import of files or a folder did. */
data class ShaderImportSummary(
    val imported: List<String> = emptyList(),
    val rejected: List<Pair<String, ShaderImportProblem>> = emptyList(),
)

/** Bundled and imported shaders together: names, traits and files for the chain. */
object ShaderLibrary {
    fun title(id: String): String {
        val base = baseId(id)
        return ShaderCatalog.shader(base)?.title ?: imported(base)?.title ?: base
    }

    fun traits(id: String): ShaderPresets.ShaderTraits? =
        ShaderPresets.bundledTraits(id) ?: imported(id)?.let(ShaderImport::traits)

    fun resolve(entry: ChainEntry): ChainShader? {
        ShaderCatalog.shader(entry.shaderId)?.let { return ShaderFiles.resolve(it, entry.forced) }
        return imported(entry.shaderId)?.let { ImportedShaderFiles.resolve(it, entry.forced) }
    }

    fun imported(id: String): ImportedShader? = ShaderSettingsRepository.settings.value.imported.firstOrNull { it.id == id }

    /** "imported:ab12#forced@2" → "imported:ab12". */
    fun baseId(chainId: String): String = chainId.substringBefore('@').substringBefore('#')

    /**
     * Imports one file: checked, optimized for mobile, given an "Apply always" variant when it is
     * an upscaler, stored in the app's folder and listed in the settings.
     */
    fun importShader(fileName: String, sizeBytes: Long, content: String?): ShaderImportCheck {
        val check = ShaderImport.check(fileName, sizeBytes, content)
        if (check !is ShaderImportCheck.Accepted || content == null) return check
        val id = ShaderImport.idFor(fileName, content)
        val stored = ShaderImport.storedFileName(id, fileName)
        val prepared = ShaderInstall.prepare(content, check.upscaler)
        if (!ImportedShaderFiles.save(stored, prepared)) return ShaderImportCheck.Rejected(ShaderImportProblem.Empty)
        val shader = ImportedShader(
            id = id,
            title = check.title,
            fileName = stored,
            upscaler = check.upscaler,
            doublesResolution = check.doublesResolution,
            compute = check.compute,
        )
        ShaderSettingsRepository.update { settings -> settings.copy(imported = settings.imported.filterNot { it.id == id } + shader) }
        InAppLogger.info(TAG, "imported shader upscaler=${check.upscaler} doubles=${check.doublesResolution} compute=${check.compute}")
        return check
    }

    fun remove(id: String) {
        val shader = imported(id) ?: return
        ImportedShaderFiles.delete(shader.fileName)
        ShaderSettingsRepository.update { settings ->
            settings.copy(
                imported = settings.imported.filterNot { it.id == id },
                chains = settings.chains.map { it.copy(shaderIds = it.shaderIds - id) }.filter { it.shaderIds.isNotEmpty() },
            )
        }
        ShaderController.updateInputs { inputs -> inputs.copy(liveChain = inputs.liveChain?.minus(id)) }
    }

    /** An imported shader mpv could not run is taken out of the library. */
    fun onFailed(chainId: String): String? {
        val id = baseId(chainId)
        if (!id.startsWith(ShaderImport.PREFIX)) return null
        val title = imported(id)?.title ?: return null
        remove(id)
        InAppLogger.warn(TAG, "imported shader removed after failing in mpv")
        return title
    }

    fun deleteChain(id: String) = ShaderSettingsRepository.update { settings -> settings.copy(chains = settings.chains.filterNot { it.id == id }) }

    fun exportChains(): String = ShaderChainFiles.export(ShaderSettingsRepository.settings.value.chains)

    /** Adds the chains of [text]; the number added, or null when it is not a chain file. */
    fun importChains(text: String): Int? {
        val settings = ShaderSettingsRepository.settings.value
        val added = ShaderChainFiles.import(
            text = text,
            known = { traits(it) != null },
            existing = settings.chains,
            newId = ::newChainId,
        ) ?: return null
        if (added.isNotEmpty()) ShaderSettingsRepository.update { it.copy(chains = it.chains + added) }
        InAppLogger.info(TAG, "imported ${added.size} shader chains")
        return added.size
    }

    fun newChainId(): String = "c" + Random.nextLong(1L shl 40).toString(36)
}
