package com.nuvio.app.features.player.shaders

/**
 * mpv user shaders gate their passes with `//!WHEN`, a reverse-Polish expression over texture
 * sizes. Upscalers only run past a scale factor: Anime4K `OUTPUT.w MAIN.w / 1.200 >`, ArtCNN and
 * FSRCNNX `OUTPUT.w LUMA.w / 1.3 >`, RAVU `HOOKED.w OUTPUT.w / 0.707106 <`. On a 1220 px tall
 * phone a 1080p video is scaled by 1.13, so none of them run.
 *
 * The "forced" variant of a shader relaxes only those thresholds so the pass runs from a factor of
 * 1.0: `OUTPUT / X > N` with N > 1 becomes `> 0.999`, and `X / OUTPUT < N` with N < 1 becomes
 * `< 1.001`. A video larger than the screen (factor below 1.0, such as 4K) still never runs them.
 * Every other condition (chroma checks, downscale checks, the 1.0 area checks of FSR, CAS and the
 * NVIDIA shaders) is left as it is.
 */
object ShaderScaleCondition {
    const val FORCED_UPSCALE_THRESHOLD = "0.999"
    const val FORCED_INVERSE_THRESHOLD = "1.001"

    private val unaryOperators = setOf("!")
    private val binaryOperators = setOf("+", "-", "*", "/", ">", "<", "=")

    /** True when some `//!WHEN` of [content] gates a pass on a scale factor above 1. */
    fun hasUpscaleThreshold(content: String): Boolean =
        content.lineSequence().any { line -> isWhen(line) && relaxExpression(expression(line)) != normalized(expression(line)) }

    /** [content] with every upscale threshold relaxed (see the class comment). */
    fun relaxUpscaleThresholds(content: String): String =
        content.lines().joinToString("\n") { line ->
            if (!isWhen(line)) {
                line
            } else {
                val prefix = line.substring(0, line.indexOf("//!WHEN") + "//!WHEN".length)
                val relaxed = relaxExpression(expression(line))
                if (relaxed == normalized(expression(line))) line else "$prefix $relaxed"
            }
        }

    /**
     * SSimDownscaler only runs when the native video is larger than the output, and reads the
     * image at the native size. This copy measures the image the main scaler gets (PREKERNEL)
     * instead, so it also takes an upscaled image (Anime4K x2 of a 1080p video: 2160 lines) down
     * to the screen. Without an upscale before it, PREKERNEL is the native image and it behaves
     * as the original.
     */
    fun downscaleFromScalerInput(content: String): String =
        content.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("//!WHEN") || trimmed.startsWith("//!WIDTH") || trimmed.startsWith("//!HEIGHT") ->
                    line.replace("NATIVE_CROPPED", "PREKERNEL")
                trimmed.startsWith("//") -> line
                else -> line.replace(Regex("\\binput_size\\b"), "PREKERNEL_size")
            }
        }

    private fun isWhen(line: String) = line.trimStart().startsWith("//!WHEN")

    private fun expression(line: String) = line.substringAfter("//!WHEN").trim()

    private fun normalized(expression: String) = expression.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** One `//!WHEN` expression with its upscale thresholds relaxed. */
    internal fun relaxExpression(expression: String): String {
        val tokens = expression.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        for (index in tokens.indices) {
            val comparison = tokens.getOrNull(index + 1) ?: continue
            if (tokens.getOrNull(index - 1) != "/") continue
            val threshold = tokens[index].toDoubleOrNull() ?: continue
            if (comparison != ">" && comparison != "<") continue
            // The division is `numerator denominator /`, both full sub-expressions before it.
            val divisionAt = index - 1
            val denominatorStart = operandStart(tokens, divisionAt - 1) ?: continue
            val numeratorStart = operandStart(tokens, denominatorStart - 1) ?: continue
            val numerator = tokens.subList(numeratorStart, denominatorStart)
            val denominator = tokens.subList(denominatorStart, divisionAt)
            val outputOnTop = numerator.any(::isOutput) && denominator.none(::isOutput)
            val outputBelow = denominator.any(::isOutput) && numerator.none(::isOutput)
            when {
                comparison == ">" && outputOnTop && threshold > 1.0 -> tokens[index] = FORCED_UPSCALE_THRESHOLD
                comparison == "<" && outputBelow && threshold < 1.0 -> tokens[index] = FORCED_INVERSE_THRESHOLD
            }
        }
        return tokens.joinToString(" ")
    }

    private fun isOutput(token: String) = token.startsWith("OUTPUT.")

    /**
     * Evaluates a `//!WHEN` [expression] the way mpv does, with texture sizes from [sizes]
     * ("OUTPUT.w" to 2712.0, ...). Null when it names a size [sizes] lacks or is malformed.
     */
    fun evaluate(expression: String, sizes: Map<String, Double>): Boolean? {
        val stack = ArrayList<Double>()
        for (token in expression.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            when (token) {
                "!" -> {
                    val value = stack.removeLastOrNull() ?: return null
                    stack += if (value == 0.0) 1.0 else 0.0
                }
                in binaryOperators -> {
                    val right = stack.removeLastOrNull() ?: return null
                    val left = stack.removeLastOrNull() ?: return null
                    stack += when (token) {
                        "+" -> left + right
                        "-" -> left - right
                        "*" -> left * right
                        "/" -> left / right
                        ">" -> if (left > right) 1.0 else 0.0
                        "<" -> if (left < right) 1.0 else 0.0
                        else -> if (left == right) 1.0 else 0.0
                    }
                }
                else -> stack += token.toDoubleOrNull() ?: sizes[token] ?: return null
            }
        }
        return stack.singleOrNull()?.let { it != 0.0 }
    }

    /** Index where the sub-expression ending at [end] starts, or null when it is malformed. */
    private fun operandStart(tokens: List<String>, end: Int): Int? {
        if (end < 0) return null
        var needed = 1
        var position = end
        while (position >= 0) {
            val token = tokens[position]
            needed -= 1
            needed += when (token) {
                in binaryOperators -> 2
                in unaryOperators -> 1
                else -> 0
            }
            if (needed == 0) return position
            position -= 1
        }
        return null
    }
}
