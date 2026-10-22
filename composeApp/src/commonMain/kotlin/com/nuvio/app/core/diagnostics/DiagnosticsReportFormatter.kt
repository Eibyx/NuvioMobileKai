package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.PlayerSettingsUiState

data class DiagnosticsSummary(
    val generatedAt: String,
    val appVersion: String,
    val versionCode: Long,
    val packageName: String,
    val buildType: String,
    val device: String,
    val androidVersion: String,
    val architecture: String,
    val playbackEngine: String,
    val playerSettings: List<Pair<String, String>>,
)

/** A saved crash or exit report, newest first when listed. */
data class StoredDiagnosticReport(
    val title: String,
    val body: String,
)

/**
 * Lays out the exported TXT: summary first, then each report with its stack trace, then the
 * latest log lines. Everything goes through [DiagnosticsRedactor] on the way out.
 */
object DiagnosticsReportFormatter {
    fun format(
        summary: DiagnosticsSummary,
        reports: List<StoredDiagnosticReport>,
        logSections: List<Pair<String, List<String>>>,
    ): String {
        val raw = buildString {
            appendLine("Nuvio Kai diagnostics")
            appendLine("=====================")
            appendLine("Generated:       ${summary.generatedAt}")
            appendLine("Version:         ${summary.appVersion} (versionCode ${summary.versionCode})")
            appendLine("Package:         ${summary.packageName} (${summary.buildType})")
            appendLine("Device:          ${summary.device}")
            appendLine("Android:         ${summary.androidVersion}")
            appendLine("Architecture:    ${summary.architecture}")
            appendLine("Playback engine: ${summary.playbackEngine}")
            appendLine("Player settings:")
            summary.playerSettings.forEach { (name, value) -> appendLine("  $name: $value") }
            appendLine("Saved reports:   ${reports.size}")
            appendLine()
            appendLine("Keys, tokens, passwords, addon configuration and e-mail addresses are replaced with")
            appendLine("${DiagnosticsRedactor.PLACEHOLDER} in this file.")

            reports.forEachIndexed { index, report ->
                appendLine()
                appendLine("=== Report ${index + 1} of ${reports.size}: ${report.title} ===")
                appendLine(report.body.trimEnd())
            }
            if (reports.isEmpty()) {
                appendLine()
                appendLine("=== No crash or unexpected exit reports saved ===")
            }

            logSections.forEach { (title, lines) ->
                appendLine()
                appendLine("=== $title (${lines.size} lines) ===")
                lines.forEach(::appendLine)
            }
        }
        return DiagnosticsRedactor.redact(raw)
    }

    /** The player options that matter when reading a playback crash. */
    fun playerSettingsSummary(settings: PlayerSettingsUiState): List<Pair<String, String>> = buildList {
        add("engine setting" to settings.androidPlaybackEngine.label)
        add("libmpv video output" to settings.androidLibmpvVideoOutput.mpvValue)
        add("libmpv hardware decoding" to settings.androidLibmpvHardwareDecodingEnabled.onOff())
        add("libmpv yuv420p" to settings.androidLibmpvYuv420pEnabled.onOff())
        add("ExoPlayer decoder priority" to settings.decoderPriority.toString())
        add("map Dolby Vision 7 to HEVC" to settings.mapDV7ToHevc.onOff())
        add("tunneling" to settings.tunnelingEnabled.onOff())
        add("audio passthrough" to settings.androidAudioPassthroughEnabled.onOff())
        add("ExoPlayer native memory" to settings.exoNativeMemoryEnabled.onOff())
        add(
            "custom buffers" to if (settings.customPlaybackBuffersEnabled) {
                "on (min ${settings.playbackMinBufferSeconds}s, max ${settings.playbackMaxBufferSeconds}s, " +
                    "target ${settings.playbackTargetBufferMb} MB)"
            } else {
                "off"
            },
        )
        add(
            "disk cache" to if (settings.vodDiskCacheEnabled) {
                if (settings.vodDiskCacheAutoSize) "on (auto size)" else "on (${settings.vodDiskCacheSizeMb} MB)"
            } else {
                "off"
            },
        )
        add("ASS subtitles (ExoPlayer libass)" to if (settings.useLibass) "on (${settings.libassRenderType})" else "off")
        add("ASS subtitles (libmpv)" to settings.useLibmpvAss.onOff())
        add("preferred audio" to settings.preferredAudioLanguage)
        add("preferred subtitles" to settings.preferredSubtitleLanguage)
        add("external player" to settings.externalPlayerEnabled.onOff())
        add("autoplay" to settings.streamAutoPlayMode.name.lowercase())
    }

    private fun Boolean.onOff(): String = if (this) "on" else "off"
}
