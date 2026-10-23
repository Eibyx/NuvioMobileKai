package com.nuvio.app.features.player.shaders

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks every shader in the APK's assets the way the installer processes it. */
class BundledShadersTest {
    private val assets = File("src/androidMain/assets/${ShaderCatalog.ASSET_FOLDER}")
    private val resources = File("src/commonMain/composeResources")

    private fun content(shader: BundledShader) = File(assets, shader.file).readText()

    /** The phone's 2712x1220 screen playing a [width]x[height] video. */
    private fun sizes(width: Int, height: Int): Map<String, Double> {
        val factor = minOf(2712.0 / width, 1220.0 / height)
        return listOf("MAIN", "LUMA", "HOOKED", "NATIVE").flatMap { listOf("$it.w" to width.toDouble(), "$it.h" to height.toDouble()) }
            .toMap() + mapOf("OUTPUT.w" to width * factor, "OUTPUT.h" to height * factor)
    }

    /** Header blocks (consecutive `//!` lines) followed by code, not by texture data. */
    private fun codePasses(content: String): Int {
        var count = 0
        var header = mutableListOf<String>()
        for (line in content.lines()) {
            if (line.trim().startsWith("//!")) {
                header += line.trim()
            } else if (header.isNotEmpty()) {
                if (header.none { it.startsWith("//!TEXTURE") || it.startsWith("//!BUFFER") || it.startsWith("//!PARAM") }) count++
                header = mutableListOf()
            }
        }
        return count
    }

    private fun whenLines(content: String) = content.lines().filter { it.startsWith("//!WHEN") }.map { it.removePrefix("//!WHEN").trim() }

    @Test
    fun everyCatalogShaderIsBundledAndNothingElse() {
        val files = assets.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(ShaderCatalog.shaders.map { it.file }.toSet(), files)
        assertEquals(ShaderCatalog.shaders.size, ShaderCatalog.shaders.map { it.id }.toSet().size)
        // Too heavy for a phone, never bundled.
        assertTrue(files.none { it.contains("nnedi", true) || it.contains("_VL") || it.contains("_UL") || it.contains("Ani4K", true) })
    }

    @Test
    fun everyBundledShaderHasItsLicense() {
        ShaderCatalog.shaders.forEach { shader ->
            assertTrue(shader.project in ShaderCatalog.projects, shader.id)
            val license = File(resources, shader.project.licenseFile)
            assertTrue(license.isFile, "${shader.file}: ${license.path}")
            val text = license.readText()
            assertTrue(text.contains(shader.pin.commit), "${shader.file}: pinned commit in the notice")
            if (shader.project.license.startsWith("LGPL")) assertTrue(File(resources, ShaderCatalog.LGPL_FILE).isFile)
        }
        assertTrue(File(resources, ShaderCatalog.shaderPack.licenseFile).isFile)
    }

