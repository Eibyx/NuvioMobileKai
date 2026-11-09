package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uLinesTest {
    private val sample = """
        #EXTM3U url-tvg="https://example.com/guide.xml"
        #EXTINF:-1 tvg-id="one" tvg-logo="https://example.com/1.png" group-title="News",Channel One
        https://example.com/1.m3u8
        #EXTINF:-1 tvg-id="two" group-title="Sports",Channel Two
        https://example.com/2.m3u8
    """.trimIndent()

    @Test
    fun readingLineByLineGivesWhatTheWholeTextGives() {
        val (epg, channels) = parseM3uLines(sample.lineSequence())
        assertEquals(parseM3uEpgUrls(sample), epg)
        assertEquals(listOf("https://example.com/guide.xml"), epg)
        assertEquals(parseM3uPlaylist(sample), channels)
        assertEquals(listOf("Channel One", "Channel Two"), channels.map { it.name })
        assertEquals("News", channels.first().group)
    }

    @Test
    fun aLocalCopyIsRecognisedByItsPrefix() {
        assertEquals("/data/x.m3u", "${LOCAL_PLAYLIST_FILE_PREFIX}/data/x.m3u".localPlaylistFilePath())
        assertNull("#EXTM3U\nhttps://example.com/1.m3u8".localPlaylistFilePath())
    }
}
