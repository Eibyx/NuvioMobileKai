package com.nuvio.app.features.player.shaders

/** A shader as it is stored on the device: optimized, plus its "Apply always" variant when it has one. */
data class PreparedShader(val content: String, val forced: String?)

object ShaderInstall {
    /**
     * Every shader gets the mobile precision pass. Upscalers also get a forced variant that runs
     * from a scale factor of 1.0 (see [ShaderScaleCondition]); a [toScreen] downscaler
     * (SSimDownscaler) gets one that also shrinks an upscaled image to the screen. Other shaders
     * (sharpeners, chroma, AutoDownscalePre) never do, so their conditions stay as written.
     */
    fun prepare(content: String, upscaler: Boolean, toScreen: Boolean = false): PreparedShader {
        val optimized = ShaderPrecision.optimize(content)
        val forced = when {
            upscaler && ShaderScaleCondition.hasUpscaleThreshold(optimized) -> ShaderScaleCondition.relaxUpscaleThresholds(optimized)
            toScreen -> ShaderScaleCondition.downscaleFromScalerInput(optimized)
            else -> null
        }
        return PreparedShader(optimized, forced)
    }
}
