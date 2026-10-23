package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderImportTest {
    private val upscaler = """
        //!DESC My Upscaler (Conv)
        //!HOOK LUMA
        //!WIDTH LUMA.w 2.0 *
        //!HEIGHT LUMA.h 2.0 *
        //!WHEN OUTPUT.w LUMA.w / 1.3 > OUTPUT.h LUMA.h / 1.3 > *
        vec4 hook() { return LUMA_tex(LUMA_pos); }
    """.trimIndent()

    @Test
    fun onlyMpvShadersWithTheRightExtensionAreAccepted() {
        assertEquals(ShaderImportCheck.Rejected(ShaderImportProblem.WrongExtension), ShaderImport.check("shader.frag", 100, upscaler))
        assertEquals(ShaderImportCheck.Rejected(ShaderImportProblem.WrongExtension), ShaderImport.check("README", 100, upscaler))
        assertEquals(ShaderImportCheck.Rejected(ShaderImportProblem.TooLarge), ShaderImport.check("big.glsl", ShaderImport.MAX_BYTES + 1, upscaler))
        assertEquals(ShaderImportCheck.Rejected(ShaderImportProblem.Empty), ShaderImport.check("empty.glsl", 0, ""))
        assertEquals(ShaderImportCheck.Rejected(ShaderImportProblem.Empty), ShaderImport.check("null.hook", 10, null))
        assertEquals(
            ShaderImportCheck.Rejected(ShaderImportProblem.NotAShader),
            ShaderImport.check("plain.glsl", 40, "void main() { gl_FragColor = vec4(1.0); }"),
        )
        // A //!DESC alone is enough, as in mpv.
        assertIs<ShaderImportCheck.Accepted>(ShaderImport.check("desc.hook", 20, "//!DESC only a description\n"))
    }

    @Test
    fun upscalersAreRecognisedAndGetAnApplyAlwaysVariant() {
        val check = assertIs<ShaderImportCheck.Accepted>(ShaderImport.check("My_Upscaler.glsl", 300, upscaler))
        assertEquals("My Upscaler", check.title)
        assertTrue(check.upscaler)
        assertTrue(check.doublesResolution)
        val prepared = ShaderInstall.prepare(upscaler, check.upscaler)
        assertTrue(prepared.forced!!.contains("/ 0.999 >"))
        assertTrue(prepared.content.contains("precision "))
        val sharpen = assertIs<ShaderImportCheck.Accepted>(
            ShaderImport.check("sharp.glsl", 100, "//!HOOK LUMA\n//!DESC Sharpen\nvec4 hook() { return LUMA_texOff(0.0); }"),
        )
        assertTrue(!sharpen.upscaler && !sharpen.doublesResolution)
        assertTrue(ShaderImport.check("c.glsl", 50, "//!HOOK LUMA\n//!COMPUTE 8 8\n//!WIDTH 2 HOOKED.w *\nvoid hook() {}").let { it is ShaderImportCheck.Accepted && it.compute && it.doublesResolution })
    }

    @Test
    fun idsAndFileNamesAreStableAndSafe() {
        val id = ShaderImport.idFor("A.glsl", upscaler)
        assertEquals(id, ShaderImport.idFor("a.GLSL", upscaler))
        assertNotEquals(id, ShaderImport.idFor("A.glsl", upscaler + "\n// changed"))
        assertTrue(id.startsWith(ShaderImport.PREFIX))
        val stored = ShaderImport.storedFileName(id, "../../evil name.hook")
        assertTrue(stored.matches(Regex("[0-9a-f]+\\.hook")), stored)
    }

    @Test
    fun chainsExportAndImportKeepingOnlyWhatRunsHere() {
        val chains = listOf(
            ShaderChainDefinition("c1", "Mine", listOf("fsrcnnx_8", "krigbilateral")),
            ShaderChainDefinition("c2", "Missing", listOf("imported:gone")),
        )
        val json = ShaderChainFiles.export(chains)
        var next = 0
        val known = { id: String -> ShaderCatalog.shader(id) != null }
        val imported = ShaderChainFiles.import(json, known, existing = listOf(chains[0]), newId = { "n${next++}" })!!
        // The chain whose shaders are all missing is dropped; a taken id gets a new one.
        assertEquals(listOf(ShaderChainDefinition("n0", "Mine", listOf("fsrcnnx_8", "krigbilateral"))), imported)
        assertNull(ShaderChainFiles.import("{\"format\":\"something else\"}", known, emptyList()) { "x" })
        assertNull(ShaderChainFiles.import("not json", known, emptyList()) { "x" })
    }

    @Test
    fun importedShadersJoinTheChains() {
        val shader = ImportedShader("imported:ab", "Mine", "ab.glsl", upscaler = true, doublesResolution = true, compute = false)
        val traits = { id: String -> ShaderPresets.bundledTraits(id) ?: shader.takeIf { it.id == id }?.let(ShaderImport::traits) }
        val settings = ShaderSettings(chains = listOf(ShaderChainDefinition("c", "C", listOf("imported:ab", "krigbilateral"))))
        assertEquals(
            listOf(ChainEntry("imported:ab", forced = true), ChainEntry("krigbilateral")),
            ShaderPresets.resolve("chain:c", settings, traits = traits),
        )
        assertTrue(ShaderPresets.doublesTwice(listOf("imported:ab", "artcnn_c4f16"), traits))
        assertEquals("imported:ab", ShaderLibrary.baseId("imported:ab#forced@2"))
    }
}
