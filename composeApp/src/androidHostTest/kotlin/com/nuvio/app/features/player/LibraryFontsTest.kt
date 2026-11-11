package com.nuvio.app.features.player

import com.nuvio.app.features.subtitlelibrary.LibraryFont
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFontsTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var cache: File

    @Before
    fun setUp() {
        SubtitleFontFiles.initialize(context)
        File(SubtitleFontFiles.directoryPath()!!, "dir_Base.ttf").writeBytes(ByteArray(10) { 1 })
        cache = File(context.cacheDir, "kai-subtitle-library/terabox").apply { mkdirs() }
        LibraryFontRegistry.clear()
    }

    @After
    fun tearDown() {
        LibraryFontRegistry.clear()
        File(SubtitleFontFiles.directoryPath()!!).deleteRecursively()
        MpvLibraryFonts.activeDir(context).deleteRecursively()
        cache.deleteRecursively()
    }

    private fun font(name: String, size: Int): LibraryFont {
        val file = File(cache, "$name.ttf").apply { writeBytes(ByteArray(size) { 2 }) }
        return LibraryFont("$name.ttf", file.absolutePath)
    }

    private fun activeNames(): Set<String> = MpvLibraryFonts.activeDir(context).listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun mpvDirectoryHoldsTheBaseFontsAndOnlyTheActiveSubtitlesFonts() {
        val episode1 = "file://${cache.absolutePath}/ep1.ass"
        val episode2 = "file://${cache.absolutePath}/ep2.ass"
        LibraryFontRegistry.register(episode1, listOf(font("FuenteA", 20), font("FuenteA-Bold", 21)))
        LibraryFontRegistry.register(episode2, listOf(font("FuenteB", 30)))

        val path = MpvLibraryFonts.prepare(context, episode1)
        assertEquals(MpvLibraryFonts.activeDir(context).absolutePath, path)
        assertEquals(setOf("dir_Base.ttf", "kai-lib-0-FuenteA.ttf", "kai-lib-1-FuenteA-Bold.ttf"), activeNames())

        // The next episode's subtitle: nothing of the previous one stays.
        MpvLibraryFonts.prepare(context, episode2)
        assertEquals(setOf("dir_Base.ttf", "kai-lib-0-FuenteB.ttf"), activeNames())

        // Any other subtitle (an add-on's, an embedded track after loadfile): only the base fonts.
        MpvLibraryFonts.prepare(context, null)
        assertEquals(setOf("dir_Base.ttf"), activeNames())
        assertTrue(MpvLibraryFonts.activeDir(context).listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun exoPlayerFindsTheFontsByTheSidecarTrackId() {
        val url = "file://${cache.absolutePath}/ep1.ass"
        val fonts = listOf(font("FuenteA", 12))
        LibraryFontRegistry.register(url, fonts)
        val trackId = addonSubtitleTrackId(url)
        assertEquals(fonts, LibraryFontRegistry.fontsForTrackId(trackId))
        // Later track groups may prefix the id.
        assertEquals(fonts, LibraryFontRegistry.fontsForTrackId("1:$trackId"))
        assertTrue(LibraryFontRegistry.fontsForTrackId(addonSubtitleTrackId("file:///other.ass")).isEmpty())

        val added = mutableListOf<Pair<String, Int>>()
        assertEquals(1, LibraryFontRegistry.addFontsFor(trackId) { name, data -> added += name to data.size })
        assertEquals(listOf("FuenteA.ttf" to 12), added)

        LibraryFontRegistry.clear()
        assertTrue(LibraryFontRegistry.isEmpty())
        assertEquals(0, LibraryFontRegistry.addFontsFor(trackId) { _, _ -> error("no fonts expected") })
    }
}
