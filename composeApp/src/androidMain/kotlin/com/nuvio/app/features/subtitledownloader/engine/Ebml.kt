package com.nuvio.app.features.subtitledownloader.engine

/** Matroska's EBML: element ids, sizes and values, read from bytes that sit at a known file offset. */
internal object Ebml {
    const val UNKNOWN_SIZE = -1L

    // Top level.
    const val EBML = 0x1A45DFA3L
    const val DOC_TYPE = 0x4282L
    const val SEGMENT = 0x18538067L

    // Level 1.
    const val SEEK_HEAD = 0x114D9B74L
    const val INFO = 0x1549A966L
    const val TRACKS = 0x1654AE6BL
    const val CHAPTERS = 0x1043A770L
    const val CLUSTER = 0x1F43B675L
    const val CUES = 0x1C53BB6BL
    const val ATTACHMENTS = 0x1941A469L
    const val TAGS = 0x1254C367L

    val LEVEL1 = setOf(SEEK_HEAD, INFO, TRACKS, CHAPTERS, CLUSTER, CUES, ATTACHMENTS, TAGS)

    // Global.
    const val VOID = 0xECL
    const val CRC32 = 0xBFL

    // SeekHead.
    const val SEEK = 0x4DBBL
    const val SEEK_ID = 0x53ABL
    const val SEEK_POSITION = 0x53ACL

    // Info.
    const val TIMESTAMP_SCALE = 0x2AD7B1L
    const val DURATION = 0x4489L
    const val SEGMENT_UID = 0x73A4L
    const val TITLE = 0x7BA9L

    // Tracks.
    const val TRACK_ENTRY = 0xAEL
    const val TRACK_NUMBER = 0xD7L
    const val TRACK_UID = 0x73C5L
    const val TRACK_TYPE = 0x83L
    const val FLAG_DEFAULT = 0x88L
    const val FLAG_FORCED = 0x55AAL
    const val NAME = 0x536EL
    const val LANGUAGE = 0x22B59CL
    const val LANGUAGE_BCP47 = 0x22B59DL
    const val CODEC_ID = 0x86L
    const val CODEC_PRIVATE = 0x63A2L
    const val DEFAULT_DURATION = 0x23E383L
    const val CONTENT_ENCODINGS = 0x6D80L
    const val CONTENT_ENCODING = 0x6240L
    const val CONTENT_ENCODING_ORDER = 0x5031L
    const val CONTENT_ENCODING_SCOPE = 0x5032L
    const val CONTENT_ENCODING_TYPE = 0x5033L
    const val CONTENT_COMPRESSION = 0x5034L
    const val CONTENT_COMP_ALGO = 0x4254L
    const val CONTENT_COMP_SETTINGS = 0x4255L
    const val CONTENT_ENCRYPTION = 0x5035L

    // Clusters.
    const val TIMESTAMP = 0xE7L
    const val SIMPLE_BLOCK = 0xA3L
    const val BLOCK_GROUP = 0xA0L
    const val BLOCK = 0xA1L
    const val BLOCK_DURATION = 0x9BL

    // Cues.
    const val CUE_POINT = 0xBBL
    const val CUE_TIME = 0xB3L
    const val CUE_TRACK_POSITIONS = 0xB7L
    const val CUE_TRACK = 0xF7L
    const val CUE_CLUSTER_POSITION = 0xF1L
    const val CUE_RELATIVE_POSITION = 0xF0L
    const val CUE_DURATION = 0xB2L
    const val CUE_BLOCK_NUMBER = 0x5378L

    // Attachments.
    const val ATTACHED_FILE = 0x61A7L
    const val FILE_DESCRIPTION = 0x467EL
    const val FILE_NAME = 0x466EL
    const val FILE_MIME_TYPE = 0x4660L
    const val FILE_DATA = 0x465CL
    const val FILE_UID = 0x46AEL

    // Tags.
    const val TAG = 0x7373L
    const val TARGETS = 0x63C0L
    const val TAG_TRACK_UID = 0x63C5L
    const val SIMPLE_TAG = 0x67C8L
    const val TAG_NAME = 0x45A3L
    const val TAG_STRING = 0x4487L

    /** An element's id, its size (or [UNKNOWN_SIZE]) and how many bytes the two take. */
    class Header(val id: Long, val size: Long, val headerLength: Int) {
        val sizeKnown: Boolean get() = size != UNKNOWN_SIZE
    }

    /** How many bytes the variable-length number starting with [first] takes, or 0 when it is not one. */
    fun vintLength(first: Int): Int {
        val value = first and 0xFF
        if (value == 0) return 0
        return Integer.numberOfLeadingZeros(value) - 23
    }

    /**
     * The element header at [offset] in [data], or null when [data] ends before it does.
     * Throws [MkvFormatException] when the bytes are not an element header.
     */
    fun header(data: ByteArray, offset: Int, limit: Int = data.size): Header? {
        if (offset >= limit) return null
        val idLength = vintLength(data[offset].toInt())
        if (idLength !in 1..4) throw MkvFormatException("not an element id at byte $offset")
        if (offset + idLength >= limit) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (data[offset + i].toLong() and 0xFF)
        val sizeOffset = offset + idLength
        val sizeLength = vintLength(data[sizeOffset].toInt())
        if (sizeLength !in 1..8) throw MkvFormatException("not an element size at byte $sizeOffset")
        if (sizeOffset + sizeLength > limit) return null
        var size = (data[sizeOffset].toLong() and (0xFFL shr sizeLength))
        var allOnes = size == (0xFFL shr sizeLength)
        for (i in 1 until sizeLength) {
            val b = data[sizeOffset + i].toLong() and 0xFF
            if (b != 0xFFL) allOnes = false
            size = (size shl 8) or b
        }
        return Header(id, if (allOnes) UNKNOWN_SIZE else size, idLength + sizeLength)
    }

    /** A Matroska variable-length number without its marker (track numbers, lace sizes): value and length. */
    fun vint(data: ByteArray, offset: Int, limit: Int = data.size): Pair<Long, Int>? {
        if (offset >= limit) return null
        val length = vintLength(data[offset].toInt())
        if (length !in 1..8) throw MkvFormatException("not a number at byte $offset")
        if (offset + length > limit) return null
        var value = data[offset].toLong() and (0xFFL shr length)
        for (i in 1 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value to length
    }

    fun uint(data: ByteArray, offset: Int, length: Int): Long {
        if (length > 8) throw MkvFormatException("integer of $length bytes")
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    fun float(data: ByteArray, offset: Int, length: Int): Double = when (length) {
        4 -> java.lang.Float.intBitsToFloat(uint(data, offset, 4).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(data, offset, 8))
        0 -> 0.0
        else -> throw MkvFormatException("float of $length bytes")
    }

    fun string(data: ByteArray, offset: Int, length: Int): String =
        String(data, offset, length, Charsets.UTF_8).trimEnd('\u0000')

    /** The children of a master element whose content is wholly in [data] from [from] to [to]. */
    inline fun children(data: ByteArray, from: Int, to: Int, block: (Header, Int) -> Unit) {
        var at = from
        while (at < to) {
            val header = header(data, at, to) ?: throw MkvFormatException("truncated element at byte $at")
            val content = at + header.headerLength
            if (!header.sizeKnown) throw MkvFormatException("unknown size inside a sized element")
            if (content + header.size > to) throw MkvFormatException("element overruns its parent at byte $at")
            block(header, content)
            at = content + header.size.toInt()
        }
    }
}

internal class MkvFormatException(message: String) : java.io.IOException(message)
