package com.nuvio.app.features.subtitlelibrary

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCacheAndRedactionTest {
    @Test
    fun `cache keeps the extension, reuses unchanged files and drops the least recently used`() {
        val root = Files.createTempDirectory("kai-sub-cache").toFile()
        var clock = 1_000_000L
        val cache = LibraryDiskCache(root, maxBytes = { 250 }, now = { clock })
        val a = cache.keyFor("file-a", 100, 5, "Episodio.ASS")
        assertTrue(a.endsWith(".ass"))
        assertNotEquals(a, cache.keyFor("file-a", 101, 5, "Episodio.ass"))
        cache.put("terabox", a) { it.write(ByteArray(100)) }
        clock += 10_000
        val b = cache.keyFor("file-b", 100, 5, "Fuente.ttf")
        cache.put("terabox", b) { it.write(ByteArray(100)) }
        clock += 10_000
        assertNotNull(cache.get("terabox", a)) // a is now the most recent
        clock += 10_000
        val c = cache.keyFor("file-c", 100, 5, "Otra.otf")
        cache.put("drive", c) { it.write(ByteArray(100)) }
        assertNotNull(cache.get("terabox", a))
        assertNull(cache.get("terabox", b))
        assertNotNull(cache.get("drive", c))
        assertTrue(cache.totalBytes() <= 250)
        root.deleteRecursively()
    }

    @Test
    fun `a failed write leaves nothing`() {
        val root = Files.createTempDirectory("kai-sub-cache").toFile()
        val cache = LibraryDiskCache(root, maxBytes = { 1000 })
        runCatching { cache.put("local", "x.ass") { error("network") } }
        assertNull(cache.get("local", "x.ass"))
        assertEquals(0L, cache.totalBytes())
        root.deleteRecursively()
    }

    @Test
    fun `links, headers and secrets never reach the log`() {
        val dlink = "https://d.terabox.com/file/abc?fid=1&sign=SECRET&expires=8h&dstime=1"
        assertEquals("https://d.terabox.com/<redacted>", LibraryRedaction.url(dlink))
        assertEquals("content://<local>", LibraryRedaction.url("content://com.android.externalstorage.documents/tree/1234"))
        assertEquals("[Authorization, Cookie]", LibraryRedaction.headers(mapOf("Cookie" to "ndus=SECRET", "Authorization" to "Bearer SECRET")))
        val message = LibraryRedaction.text("GET $dlink failed; Authorization: Bearer ya29.SECRET cookie=ndus=SECRET")
        assertFalse(message, message.contains("SECRET"))
    }
}
