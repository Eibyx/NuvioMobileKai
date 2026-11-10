package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    if (source.width <= 0 || source.height <= 0) return null
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    // Reading every pixel of a large backdrop (up to 3840 px from fanart.tv) costs tens of MB
    // and time. A filtered copy at a few times the sample width keeps the area average accurate.
    val intermediateWidth = (targetWidth * IntermediateScale).coerceAtMost(software.width)
    val small = if (intermediateWidth < software.width) {
        val height = (software.height.toLong() * intermediateWidth / software.width).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(software, intermediateWidth, height, true)
    } else {
        software
    }
    val width = small.width
    val height = small.height
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== software && small !== source) small.recycle()
    if (software !== source) software.recycle()
    return downsampleArgb(pixels, width, height, targetWidth)
}

private const val IntermediateScale = 4
