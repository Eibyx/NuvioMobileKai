package com.nuvio.app.features.cloudstorage

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.logging.redactLocalStreamTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalStreamTokenRedactionTest {
    private val token = "0123456789abcdef".repeat(4)
    private val url = "http://127.0.0.1:41234/v1/$token/Kotoura%20san%2001.mp4"

    @Test
    fun tokensAreHiddenInLogLinesAndRedactedUrls() {
        assertEquals("http://127.0.0.1:41234/v1/<token>/Kotoura%20san%2001.mp4", redactLocalStreamTokens(url))
        assertEquals(
            "mpv: Failed to open http://127.0.0.1:41234/v1/<token>/Kotoura%20san%2001.mp4.",
            redactLocalStreamTokens("mpv: Failed to open $url."),
        )
        assertFalse(token in InAppLogger.redactUrl(url))
    }

    @Test
    fun otherUrlsAreUntouched() {
        val addon = "https://example.com/v1/stream/movie/tt1.json"
        assertEquals(addon, redactLocalStreamTokens(addon))
    }
}
