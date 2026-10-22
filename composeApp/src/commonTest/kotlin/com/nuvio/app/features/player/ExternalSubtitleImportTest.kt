package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalSubtitleImportTest {
    @Test
    fun onlySubtitleExtensionsAreAccepted() {
        assertEquals("srt", externalSubtitleExtension("Movie.ES.SRT"))
        assertEquals("ass", externalSubtitleExtension("[Group] Show - 01.ass"))
        assertEquals("ssa", externalSubtitleExtension("a.ssa"))
        assertEquals("vtt", externalSubtitleExtension("a.vtt"))
        assertEquals("sub", externalSubtitleExtension("a.sub"))
        assertNull(externalSubtitleExtension("a.idx"))
        assertNull(externalSubtitleExtension("movie.mkv"))
        assertNull(externalSubtitleExtension("srt"))
        assertNull(externalSubtitleExtension(null))
    }

    @Test
    fun theLanguageComesFromTheFileName() {
        assertEquals("es", externalSubtitleLanguage("Movie.es.srt"))
        assertEquals("en", externalSubtitleLanguage("Show.S01E02.eng.ass"))
        assertNull(externalSubtitleLanguage("Movie.2019.HDR.srt"))
        assertNull(externalSubtitleLanguage("Movie.srt"))
    }

    @Test
    fun utf8IsKeptAndLatinEncodingsBecomeUtf8() {
        assertEquals("¿Qué tal?", decodeSubtitleBytes("¿Qué tal?".encodeToByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Señor".encodeToByteArray()
        assertEquals("Señor", decodeSubtitleBytes(bom))
        // "¿Qué?" in Windows-1252 / ISO-8859-1.
        val latin = byteArrayOf(0xBF.toByte(), 'Q'.code.toByte(), 'u'.code.toByte(), 0xE9.toByte(), '?'.code.toByte())
        assertEquals("¿Qué?", decodeSubtitleBytes(latin))
        // Curly quotes and the euro sign only exist in Windows-1252.
        val windows = byteArrayOf(0x93.toByte(), 'H'.code.toByte(), 'i'.code.toByte(), 0x94.toByte(), ' '.code.toByte(), 0x80.toByte())
        assertEquals("“Hi” €", decodeSubtitleBytes(windows))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'O'.code.toByte(), 0, 'k'.code.toByte(), 0)
        assertEquals("Ok", decodeSubtitleBytes(utf16))
    }

    @Test
    fun binaryFilesAreNotSubtitles() {
        assertNull(decodeSubtitleBytes(byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44, 0, 4, 0)))
        assertNull(playableSubtitleText("sub", "Not a subtitle at all".encodeToByteArray()))
        assertEquals("srt", playableSubtitleText("sub", "1\n00:00:01,000 --> 00:00:02,000\nHola\n".encodeToByteArray())?.first)
    }

    @Test
    fun microDvdBecomesSubRip() {
        val microDvd = "{1}{1}25\n{25}{75}Hola|mundo\n{100}{150}{y:i}Adiós\n"
        assertTrue(isMicroDvd(microDvd))
        val (extension, srt) = playableSubtitleText("sub", microDvd.encodeToByteArray())!!
        assertEquals("srt", extension)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nHola\nmundo\n\n2\n00:00:04,000 --> 00:00:06,000\nAdiós\n\n",
            srt,
        )
    }

    @Test
    fun theEntryKeepsTheFileNameAndUsesTheRightCopyPerEngine() {
        val imported = ImportedSubtitle(
            id = "local:abc",
            displayName = "Movie.es.srt",
            language = "es",
            rawPath = "/cache/external_subtitles/abc-Movie.es.srt",
            utf8Path = "/cache/external_subtitles/abc-Movie.es.utf8.srt",
        )
        val forMpv = imported.toAddonSubtitle(useRawFile = true, sourceName = "Local file")
        assertEquals("file:///cache/external_subtitles/abc-Movie.es.srt", forMpv.url)
        assertEquals("Movie.es.srt", forMpv.display)
        assertEquals("es", forMpv.language)
        val forExo = imported.toAddonSubtitle(useRawFile = false, sourceName = "Local file")
        assertEquals("file:///cache/external_subtitles/abc-Movie.es.utf8.srt", forExo.url)
    }
}
