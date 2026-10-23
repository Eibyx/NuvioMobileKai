package com.nuvio.app.features.player.shaders

import androidx.compose.runtime.Composable

/** Shaders are Android (mpv) only. */
internal actual object ImportedShaderFiles {
    actual fun save(storedName: String, prepared: PreparedShader): Boolean = false

    actual fun delete(storedName: String) = Unit

    actual fun resolve(shader: ImportedShader, forced: Boolean): ChainShader? = null
}

@Composable
internal actual fun rememberShaderLibraryActions(onMessage: (String) -> Unit): ShaderLibraryActions? = null
