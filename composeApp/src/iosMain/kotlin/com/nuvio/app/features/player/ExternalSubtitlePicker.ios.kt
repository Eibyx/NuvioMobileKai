package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** Not offered on iOS yet: the entry is hidden. */
@Composable
internal actual fun rememberExternalSubtitlePicker(
    onImported: (ImportedSubtitle) -> Unit,
    onRejected: (ExternalSubtitleRejection) -> Unit,
): (() -> Unit)? = null
