package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.PlayerSettingsUiState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportFormatterTest {
    private val summary = DiagnosticsSummary(
        generatedAt = "2026-10-23 21:14:05 +0200",
        appVersion = "1.0.1-beta",
        versionCode = 1_000_001,
        packageName = "com.eibyx.nuviokai",
        buildType = "release",
        device = "samsung SM-S911B (dm1q)",
        androidVersion = "14 (API 34)",
        architecture = "64-bit app on arm64-v8a, armeabi-v7a, armeabi",
        playbackEngine = "Auto (setting), last used: Libmpv",
        playerSettings = DiagnosticsReportFormatter.playerSettingsSummary(PlayerSettingsUiState()),
    )

    private val nativeCrash = StoredDiagnosticReport(
        title = "NativeCrash at 2026-10-23 21:02:47 +0200",
        body = """
            Kind: NativeCrash
            Time: 2026-10-23 21:02:47 +0200
            Pid: 21877
            Process: com.eibyx.nuviokai
            System reason: native crash (status 11)
            Description: crash
            Importance: 100
            Memory: pss 612 MB, rss 845 MB

            --- Last log lines before the exit ---
            21:02:41 [Info] [Player/Android] engine=Libmpv configured=Auto url=https://real-debrid.example/d/ABCDEF1234567890ABCDEF1234567890/Show.S01E03.mkv audio=false subtitles=1 streamType=unknown
            21:02:44 [Warn] [MPV/Android/ffmpeg] http: HTTP error 403 Forbidden for https://cdn.example.net/v.mkv?token=9f8e7d6c5b4a&expires=1729710000
            21:02:46 [Error] [MPV/Android/vd] Decoding error, falling back to software decoding
        """.trimIndent(),
    )

    private val javaCrash = StoredDiagnosticReport(
        title = "Crash at 2026-10-22 18:40:12 +0200",
        body = """
            Kind: Crash
            Time: 2026-10-22 18:40:12 +0200
            Pid: 18321
            Thread: main
            Exception: java.lang.IllegalStateException

            java.lang.IllegalStateException: Trakt sync failed for maria.lopez@gmail.com (Authorization: Bearer 3c1f0e9d8c7b6a5f4e3d2c1b0a9f8e7d)
            	at com.nuvio.app.features.trakt.TraktSyncRepository.push(TraktSyncRepository.kt:212)
            	at com.nuvio.app.features.trakt.TraktSyncRepository${'$'}sync${'$'}1.invokeSuspend(TraktSyncRepository.kt:98)

            --- Last log lines before the crash ---
            18:40:10 [Info] [Addons] fetched https://aiostreams.elfhosted.com/stremio/2f1e0c4a-1111-2222-3333-444455556666/eyJpdiI6IjEyMzQ1Njc4OTAxMjM0NTYiLCJlbmMiOiJhYmMifQ/manifest.json in 412 ms
        """.trimIndent(),
    )

    private val logLines = listOf(
        "=== Session started 2026-10-23 21:05:10 +0200 (pid 22410) ===",
        "21:05:12 [Info] [Debrid] TorBox GET https://api.torbox.app/v1/api/user/me?apikey=tb_7c9d1e2f3a4b5c6d7e8f9a0b1c2d3e4f",
        "21:05:13 [Info] [Metadata] TMDB https://api.themoviedb.org/3/tv/1399?api_key=0123456789abcdef0123456789abcdef&language=es-ES",
        "21:05:20 [Info] [Player/Android] engine=ExoPlayer configured=Auto audio=false subtitles=2 streamType=unknown",
        "21:05:21 [Info] [ExoPlayer/Android] videoDecoder=c2.exynos.hevc.decoder acceleration=hardware initMs=38",
    )

    @Test
    fun summaryComesFirstThenReportsThenTheLog() {
        val text = DiagnosticsReportFormatter.format(summary, listOf(nativeCrash, javaCrash), listOf("Latest log lines (this session)" to logLines))
        val summaryAt = text.indexOf("Version:         1.0.1-beta (versionCode 1000001)")
        val firstReportAt = text.indexOf("=== Report 1 of 2: NativeCrash")
        val secondReportAt = text.indexOf("=== Report 2 of 2: Crash")
        val logAt = text.indexOf("=== Latest log lines (this session) (5 lines) ===")
        assertTrue(summaryAt in 0 until firstReportAt, text)
        assertTrue(firstReportAt < secondReportAt && secondReportAt < logAt, text)
        assertTrue(text.contains("Playback engine: Auto (setting), last used: Libmpv"), text)
        assertTrue(text.contains("at com.nuvio.app.features.trakt.TraktSyncRepository.push(TraktSyncRepository.kt:212)"), text)
    }

    @Test
    fun exportedTextCarriesNoSecrets() {
        val text = DiagnosticsReportFormatter.format(summary, listOf(nativeCrash, javaCrash), listOf("Latest log lines (this session)" to logLines))
        listOf(
            "ABCDEF1234567890ABCDEF1234567890",
            "9f8e7d6c5b4a",
            "maria.lopez@gmail.com",
            "3c1f0e9d8c7b6a5f",
            "eyJpdiI6",
            "2f1e0c4a-1111",
            "tb_7c9d1e2f",
            "0123456789abcdef0123456789abcdef",
        ).forEach { secret -> assertFalse(text.contains(secret), "\"$secret\" leaked:\n$text") }

        // The redacted example sits next to the build output for anyone checking the format.
        File("build/reports/diagnostics-example.txt").apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }
}
