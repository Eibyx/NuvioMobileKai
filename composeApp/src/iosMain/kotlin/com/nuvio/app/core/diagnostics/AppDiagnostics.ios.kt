package com.nuvio.app.core.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Kai only builds Android; iOS keeps the old copy-to-clipboard log. */
actual object AppDiagnostics {
    actual val isSupported: Boolean = false
    actual val canSimulateCrash: Boolean = false
    actual val notice: StateFlow<UnexpectedExitNotice?> = MutableStateFlow(null)
    actual val status: StateFlow<DiagnosticsStatus> = MutableStateFlow(DiagnosticsStatus())

    actual fun dismissNotice() = Unit

    actual suspend fun exportAndShare(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Diagnostics export is Android only"))

    actual suspend fun clear() = Unit

    actual fun simulateCrash() = Unit
}
