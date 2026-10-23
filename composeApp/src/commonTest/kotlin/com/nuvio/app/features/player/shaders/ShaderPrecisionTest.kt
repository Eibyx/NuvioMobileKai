package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderPrecisionTest {
    private val cnnPass = """
        //!DESC Anime4K-v4.0-Restore-CNN-(M)-Conv-4x3x3x3
        //!HOOK MAIN
        //!BIND MAIN
        //!SAVE conv2d_tf
        #define go_0(x_off, y_off) (MAIN_texOff(vec2(x_off, y_off)))
        vec4 hook() {
            vec4 result = mat4(0.1, 0.2, 0.3, 0.4, 0.1, 0.2, 0.3, 0.4, 0.1, 0.2, 0.3, 0.4, 0.1, 0.2, 0.3, 0.4) * go_0(-1.0, -1.0);
            float weight = 0.5;
            const vec2 fixedOffset = vec2(1.0);
            return result * weight;
        }
    """.trimIndent()

    @Test
    fun cnnPassesGetMediumPrecision() {
        val optimized = ShaderPrecision.optimize(cnnPass)
        val lines = optimized.lines()
        val header = lines.indexOf("precision mediump float;")
        assertTrue(header > lines.indexOfFirst { it.startsWith("#define go_0") }, optimized)
        assertEquals("precision highp int;", lines[header + 1])
        assertTrue(optimized.contains("    mediump vec4 result = mat4("), optimized)
        assertTrue(optimized.contains("    mediump float weight = 0.5;"), optimized)
        // Function signatures and const declarations are left alone.
        assertTrue(optimized.contains("\nvec4 hook() {"), optimized)
        assertTrue(optimized.contains("    const vec2 fixedOffset"), optimized)
    }

    @Test
    fun optimizingTwiceChangesNothing() {
        val once = ShaderPrecision.optimize(cnnPass)
        assertEquals(once, ShaderPrecision.optimize(once))
        assertTrue(once.startsWith(ShaderPrecision.MARKER))
    }

    @Test
    fun constructorsAtTheStartOfALineAreNotDeclarations() {
        assertEquals("    mat4(1.0) * x,", ShaderPrecision.explicitPrecision("    mat4(1.0) * x,", "mediump"))
        assertEquals("vec4 hook() {", ShaderPrecision.explicitPrecision("vec4 hook() {", "mediump"))
        assertEquals("float f(float x) {", ShaderPrecision.explicitPrecision("float f(float x) {", "mediump"))
        assertEquals("  highp vec2 a = b;", ShaderPrecision.explicitPrecision("  highp vec2 a = b;", "mediump"))
        assertEquals("  mediump vec2 a = f(b);", ShaderPrecision.explicitPrecision("  vec2 a = f(b);", "mediump"))
    }

    @Test
    fun textureCoordinatesKeepFullPrecision() {
        // FSRCNNX's aggregation pass: the fraction is fine at FP16, the coordinate is not.
        assertEquals("mediump vec2 fcoord = fract(SUBCONV1_pos * SUBCONV1_size);", ShaderPrecision.explicitPrecision("vec2 fcoord = fract(SUBCONV1_pos * SUBCONV1_size);", "mediump"))
        assertEquals(
            "highp vec2 base = SUBCONV1_pos + (vec2(0.5) - fcoord) * SUBCONV1_pt;",
            ShaderPrecision.explicitPrecision("vec2 base = SUBCONV1_pos + (vec2(0.5) - fcoord) * SUBCONV1_pt;", "mediump"),
        )
        assertEquals("  const highp vec2 p = HOOKED_pos * 2.0;", ShaderPrecision.explicitPrecision("  const vec2 p = HOOKED_pos * 2.0;", "mediump"))
        assertEquals("  const float k = 0.5;", ShaderPrecision.explicitPrecision("  const float k = 0.5;", "mediump"))
        // NVIDIA Image Scaling works in source pixels: the whole pass stays highp.
        val nvScaler = """
            //!DESC NVIDIA Image Scaling and Sharpening v1.0.2
            //!HOOK LUMA
            //!COMPUTE 32 24 256 1
            void hook() {
                const float srcX = (0.5f + dstX) * kScaleX - 0.5f;
            }
        """.trimIndent()
        assertTrue(ShaderPrecision.optimize(nvScaler).contains("precision highp float;"))
    }

    @Test
    fun extensionsAndTheirIfBlocksStayFirst() {
        val artCnn = """
            //!DESC ArtCNN C4F16 (Conv2D)
            //!COMPUTE 24 32 12 16
            //!HOOK LUMA
            //!WHEN OUTPUT.w LUMA.w / 1.3 > OUTPUT.h LUMA.h / 1.3 > *
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable
            #ifdef GL_EXT_shader_explicit_arithmetic_types_float16
            #	define V4 f16vec4
            #else
            #	define V4 vec4
            #endif

            const ivec2 ksize = ivec2(3, 3);
            void hook() {
            }
        """.trimIndent()
        val lines = ShaderPrecision.optimize(artCnn).lines()
        val precision = lines.indexOf("precision mediump float;")
        assertTrue(precision > lines.indexOf("#endif"))
        assertTrue(lines.indexOfFirst { it.startsWith("#extension") } < precision)
        assertEquals("const ivec2 ksize = ivec2(3, 3);", lines[precision + 2])
    }

    @Test
    fun fsrBitTricksAndStatisticsKeepHighPrecision() {
        val fsr = """
            //!DESC FidelityFX Super Resolution v1.0.2 (EASU)
            //!HOOK LUMA
            float APrxLoRcpF1(float a) { return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a)); }
            vec4 hook() { return vec4(0.0); }
        """.trimIndent()
        assertTrue(ShaderPrecision.optimize(fsr).contains("precision highp float;"))
        val krig = """
            //!DESC KrigBilateral Downscaling Y
            //!HOOK CHROMA
            vec4 hook() {
                vec4 total = vec4(0.0);
                return total;
            }
        """.trimIndent()
        val optimized = ShaderPrecision.optimize(krig)
        assertTrue(optimized.contains("precision highp float;"))
        assertTrue(optimized.contains("    highp vec4 total = vec4(0.0);"))
        assertFalse(optimized.contains("mediump"))
    }

    @Test
    fun textureDataIsLeftUntouched() {
        val ravu = """
            //!DESC RAVU-Lite-AR (step2, r2)
            //!HOOK LUMA
            vec4 hook() {
                vec4 res = vec4(0.0);
                return res;
            }
            //!TEXTURE ravu_lite_lut2
            //!SIZE 5 288
            //!FORMAT rgba16f
            //!FILTER NEAREST
            3c003c003c003c00
        """.trimIndent()
        val lines = ShaderPrecision.optimize(ravu).lines()
        assertEquals("3c003c003c003c00", lines.last())
        assertEquals(1, lines.count { it == "precision mediump float;" })
        assertEquals("//!FILTER NEAREST", lines[lines.size - 2])
    }

    @Test
    fun parameterDefaultsAreNotCode() {
        val cfl = """
            //!PARAM distance_coeff
            //!TYPE float
            //!MINIMUM 0.0
            2.0

            //!HOOK CHROMA
            //!DESC Chroma From Luma Prediction (Smoothing Chroma)
            vec4 hook() {
                float weight = distance_coeff;
                return vec4(weight);
            }
        """.trimIndent()
        val lines = ShaderPrecision.optimize(cfl).lines()
        assertEquals("2.0", lines[lines.indexOf("//!MINIMUM 0.0") + 1])
        assertEquals(1, lines.count { it.startsWith("precision ") && it.endsWith("float;") })
        assertTrue(lines.indexOf("precision highp float;") > lines.indexOf("//!DESC Chroma From Luma Prediction (Smoothing Chroma)"))
    }

    @Test
    fun creluTexelsAreReadOnce() {
        val crelu = """
            //!DESC Anime4K-v4.0-Restore-CNN-(M)-Conv-4x3x3x8
            //!HOOK MAIN
            //!BIND conv2d_tf
            #define go_0(x_off, y_off) (max((conv2d_tf_texOff(vec2(x_off, y_off))), 0.0))
            #define go_1(x_off, y_off) (max(-(conv2d_tf_texOff(vec2(x_off, y_off))), 0.0))
            vec4 hook() {
                vec4 result = go_0(-1.0, 0.0) + go_1(1.0, 1.0);
                return result;
            }
        """.trimIndent()
        val optimized = ShaderPrecision.optimize(crelu)
        assertTrue(optimized.contains("vec4 t_m1_0 = conv2d_tf_texOff(vec2(-1.0, 0.0));"), optimized)
        assertTrue(optimized.contains("max(t_m1_0, 0.0) + max(-t_1_1, 0.0)"), optimized)
        assertFalse(optimized.contains("#define go_0"))
    }
}

class ShaderInstallTest {
    private val upscaler = """
        //!DESC Anime4K-v4.0-Upscale-CNN-x2-(S)-Conv-4x3x3x3
        //!HOOK MAIN
        //!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *
        vec4 hook() {
            vec4 result = vec4(0.0);
            return result;
        }
    """.trimIndent()

    @Test
    fun upscalersGetAForcedVariantOthersDoNot() {
        val prepared = ShaderInstall.prepare(upscaler, upscaler = true)
        assertTrue(prepared.content.contains("precision mediump float;"))
        assertTrue(prepared.content.contains("/ 1.200 >"))
        val forced = prepared.forced!!
        assertTrue(forced.contains("//!WHEN OUTPUT.w MAIN.w / 0.999 > OUTPUT.h MAIN.h / 0.999 > *"))
        // Only the WHEN line differs.
        assertEquals(prepared.content.lines().size, forced.lines().size)
        assertEquals(1, prepared.content.lines().zip(forced.lines()).count { (a, b) -> a != b })
        // A downscaler with the same kind of condition (AutoDownscalePre) is never relaxed.
        assertEquals(null, ShaderInstall.prepare(upscaler, upscaler = false).forced)
    }
}
