package com.nuvio.app.features.subtitledownloader.engine

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A SimpleBlock's or Block's frames, with its track and time relative to its cluster. */
internal class ParsedBlock(val track: Long, val relativeTimestamp: Int, val flags: Int, val frames: List<ByteArray>) {
    val laced: Boolean get() = flags and 0x06 != 0
}

/** One subtitle event as stored: its ReadOrder and the fields after it, still raw bytes. */
internal class AssEvent(
    val track: Long,
    val readOrder: Long?,
    /** Absolute, in the segment's timestamp ticks. */
    val start: Long,
    /** In ticks; null when the file gave none (BlockDuration missing). */
    val duration: Long?,
    /** Everything after ReadOrder's comma: Layer, Style, … Text. */
    val fields: ByteArray,
    /** Where the block sits in the file, to tell copies of one event from different events. */
    val position: Long,
    val frameIndex: Int,
)

internal object Blocks {
    /** The track number at the start of a block's content, and the bytes it takes. */
    fun trackOf(data: ByteArray, offset: Int, limit: Int): Pair<Long, Int>? = Ebml.vint(data, offset, limit)

    fun parse(data: ByteArray, offset: Int, length: Int): ParsedBlock {
        val limit = offset + length
        val (track, trackLength) = Ebml.vint(data, offset, limit) ?: throw MkvFormatException("truncated block")
        var at = offset + trackLength
        if (at + 3 > limit) throw MkvFormatException("truncated block header")
        val timestamp = ((data[at].toInt() shl 8) or (data[at + 1].toInt() and 0xFF)).toShort().toInt()
        val flags = data[at + 2].toInt() and 0xFF
        at += 3
        val lacing = (flags shr 1) and 0x03
        if (lacing == 0) return ParsedBlock(track, timestamp, flags, listOf(data.copyOfRange(at, limit)))
        if (at >= limit) throw MkvFormatException("truncated lacing")
        val count = (data[at].toInt() and 0xFF) + 1
        at++
        val sizes = LongArray(count)
        when (lacing) {
            1 -> { // Xiph
                for (i in 0 until count - 1) {
                    var size = 0L
                    while (true) {
                        if (at >= limit) throw MkvFormatException("truncated Xiph lacing")
                        val b = data[at++].toInt() and 0xFF
                        size += b
                        if (b != 255) break
                    }
                    sizes[i] = size
                }
            }
            3 -> { // EBML
                val (first, firstLength) = Ebml.vint(data, at, limit) ?: throw MkvFormatException("truncated EBML lacing")
                at += firstLength
                sizes[0] = first
                for (i in 1 until count - 1) {
                    val (raw, rawLength) = Ebml.vint(data, at, limit) ?: throw MkvFormatException("truncated EBML lacing")
                    at += rawLength
                    val bias = (1L shl (7 * rawLength - 1)) - 1
                    sizes[i] = sizes[i - 1] + (raw - bias)
                }
            }
            2 -> { // fixed
                val each = (limit - at).toLong() / count
                if (each * count != (limit - at).toLong()) throw MkvFormatException("uneven fixed lacing")
                for (i in 0 until count) sizes[i] = each
            }
        }
        if (lacing != 2) {
            val used = sizes.take(count - 1).sum()
            sizes[count - 1] = (limit - at) - used
        }
        val frames = ArrayList<ByteArray>(count)
        for (size in sizes) {
            if (size < 0 || at + size > limit) throw MkvFormatException("bad lace sizes")
            frames += data.copyOfRange(at, at + size.toInt())
            at += size.toInt()
        }
        return ParsedBlock(track, timestamp, flags, frames)
    }

    /** A frame with the track's encodings undone: zlib inflated, stripped header bytes put back. */
    fun decode(frame: ByteArray, track: MkvTrack): ByteArray {
        var data = frame
        for (encoding in track.frameDecodings) {
            encoding.unsupportedReason?.let { throw MkvFormatException("track ${track.number} is $it") }
            data = when (encoding.compressionAlgorithm) {
                ContentEncoding.ZLIB -> inflate(data)
                ContentEncoding.HEADER_STRIPPING -> (encoding.compressionSettings ?: ByteArray(0)) + data
                else -> data
            }
        }
        return data
    }

    /** CodecPrivate as the track's encodings left it, decoded. */
    fun codecPrivate(track: MkvTrack): ByteArray? {
        var data = track.codecPrivateRaw ?: return null
        for (encoding in track.encodings.filter { it.appliesToCodecPrivate }.sortedByDescending { it.order }) {
            if (encoding.isEncryption) throw MkvFormatException("encrypted CodecPrivate")
            data = when (encoding.compressionAlgorithm) {
                ContentEncoding.ZLIB -> inflate(data)
                ContentEncoding.HEADER_STRIPPING -> (encoding.compressionSettings ?: ByteArray(0)) + data
                else -> throw MkvFormatException("CodecPrivate compressed with ${encoding.compressionAlgorithm}")
            }
        }
        return data
    }

    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(maxOf(64, data.size * 3))
            val chunk = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw MkvFormatException("truncated zlib frame")
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw MkvFormatException("bad zlib frame: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /** The events of one subtitle block, timed from its cluster's [clusterTimestamp]. */
    fun events(block: ParsedBlock, track: MkvTrack, clusterTimestamp: Long, duration: Long?, position: Long): List<AssEvent> =
        block.frames.mapIndexed { index, frame ->
            val decoded = decode(frame, track)
            val comma = decoded.indexOf(','.code.toByte())
            val readOrder = if (comma > 0) String(decoded, 0, comma, Charsets.US_ASCII).trim().toLongOrNull() else null
            val fields = if (readOrder != null) decoded.copyOfRange(comma + 1, decoded.size) else decoded
            AssEvent(track.number, readOrder, clusterTimestamp + block.relativeTimestamp, duration, fields, position, index)
        }
}