    @Test
    fun everyShaderIsAValidMpvShaderWithMobilePrecision() {
        ShaderCatalog.shaders.forEach { shader ->
            val original = content(shader)
            assertTrue(ShaderValidation.isMpvUserShader(original), shader.file)
            assertEquals(shader.compute, ShaderValidation.usesCompute(original), "${shader.file} compute")
            val prepared = ShaderInstall.prepare(original, shader.upscaler)
            val lines = prepared.content.lines()
            val code = codePasses(original)
            // One precision header per pass with code (texture data blocks get none).
            assertEquals(code, lines.count { it.startsWith("precision ") && it.endsWith(" float;") }, "${shader.file} precision headers")
            assertEquals(code, lines.count { it == "precision highp int;" }, "${shader.file} int precision")
            // #extension must stay ahead of the precision statements of its pass.
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#extension")) {
                    val nextPrecision = (index until lines.size).firstOrNull { lines[it].startsWith("precision ") }
                    val nextPass = (index until lines.size).firstOrNull { lines[it].startsWith("//!") } ?: lines.size
                    assertTrue(nextPrecision != null && nextPrecision < nextPass, "${shader.file}: #extension before precision")
                }
            }
            val mediump = lines.any { it == "precision mediump float;" }
            when (shader.project) {
                ShaderCatalog.anime4k, ShaderCatalog.artCnn, ShaderCatalog.fsrcnnx, ShaderCatalog.ravu -> assertTrue(mediump, "${shader.file} mediump")
                ShaderCatalog.krigBilateral, ShaderCatalog.ssimDownscaler, ShaderCatalog.cfl, ShaderCatalog.nvidia -> assertFalse(mediump, "${shader.file} keeps highp")
                else -> Unit
            }
            // //!PARAM defaults (CfL) are not code: they stay right after their header.
            original.lines().forEachIndexed { index, line ->
                if (line.startsWith("//!PARAM")) {
                    val value = original.lines().drop(index).first { !it.startsWith("//!") }
                    val at = lines.indexOf(line)
                    assertEquals(value, lines.drop(at).first { !it.startsWith("//!") }, "${shader.file} $line")
                }
            }
            // No texture coordinate is kept in a medium precision variable.
            lines.filter { it.trimStart().startsWith("mediump ") }.forEach { line ->
                val initializer = line.substringAfter('=', "").trim()
                if (!initializer.startsWith("fract(")) {
                    assertFalse(listOf("_pos", "_pt", "_size").any { initializer.contains(it) }, "${shader.file}: $line")
                }
            }
            // The data of texture blocks (RAVU's lookup tables) is untouched.
            val originalData = original.lines().filter { it.matches(Regex("[0-9a-f]{64,}")) }
            assertEquals(originalData, lines.filter { it.matches(Regex("[0-9a-f]{64,}")) }, "${shader.file} texture data")
        }
    }

    @Test
    fun forcedVariantsRunAt1080pOnThePhoneButNeverOnLargerVideo() {
        ShaderCatalog.shaders.forEach { shader ->
            val prepared = ShaderInstall.prepare(content(shader), shader.upscaler, shader.toScreen)
            if (shader.toScreen) {
                val toScreen = assertNotNull(prepared.forced, shader.file)
                assertFalse(toScreen.contains("NATIVE_CROPPED"), shader.file)
                assertFalse(Regex("\\binput_size\\b").containsMatchIn(toScreen), shader.file)
                // 1080p doubled to 2160 lines, shown on 1220: it runs; on the plain 1080p (no upscale) it does not.
                val upscaled = mapOf("PREKERNEL.w" to 3840.0, "PREKERNEL.h" to 2160.0, "POSTKERNEL.w" to 2169.0, "POSTKERNEL.h" to 1220.0)
                whenLines(toScreen).forEach { assertEquals(true, ShaderScaleCondition.evaluate(it, upscaled), it) }
                val plain = mapOf("PREKERNEL.w" to 1920.0, "PREKERNEL.h" to 1080.0, "POSTKERNEL.w" to 2169.0, "POSTKERNEL.h" to 1220.0)
                whenLines(toScreen).forEach { assertEquals(false, ShaderScaleCondition.evaluate(it, plain), it) }
                return@forEach
            }
            if (!shader.upscaler) {
                assertNull(prepared.forced, shader.file)
                return@forEach
            }
            val forced = assertNotNull(prepared.forced, shader.file)
            // Only //!WHEN lines differ.
            prepared.content.lines().zip(forced.lines()).filter { (a, b) -> a != b }.forEach { (a, b) ->
                assertTrue(a.startsWith("//!WHEN") && b.startsWith("//!WHEN"), "${shader.file}: $a")
            }
            whenLines(prepared.content).forEach { expression ->
                if (shader.project == ShaderCatalog.ravu || shader.stage != ShaderStage.Scaling) return@forEach
                assertEquals(false, ShaderScaleCondition.evaluate(expression, sizes(1920, 1080)), "${shader.file} original at 1080p")
            }
            whenLines(forced).forEach { expression ->
                assertEquals(true, ShaderScaleCondition.evaluate(expression, sizes(1920, 1080)), "${shader.file} forced at 1080p: $expression")
                assertEquals(false, ShaderScaleCondition.evaluate(expression, sizes(3840, 2160)), "${shader.file} forced at 4K")
            }
        }
    }

    @Test
    fun anime4kDownscalersAreNeverForced() {
        listOf("anime4k_autodownscale_x2", "anime4k_autodownscale_x4", "nvsharpen", "krigbilateral").forEach { id ->
            val shader = ShaderCatalog.shader(id)!!
            assertFalse(shader.upscaler, id)
            assertNull(ShaderInstall.prepare(content(shader), shader.upscaler, shader.toScreen).forced, id)
        }
    }
}
