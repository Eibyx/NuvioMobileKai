package com.nuvio.app.features.player.shaders

/** The installed shader files on this device. Shaders are Android (mpv) only. */
internal expect object ShaderFiles {
    val isSupported: Boolean

    /**
     * [shader] ready for the chain, or null when it is not installed. [forced] picks its "Apply
     * always" variant; shaders without one use their normal file.
     */
    fun resolve(shader: BundledShader, forced: Boolean): ChainShader?
}
