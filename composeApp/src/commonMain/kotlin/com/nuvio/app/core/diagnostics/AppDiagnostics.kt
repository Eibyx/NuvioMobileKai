package com.nuvio.app.core.diagnostics

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow

/** Why the previous run of the app ended without the user closing it. */
enum class UnexpectedExitKind {
    /** An uncaught Java/Kotlin exception. */
    Crash,

    /** A crash in native code, such as libmpv or a decoder. */
    NativeCrash,

    /** The app stopped responding (ANR). */
    Anr,

    /** Android killed the app for memory while it was in use. */
    LowMemory,

    /** Any other abnormal exit the system reported. */
    Other,
}

data class UnexpectedExitNotice(
    val reportId: String,
    val kind: UnexpectedExitKind,
    val timestampMillis: Long,
)

data class DiagnosticsStatus(
    val reportCount: Int = 0,
    val savedLogBytes: Long = 0,
)

/**
 * Local crash and log diagnostics. Nothing leaves the device unless the user exports it, and the
 * export is redacted by [DiagnosticsRedactor].
 */
expect object AppDiagnostics {
    val isSupported: Boolean

    /** True on debug builds, where Settings offers a test crash. */
    val canSimulateCrash: Boolean

    /** Set when the previous run ended unexpectedly; cleared by [dismissNotice]. */
    val notice: StateFlow<UnexpectedExitNotice?>
    val status: StateFlow<DiagnosticsStatus>

    fun dismissNotice()

    /** Writes the diagnostics TXT and opens the system share sheet. */
    suspend fun exportAndShare(): Result<Unit>

    /** Deletes saved crash reports and the saved log. */
    suspend fun clear()

    fun simulateCrash()
}

/** Playback facts the diagnostics summary needs but no repository keeps. */
object PlaybackDiagnostics {
    /** The engine that last opened a stream in this process, e.g. "ExoPlayer" or "Libmpv". */
    @Volatile
    var lastActiveEngine: String? = null
}
