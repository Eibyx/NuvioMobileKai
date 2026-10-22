package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.request.SuccessResult
import coil3.toBitmap

internal actual fun readableLoadedImageBitmap(result: SuccessResult): ImageBitmap? =
    runCatching { result.image.toBitmap().asComposeImageBitmap() }.getOrNull()
