package com.nuvio.app.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsRedactorTest {
    private val r = DiagnosticsRedactor.PLACEHOLDER

    private fun redact(text: String) = DiagnosticsRedactor.redact(text)

    private fun assertHidden(text: String, vararg secrets: String) {
        val result = redact(text)
        secrets.forEach { secret ->
            assertFalse(result.contains(secret), "\"$secret\" survived in: $result")
        }
        assertTrue(result.contains(r), "nothing was redacted in: $result")
    }

    @Test
    fun torboxTokenInQueryIsHiddenButIdsStay() {
        val line = "12:01:03 [Info] [Debrid] GET https://api.torbox.app/v1/api/torrents/requestdl" +
            "?token=a1b2c3d4-e5f6-7890-abcd-ef1234567890&torrent_id=12345&file_id=2"
        assertEquals(
            "12:01:03 [Info] [Debrid] GET https://api.torbox.app/v1/api/torrents/requestdl" +
                "?token=$r&torrent_id=12345&file_id=2",
            redact(line),
        )
    }

    @Test
    fun traktAuthorizationAndApiKeyHeadersAreHidden() {
        val line = "headers={Authorization=Bearer 7c3e1f0d9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d, " +
            "trakt-api-key=1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b, trakt-api-version=2}"
        val result = redact(line)
        assertHidden(line, "7c3e1f0d9a8b", "1a2b3c4d5e6f")
        assertTrue(result.contains("trakt-api-version=2"), result)
    }

    @Test
    fun simklApiKeyHeaderAndClientIdAreHidden() {
        assertHidden(
            "POST https://api.simkl.com/sync/history?client_id=3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c simkl-api-key: 3f9a8b7c6d5e",
            "3f9a8b7c6d5e",
        )
    }

    @Test
    fun anilistTokenResponseIsHidden() {
        val json = """{"access_token":"eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiIxMjM0NSIsImp0aSI6ImFiYyJ9.c2lnbmF0dXJlLXNpZ25hdHVyZQ","token_type":"Bearer","expires_in":31536000}"""
        val result = redact(json)
        assertHidden(json, "eyJ0eXAiOiJKV1Qi", "c2lnbmF0dXJl")
        assertTrue(result.contains("\"expires_in\":31536000"), result)
    }

    @Test
    fun mdblistAndTmdbKeysAreHiddenButOtherParamsStay() {
        assertEquals(
            "https://api.mdblist.com/tmdb/movie/603?apikey=$r",
            redact("https://api.mdblist.com/tmdb/movie/603?apikey=abcd1234efgh5678ijkl9012"),
        )
        assertEquals(
            "https://api.themoviedb.org/3/tv/1399?api_key=$r&language=es-ES",
            redact("https://api.themoviedb.org/3/tv/1399?api_key=0123456789abcdef0123456789abcdef&language=es-ES"),
        )
    }

    /**
     * Provider keys built at run time from an obviously fake body, so no key-shaped literal sits in
     * the source (secret scanners flag those, real or not). Same shape as the real formats.
     */
    private val fakeGoogleKey = "AIza" + "FAKE0TEST0KEY0NOT0A0REAL0KEY".padEnd(35, '0')
    private val fakeOpenAiKey = "sk-" + "proj-" + "FAKE0TEST0KEY0NOT0A0REAL0KEY"

    @Test
    fun aiProviderKeysAreHidden() {
        assertEquals(39, fakeGoogleKey.length)
        assertHidden("x-goog-api-key: $fakeGoogleKey", fakeGoogleKey, "FAKE0TEST")
        assertHidden("AI insights request failed with key $fakeOpenAiKey", fakeOpenAiKey, "FAKE0TEST")
    }

    @Test
    fun aiostreamsConfigurationInTheUrlIsHidden() {
        val manifest = "https://aiostreams.elfhosted.com/stremio/2f1e0c4a-1111-2222-3333-444455556666/" +
            "eyJpdiI6IjEyMzQ1Njc4OTAxMjM0NTYiLCJlbmNyeXB0ZWQiOiJhYmNkZWZnaGlqa2xtbm9wIn0/manifest.json"
        assertEquals("https://aiostreams.elfhosted.com/$r/manifest.json", redact(manifest))

        val stream = manifest.removeSuffix("manifest.json") + "stream/series/tt0944947:1:3.json"
        assertEquals("https://aiostreams.elfhosted.com/$r/stream/series/tt0944947:1:3.json", redact(stream))
    }

    @Test
    fun torrentioDebridKeyInThePathIsHidden() {
        val url = "https://torrentio.strem.fun/providers=yts,eztv|sort=qualitysize|realdebrid=ABCDEFGH12345678IJKL" +
            "/stream/movie/tt0111161.json"
        assertEquals("https://torrentio.strem.fun/$r/stream/movie/tt0111161.json", redact(url))
    }

    @Test
    fun secretQueryParametersAreHidden() {
        assertEquals(
            "https://example.com/login?user=kai&password=$r&auth=$r&key=$r",
            redact("https://example.com/login?user=kai&password=hunter2&auth=xyz987&key=k3y"),
        )
    }

    @Test
    fun authorizationHeaderLineIsHidden() {
        assertEquals("Authorization: $r", redact("Authorization: Basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Authorization: $r", redact("Authorization: Bearer abc.def-ghi"))
    }

    @Test
    fun emailAddressesAreHidden() {
        assertEquals("Signed in as $r (profile 1)", redact("Signed in as maria.lopez@gmail.com (profile 1)"))
    }

    @Test
    fun urlCredentialsAreHidden() {
        assertEquals("https://$r@webdav.example.com/files", redact("https://kai:secret@webdav.example.com/files"))
    }

    @Test
    fun ordinaryDiagnosticsAreLeftAlone() {
        val lines = listOf(
            "13:10:02 [Info] [Player/Android] engine=ExoPlayer configured=Auto audio=false subtitles=2",
            "\tat com.nuvio.app.features.player.PlayerScreenKt\$PlayerScreen\$1.invokeSuspend(PlayerScreen.kt:812)",
            "java.lang.IllegalStateException: Season 2 episode 5 not found for tt0944947",
            "https://v3-cinemeta.strem.io/meta/series/tt0944947.json",
            "videoDecoder=c2.qti.hevc.decoder acceleration=hardware initMs=41",
        )
        lines.forEach { line -> assertEquals(line, redact(line)) }
    }
}
