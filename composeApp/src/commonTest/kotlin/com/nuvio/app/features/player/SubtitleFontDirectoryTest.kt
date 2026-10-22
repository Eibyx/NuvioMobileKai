package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleFontDirectoryTest {
    /** A minimal TrueType file whose name table holds [family]. */
    private fun font(family: String): ByteArray {
        val name = family.flatMap { listOf((it.code shr 8).toByte(), it.code.toByte()) }
        val nameTable = listOf<Byte>(0, 0, 0, 1, 0, 18) +
            listOf<Byte>(0, 3, 0, 1, 0x04, 0x09, 0, 1, 0, name.size.toByte(), 0, 0) +
            name
        val header = listOf<Byte>(0, 1, 0, 0, 0, 1, 0, 16, 0, 0, 0, 0) +
            "name".encodeToByteArray().toList() + listOf<Byte>(0, 0, 0, 0, 0, 0, 0, 28) +
            listOf<Byte>(0, 0, 0, nameTable.size.toByte())
        return (header + nameTable).toByteArray()
    }

    @Test
    fun onlyRealFontsAreValid() {
        assertTrue(isValidFontFile(font("Open Sans")))
        assertFalse(isValidFontFile("not a font at all".encodeToByteArray()))
        assertFalse(isValidFontFile(ByteArray(4)))
    }

    @Test
    fun fontExtensionsIncludeCollections() {
        listOf("A.ttf", "B.OTF", "c.ttc", "d.otc").forEach { assertTrue(hasFontFileExtension(it), it) }
        listOf("readme.txt", "font.woff2", "ttf").forEach { assertFalse(hasFontFileExtension(it), it) }
    }

    @Test
    fun aNestedFolderBecomesOneFlatFolder() {
        val namer = FlatFontNamer()
        // Fonts/Open Sans/Regular.ttf and Fonts/Roboto/Regular.ttf: same name, different fonts.
        assertEquals(FontPlacement.Copy("dir_Regular.ttf"), namer.place("Regular.ttf", "hash-a", isFont = true))
        assertEquals(FontPlacement.Copy("dir_Regular (2).ttf"), namer.place("Regular.ttf", "hash-b", isFont = true))
        assertEquals(FontPlacement.Copy("dir_regular (3).ttf"), namer.place("regular.TTF", "hash-c", isFont = true))
        // The same file again, in another subfolder or under another name: copied once.
        assertEquals(FontPlacement.Identical("dir_Regular.ttf"), namer.place("Copy of Regular.ttf", "hash-a", isFont = true))
        assertEquals(FontPlacement.NotAFont, namer.place("Broken.ttf", "hash-d", isFont = false))
    }

    @Test
    fun aReloadKeepsWhatWasCopiedBefore() {
        val namer = FlatFontNamer(mapOf("dir_Regular.ttf" to "hash-a"))
        assertEquals(FontPlacement.Identical("dir_Regular.ttf"), namer.place("Regular.ttf", "hash-a", isFont = true))
        assertEquals(FontPlacement.Copy("dir_Regular (2).ttf"), namer.place("Regular.ttf", "hash-b", isFont = true))
    }

    @Test
    fun unsafeCharactersAreReplaced() {
        val placement = FlatFontNamer().place("Font:Name/Bold?.otf", "h", isFont = true)
        assertEquals(FontPlacement.Copy("dir_Font_Name_Bold_.otf"), placement)
    }

    @Test
    fun sizesAreReadable() {
        assertEquals("512 B", formatByteSize(512))
        assertEquals("1.5 KB", formatByteSize(1536))
        assertEquals("12.3 MB", formatByteSize(12_900_000))
        assertEquals("2 GB", formatByteSize(2L * 1024 * 1024 * 1024))
    }
}
