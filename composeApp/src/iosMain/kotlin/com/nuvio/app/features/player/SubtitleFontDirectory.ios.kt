package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** Not offered on iOS: the "Fonts" section is hidden. */
internal actual object SubtitleFontDirectoryPlatform {
    actual val isSupported: Boolean = false
    actual fun folderLabel(): String? = null
    actual fun hasFolder(): Boolean = false
    actual suspend fun copyFromFolder(onProgress: (FontDirectoryProgress) -> Unit): FontDirectoryProgress? = null
    actual fun clear() = Unit
    actual fun summary(): Pair<Int, Long> = 0 to 0L
}

@Composable
internal actual fun rememberFontDirectoryPicker(onPicked: () -> Unit): () -> Unit = {}
