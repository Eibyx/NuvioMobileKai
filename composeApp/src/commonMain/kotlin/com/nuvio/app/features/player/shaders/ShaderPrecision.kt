/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Adapted from mpvRx's Anime4KManager (https://github.com/Riteshp2001/mpvRx,
 * domain/anime4k/Anime4KManager.kt: optimizeShaderContent, rewriteLineWithExplicitPrecision,
 * passNeedsHighpFloat and the C.ReLU texel reuse).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Modified for Nuvio: applied to every installed shader (.glsl and .hook), and passes doing
 * statistics or least squares (KrigBilateral, SSimDownscaler, CfL) keep full precision too.
 */
package com.nuvio.app.features.player.shaders

/**
 * Mobile GPUs (Mali, Adreno) run much faster at medium precision (FP16). Each shader pass gets a
 * default precision header and every float declaration an explicit qualifier, since some drivers
 * ignore the default. Passes that rely on FP32 (FSR's bit tricks, statistical solves) stay highp;
 * integers are always highp.
 */
object ShaderPrecision {
    /** Marks a shader as already processed, so installing twice changes nothing. */
    const val MARKER = "// nuvio: mobile precision"

    private val fp32Markers = listOf(
        "floatBitsToUint",
        "uintBitsToFloat",
        "floatBitsToInt",
        "intBitsToFloat",
        "bitfieldExtract",
        "bitfieldInsert",
        "FsrEasu",
        "FsrRcas",
        "APrxLoRcpF1",
        "APrxLoRsqF1",
        "APrxMedRcpF1",
    )

    /**
     * Pass descriptions whose maths lose too much at FP16: variance, kriging and regression, and
     * NVIDIA Image Scaling's source positions in pixels (FP16 has no fraction left above 1024).
     */
    private val highpDescriptions = listOf("KrigBilateral", "SSimDownscaler", "CfL", "Chroma From Luma", "NVIDIA Image")

    /** Texture coordinates and sizes: a variable holding one needs FP32 unless it keeps only the fraction. */
    private val coordinateTokens = listOf("_pos", "_pt", "_size", "gl_FragCoord", "gl_GlobalInvocationID")

    private val dataBlockHeaders = listOf("//!TEXTURE", "//!BUFFER", "//!PARAM")

    private val floatTypes = listOf("mat4", "mat3", "mat2", "vec4", "vec3", "vec2", "float")

