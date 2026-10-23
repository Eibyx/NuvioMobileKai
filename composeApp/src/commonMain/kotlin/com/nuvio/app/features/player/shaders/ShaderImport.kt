package com.nuvio.app.features.player.shaders

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why a file did not become an imported shader. */
enum class ShaderImportProblem { WrongExtension, TooLarge, Empty, NotAShader }

/** What the importer makes of one file. */
sealed interface ShaderImportCheck {
    data class Accepted(
        val title: String,
        val upscaler: Boolean,
        val doublesResolution: Boolean,
        val compute: Boolean,
    ) : ShaderImportCheck

    data class Rejected(val problem: ShaderImportProblem) : ShaderImportCheck
}

object ShaderImport {
    const val PREFIX = "imported:"
    const val MAX_BYTES = 4L * 1024L * 1024L

    /** `//!WIDTH 2 HOOKED.w *`, `//!WIDTH LUMA.w 2.0 *` and the like: the pass doubles the size. */
    private val doublingSize = Regex("""^//!(WIDTH|HEIGHT)\s+(.*\s)?2(\.0+)?(\s.*)?\*""")

    /**
     * Checks one file: a .glsl or .hook file with an mpv user shader in it (a `//!HOOK` or a
     * `//!DESC`). An accepted shader is an upscaler when one of its passes runs past a scale
     * factor (it then gets an "Apply always" variant), and doubles the resolution when a pass
     * writes a texture twice the size of its input.
     */
    fun check(fileName: String, sizeBytes: Long, content: String?): ShaderImportCheck {
        if (!ShaderValidation.extensionAllowed(fileName)) return ShaderImportCheck.Rejected(ShaderImportProblem.WrongExtension)
        if (sizeBytes > MAX_BYTES) return ShaderImportCheck.Rejected(ShaderImportProblem.TooLarge)
        if (content.isNullOrBlank()) return ShaderImportCheck.Rejected(ShaderImportProblem.Empty)
        if (!ShaderValidation.isMpvUserShader(content)) return ShaderImportCheck.Rejected(ShaderImportProblem.NotAShader)
        val doubles = content.lineSequence().any { doublingSize.containsMatchIn(it.trim()) }
        return ShaderImportCheck.Accepted(
            title = fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { fileName },
            upscaler = ShaderScaleCondition.hasUpscaleThreshold(content),
            doublesResolution = doubles,
            compute = ShaderValidation.usesCompute(content),
        )
    }

    /** A stable id for an imported file: same name and content, same id. */
    fun idFor(fileName: String, content: String): String =
        PREFIX + (fileName.lowercase() + ":" + content.hashCode().toUInt().toString(16)).hashCode().toUInt().toString(16)

    /** A file name for the app's folder: only safe characters, the extension kept. */
    fun storedFileName(id: String, fileName: String): String {
        val extension = fileName.substringAfterLast('.', "glsl").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "glsl" }
        return id.removePrefix(PREFIX) + "." + extension
    }

    fun traits(shader: ImportedShader) =
        ShaderPresets.ShaderTraits(stage = ShaderStage.Scaling, upscaler = shader.upscaler, doublesResolution = shader.doublesResolution)
}

/** Own chains as a file, to keep or share. */
@Serializable
data class ShaderChainExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val chains: List<ShaderChainDefinition> = emptyList(),
) {
    companion object {
        const val FORMAT = "nuvio-shader-chains"
    }
}

object ShaderChainFiles {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(chains: List<ShaderChainDefinition>): String =
        json.encodeToString(ShaderChainExport.serializer(), ShaderChainExport(chains = chains))

    /**
     * The chains in [text] that can run here: shaders this device does not have are left out,
     * chains left empty are dropped, and ids already in [existing] get new ones. Null when the
     * text is not a chain file.
     */
    fun import(text: String, known: (String) -> Boolean, existing: List<ShaderChainDefinition>, newId: () -> String): List<ShaderChainDefinition>? {
        val file = runCatching { json.decodeFromString(ShaderChainExport.serializer(), text) }.getOrNull() ?: return null
        if (file.format != ShaderChainExport.FORMAT) return null
        val taken = existing.map { it.id }.toMutableSet()
        return file.chains.mapNotNull { chain ->
            val ids = chain.shaderIds.filter(known)
            if (ids.isEmpty() || chain.name.isBlank()) return@mapNotNull null
            val id = if (chain.id.isBlank() || chain.id in taken) newId() else chain.id
            taken += id
            ShaderChainDefinition(id = id, name = chain.name.trim().take(40), shaderIds = ids)
        }
    }
}
