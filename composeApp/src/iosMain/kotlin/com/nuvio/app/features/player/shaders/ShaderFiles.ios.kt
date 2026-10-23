package com.nuvio.app.features.player.shaders

/** Not offered on iOS: there is no mpv user shader support there. */
internal actual object ShaderFiles {
    actual val isSupported: Boolean = false

    actual fun resolve(shader: BundledShader, forced: Boolean): ChainShader? = null
}