    /** [content] with mobile precision; unchanged when it was already processed. */
    fun optimize(content: String): String {
        if (content.contains(MARKER)) return content
        val lines = content.lines()
        val out = ArrayList<String>(lines.size + 64)
        out += MARKER
        var inHeader = false
        var dataBlock = false
        var injected = false
        var waiting = false
        var conditionalDepth = 0
        var passHighp = false
        var passDescription = ""
        var bodyStart = 0
        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.startsWith("//!")) {
                if (!inHeader) {
                    inHeader = true
                    injected = false
                    waiting = false
                    dataBlock = false
                    passDescription = ""
                }
                if (trimmed.startsWith("//!DESC")) passDescription = trimmed
                // //!TEXTURE and //!BUFFER blocks hold data (RAVU's lookup tables) and //!PARAM
                // blocks a default value (CfL), not code.
                if (dataBlockHeaders.any { trimmed.startsWith(it) }) dataBlock = true
                out += line
                continue
            }
            if (inHeader) {
                inHeader = false
                waiting = !dataBlock
                conditionalDepth = 0
                bodyStart = index
            }
            if (waiting) {
                // #extension and the #if blocks that follow it must stay ahead of any statement.
                val directive = trimmed.startsWith("#")
                if (directive) {
                    when {
                        trimmed.startsWith("#if") -> conditionalDepth++
                        trimmed.startsWith("#endif") -> conditionalDepth = (conditionalDepth - 1).coerceAtLeast(0)
                    }
                }
                val code = trimmed.isNotEmpty() && !trimmed.startsWith("//") && !directive
                if (code && conditionalDepth == 0) {
                    passHighp = passNeedsHighp(lines, bodyStart, passDescription)
                    out += "precision ${if (passHighp) "highp" else "mediump"} float;"
                    out += "precision highp int;"
                    injected = true
                    waiting = false
                }
            }
            out += if (injected && !dataBlock) explicitPrecision(line, if (passHighp) "highp" else "mediump") else line
        }
        return reuseCreluTexels(out.joinToString("\n"))
    }

    /** True when the pass body from [bodyStart] uses FP32-only tricks or needs FP32 maths. */
    internal fun passNeedsHighp(lines: List<String>, bodyStart: Int, description: String): Boolean {
        if (highpDescriptions.any { description.contains(it, ignoreCase = true) }) return true
        var index = bodyStart
        while (index < lines.size && !lines[index].trim().startsWith("//!")) {
            if (fp32Markers.any { lines[index].contains(it) }) return true
            index++
        }
        return false
    }

    /**
     * `vec4 x = ...` becomes `mediump vec4 x = ...`. Comments, macros, already qualified
     * declarations, constructors and function signatures (`vec4 hook() {`) are left alone. A
     * variable that holds a texture coordinate (`vec2 base = HOOKED_pos + ...`) is always highp:
     * at FP16 a coordinate on a 1080p texture is off by about a pixel. Only `fract(...)` of one
     * is safe at medium precision.
     */
    internal fun explicitPrecision(line: String, precision: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) return line
        if (trimmed.startsWith("mediump ") || trimmed.startsWith("highp ") || trimmed.startsWith("lowp ")) return line
        val indent = line.take(line.length - line.trimStart().length)
        if (trimmed.startsWith("const ")) {
            val declaration = trimmed.removePrefix("const ").trimStart()
            val isFloat = floatTypes.any { declaration.startsWith("$it ") }
            return if (precision == "mediump" && isFloat && holdsCoordinate(declaration)) "${indent}const highp $declaration" else line
        }
        val chosen = if (precision == "mediump" && holdsCoordinate(trimmed)) "highp" else precision
        for (type in floatTypes) {
            // `mat4(...)` at the start of a line is a constructor in an expression, not a declaration.
            if (trimmed.startsWith("$type ")) {
                val afterType = trimmed.removePrefix(type).trimStart()
                val isFunction = afterType.contains("(") &&
                    !afterType.substringBefore("(").contains("=") &&
                    afterType.first() != '('
                if (isFunction) return line
                return "$indent$chosen $trimmed"
            }
        }
        return line
    }

    private fun holdsCoordinate(declaration: String): Boolean {
        val initializer = declaration.substringAfter('=', "").trim()
        if (initializer.isEmpty() || initializer.startsWith("fract(")) return false
        return coordinateTokens.any { initializer.contains(it) }
    }

    /**
     * Anime4K's C.ReLU convolutions read every 3x3 neighbour twice through `go_0`/`go_1` macros;
     * read each texel once at the top of `hook()` instead.
     */
    private fun reuseCreluTexels(content: String): String =
        content.split(Regex("(?=(?://!DESC|//!HOOK))")).joinToString("") { pass -> reuseInPass(pass) }

    private fun reuseInPass(pass: String): String {
        val go0 = Regex("""#define\s+go_0\([^)]+\)\s+\(max\(\(?\s*([A-Za-z0-9_]+)_texOff\(vec2\([^)]+\)\)\)?\s*,\s*0\.0\)\)""").find(pass)
        val go1 = Regex("""#define\s+go_1\([^)]+\)\s+\(max\(-\(?\s*([A-Za-z0-9_]+)_texOff\(vec2\([^)]+\)\)\)?\s*,\s*0\.0\)\)""").find(pass)
        if (go0 == null || go1 == null) return pass
        val texture = go0.groupValues[1]
        if (texture != go1.groupValues[1]) return pass
        var optimized = pass.replace(go0.value, "// reused texels: go_0").replace(go1.value, "// reused texels: go_1")
        val fetches = listOf("m1", "0", "1").flatMap { x -> listOf("m1", "0", "1").map { y -> x to y } }
            .joinToString("\n") { (x, y) -> "    vec4 t_${x}_$y = ${texture}_texOff(vec2(${coordinate(x)}, ${coordinate(y)}));" }
        optimized = optimized.replaceFirst(Regex("""vec4\s+hook\(\s*\)\s*\{"""), "vec4 hook() {\n$fetches")
        optimized = Regex("""go_0\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""").replace(optimized) {
            "max(t_${name(it.groupValues[1])}_${name(it.groupValues[2])}, 0.0)"
        }
        optimized = Regex("""go_1\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""").replace(optimized) {
            "max(-t_${name(it.groupValues[1])}_${name(it.groupValues[2])}, 0.0)"
        }
        // A kernel larger than 3x3 would reference undeclared texels: keep the original pass.
        return if (optimized.contains("_unknown")) pass else optimized
    }

    private fun coordinate(name: String) = when (name) {
        "m1" -> "-1.0"
        "0" -> "0.0"
        else -> "1.0"
    }

    private fun name(value: String) = when (value.trim()) {
        "-1.0", "-1" -> "m1"
        "0.0", "0" -> "0"
        "1.0", "1" -> "1"
        else -> "unknown"
    }
}
