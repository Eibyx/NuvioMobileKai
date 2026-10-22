package com.nuvio.app.core.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.SuccessResult
import coil3.toBitmap

// Hardware bitmaps cannot be read, so those are copied into memory first.
internal actual fun readableLoadedImageBitmap(result: SuccessResult): ImageBitmap? = runCatching {
    val bitmap = result.image.toBitmap()
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    readable.asImageBitmap()
}.getOrNull()
