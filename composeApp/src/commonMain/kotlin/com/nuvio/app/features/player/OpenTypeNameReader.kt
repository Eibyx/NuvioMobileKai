package com.nuvio.app.features.player

/**
 * Reads the family name out of a TrueType/OpenType font or collection: the name libass matches
 * fonts on. Used to validate the fonts copied from the user's font directory.
 */
internal object OpenTypeNameReader {
    private const val TAG_TTCF = 0x74746366
    private const val TAG_OTTO = 0x4F54544F
    private const val TAG_TRUE = 0x74727565
    private const val VERSION_TRUETYPE = 0x00010000
    private const val TAG_NAME = 0x6E616D65

    fun fileExtension(bytes: ByteArray): String = when (bytes.u32(0)) {
        TAG_TTCF -> "ttc"
        TAG_OTTO -> "otf"
        else -> "ttf"
    }

    fun familyName(bytes: ByteArray): String? = runCatching { readFamilyName(bytes) }.getOrNull()

    private fun readFamilyName(bytes: ByteArray): String? {
        var offset = 0
        if (bytes.u32(0) == TAG_TTCF) {
            if (bytes.u32(8) < 1) return null
            offset = bytes.u32(12)
        }
        val version = bytes.u32(offset)
        if (version != VERSION_TRUETYPE && version != TAG_OTTO && version != TAG_TRUE) return null
        val tableCount = bytes.u16(offset + 4)
        var nameOffset = -1
        for (index in 0 until tableCount) {
            val record = offset + 12 + index * 16
            if (bytes.u32(record) == TAG_NAME) {
                nameOffset = bytes.u32(record + 8)
                break
            }
        }
        if (nameOffset < 0) return null
        val count = bytes.u16(nameOffset + 2)
        val stringsStart = nameOffset + bytes.u16(nameOffset + 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (index in 0 until count) {
            val record = nameOffset + 6 + index * 12
            val platformId = bytes.u16(record)
            val languageId = bytes.u16(record + 4)
            val nameId = bytes.u16(record + 6)
            if (nameId != 1 && nameId != 16) continue
            val length = bytes.u16(record + 8)
            val start = stringsStart + bytes.u16(record + 10)
            if (start < 0 || start + length > bytes.size) continue
            val value = when (platformId) {
                0, 3 -> bytes.decodeUtf16Be(start, length)
                1 -> bytes.decodeToString(start, start + length)
                else -> continue
            }.trim()
            if (value.isEmpty()) continue
            // Prefer the legacy family (1), which libass matches on, then Windows/Unicode
            // records, then English.
            val rank = (if (nameId == 1) 0 else 4) +
                (if (platformId == 1) 2 else 0) +
                (if (languageId == 0x0409 || languageId == 0) 0 else 1)
            if (rank < bestRank) {
                bestRank = rank
                best = value
            }
        }
        return best
    }

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.u32(at: Int): Int =
        (u16(at) shl 16) or u16(at + 2)

    private fun ByteArray.decodeUtf16Be(start: Int, length: Int): String {
        val chars = CharArray(length / 2) { index -> Char(u16(start + index * 2)) }
        return chars.concatToString()
    }
}
