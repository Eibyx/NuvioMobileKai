package com.nuvio.app.features.subtitlelibrary

/** One face of a font file and the names libass can match it by (normalised like [AssFontNames.normalize]). */
internal data class FontFaceNames(
    val faceIndex: Int,
    val family: String?,
    val names: Set<String>,
)

/** Random access to a font: only the header, table directory and `name` table are read. */
internal interface FontBytes {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

internal class ByteArrayFontBytes(private val bytes: ByteArray) : FontBytes {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "out of range" }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * Reads the `name` table of TrueType/OpenType fonts and collections (`.ttf`, `.otf`, `.ttc`,
 * `.otc`): family (1), full name (4), PostScript name (6) and typographic family (16), from the
 * Windows/Unicode platforms (UTF-16BE) and the Mac platform (Mac Roman). libass matches a style's
 * font by these internal names, not by the file name.
 *
 * Ported from NuvioArcKai (GPL-3.0); the text decoding is done here so it builds for every target.
 */
internal object SfntNames {
    private const val TAG_TTCF = 0x74746366L // 'ttcf'
    private const val TAG_NAME = 0x6E616D65L // 'name'
    private const val MAX_TABLES = 512
    private const val MAX_FACES = 64
    private const val MAX_NAME_TABLE = 512 * 1024
    private val WANTED_IDS = setOf(1, 4, 6, 16)

    fun read(bytes: ByteArray): List<FontFaceNames> = read(ByteArrayFontBytes(bytes))

    /** The faces of the font; empty when it is not a readable sfnt font. Never throws. */
    fun read(font: FontBytes): List<FontFaceNames> = runCatching {
        if (font.size < 12) return emptyList()
        val head = font.read(0, 12)
        if (u32(head, 0) == TAG_TTCF) {
            val count = u32(head, 8).toInt()
            if (count <= 0 || count > MAX_FACES) return emptyList()
            val offsets = font.read(12, count * 4)
            (0 until count).mapNotNull { index -> face(font, u32(offsets, index * 4), index) }
        } else {
            listOfNotNull(face(font, 0, 0))
        }
    }.getOrDefault(emptyList())

    private fun face(font: FontBytes, offset: Long, index: Int): FontFaceNames? {
        val header = font.read(offset, 12)
        val version = u32(header, 0)
        // 0x00010000 (TrueType), 'OTTO' (CFF), 'true' (old Apple TrueType).
        if (version != 0x00010000L && version != 0x4F54544FL && version != 0x74727565L) return null
        val numTables = u16(header, 4)
        if (numTables <= 0 || numTables > MAX_TABLES) return null
        val directory = font.read(offset + 12, numTables * 16)
        var nameOffset = -1L
        var nameLength = 0L
        for (i in 0 until numTables) {
            if (u32(directory, i * 16) == TAG_NAME) {
                nameOffset = u32(directory, i * 16 + 8)
                nameLength = u32(directory, i * 16 + 12)
                break
            }
        }
        if (nameOffset < 0 || nameLength < 6 || nameLength > MAX_NAME_TABLE) return null
        if (nameOffset + nameLength > font.size) return null
        val table = font.read(nameOffset, nameLength.toInt())
        val count = u16(table, 2)
        val stringBase = u16(table, 4)
        val names = linkedSetOf<String>()
        var family: String? = null
        var typographicFamily: String? = null
        for (i in 0 until count) {
            val record = 6 + i * 12
            if (record + 12 > table.size) break
            val platform = u16(table, record)
            val encoding = u16(table, record + 2)
            val nameId = u16(table, record + 6)
            val length = u16(table, record + 8)
            val stringOffset = stringBase + u16(table, record + 10)
            if (nameId !in WANTED_IDS) continue
            if (stringOffset + length > table.size) continue
            val raw = table.copyOfRange(stringOffset, stringOffset + length)
            val value = when (platform) {
                0, 3 -> AssFontNames.utf16(raw, 0, littleEndian = false)
                1 -> if (encoding == 0) macRoman(raw) else continue
                else -> continue
            }.trim().trimEnd('\u0000')
            val normalized = AssFontNames.normalize(value) ?: continue
            names += normalized
            if (nameId == 1 && family == null) family = normalized
            if (nameId == 16 && typographicFamily == null) typographicFamily = normalized
        }
        if (names.isEmpty()) return null
        return FontFaceNames(index, typographicFamily ?: family, names)
    }

    private fun macRoman(bytes: ByteArray): String = buildString(bytes.size) {
        bytes.forEach { byte ->
            val code = byte.toInt() and 0xFF
            append(if (code < 0x80) code.toChar() else MAC_ROMAN_HIGH[code - 0x80])
        }
    }

    /** Mac OS Roman, 0x80–0xFF. */
    private const val MAC_ROMAN_HIGH =
        "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø" +
            "¿¡¬√ƒ≈∆«»…\u00A0ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ"

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    fun isFontName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS
}
