package com.nuvio.app.features.subtitledownloader

import androidx.compose.runtime.Composable

actual val subtitleDownloaderAvailable: Boolean = false

@Composable
actual fun SubtitleDownloaderHost(request: SubtitleDownloaderRequest?, onDismiss: () -> Unit) = Unit
