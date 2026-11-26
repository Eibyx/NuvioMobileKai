package com.nuvio.app.features.subtitledownloader.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Reads a Matroska file's headers and indexes from its [source] without touching its clusters:
 * the EBML header, the segment, every SeekHead, Info, Tracks (with CodecPrivate and encodings),
 * where the attachments are, Cues and Tags. Elements the SeekHead points at are read in parallel.
 */
internal class MkvProbe(
    private val source: ByteSource,
    /** How many bytes to ask for when walking headers whose sizes aren't known yet. */
    private val window: () -> Long,
) {
    private val notes = mutableListOf<String>()

    suspend fun probe(): MkvLayout = coroutineScope {
        val size = source.size
        // The end of the file usually holds the Cues and Tags: asked for alongside the start.
        val tailBytes = (size / 200).coerceIn(256L * 1024, 1024L * 1024).coerceAtMost(size)
        val tail = if (size > 8L * 1024 * 1024) async { runCatching { source.prefetch(listOf((size - tailBytes) until size)) } } else null

        val head = source.read(0, 64, window().coerceAtLeast(256L * 1024))
        val ebml = Ebml.header(head, 0) ?: throw MkvFormatException("file too short")
        if (ebml.id != Ebml.EBML || !ebml.sizeKnown) throw MkvFormatException("not a Matroska file")
        val ebmlContent = source.read(ebml.headerLength.toLong(), ebml.size.toInt())
        var docType = "matroska"
        Ebml.children(ebmlContent, 0, ebmlContent.size) { h, at -> if (h.id == Ebml.DOC_TYPE) docType = Ebml.string(ebmlContent, at, h.size.toInt()) }
        if (docType != "matroska" && docType != "webm") throw MkvFormatException("DocType $docType is not Matroska")

        val segmentPos = ebml.headerLength + ebml.size
        val segmentHeader = header(segmentPos) ?: throw MkvFormatException("no segment")
        if (segmentHeader.id != Ebml.SEGMENT) throw MkvFormatException("no segment after the EBML header")
        val dataPos = segmentPos + segmentHeader.headerLength
        val segmentEnd = if (segmentHeader.sizeKnown) minOf(size, dataPos + segmentHeader.size) else size
        if (segmentHeader.sizeKnown && dataPos + segmentHeader.size > size) notes += "segment runs past the end of the file"

        val found = HashMap<Long, Long>()
        val parsed = HashSet<Long>()
        var info: ByteArray? = null
        var tracks: ByteArray? = null
        var cues: ByteArray? = null
        var tags = mutableListOf<ByteArray>()
        var attachments: LongRange? = null
        var firstCluster: Long? = null
        val seekTargets = HashMap<Long, Long>()

        suspend fun take(id: Long, position: Long, header: Ebml.Header) {
            val content = position + header.headerLength
            if (!header.sizeKnown) return
            when (id) {
                Ebml.SEEK_HEAD -> readSeekHead(source.read(content, header.size.toInt()), dataPos, seekTargets)
                Ebml.INFO -> info = source.read(content, header.size.toInt())
                Ebml.TRACKS -> tracks = source.read(content, header.size.toInt())
                Ebml.CUES -> cues = source.read(content, header.size.toInt(), header.size)
                Ebml.TAGS -> tags += source.read(content, header.size.toInt(), header.size)
                Ebml.ATTACHMENTS -> attachments = content until content + header.size
            }
            parsed += position
        }

        // The level-1 elements before the first cluster, in order.
        var at = dataPos
        while (at < segmentEnd) {
            val h = header(at) ?: break
            if (h.id == Ebml.CLUSTER) {
                firstCluster = at
                break
            }
            found[at] = h.id
            if (h.id in Ebml.LEVEL1) take(h.id, at, h)
            if (!h.sizeKnown) {
                notes += "level-1 element ${h.id.toString(16)} of unknown size"
                break
            }
            at += h.headerLength + h.size
        }

        tail?.await()
        // Whatever the SeekHeads point at that the walk didn't reach (often Cues and Tags at the end).
        var rounds = 0
        while (rounds++ < 4) {
            val pending = seekTargets.filter { (pos, id) -> pos !in parsed && pos < segmentEnd && id in WANTED }
            if (pending.isEmpty()) break
            pending.map { (pos, id) ->
                async {
                    val h = header(pos) ?: return@async null
                    if (h.id != id) {
                        notes += "SeekHead points at ${id.toString(16)} but finds ${h.id.toString(16)}"
                        null
                    } else {
                        Triple(id, pos, h)
                    }
                }
            }.mapNotNull { it.await() }.forEach { (id, pos, h) ->
                if (pos !in parsed) take(id, pos, h)
                parsed += pos
            }
        }

        val infoValues = parseInfo(info)
        val trackList = tracks?.let(::parseTracks) ?: throw MkvFormatException("no Tracks")
        MkvLayout(
            fileSize = size,
            segmentDataPosition = dataPos,
            segmentEnd = segmentEnd,
            timestampScale = infoValues.scale,
            segmentUid = infoValues.uid,
            durationTicks = infoValues.duration,
            title = infoValues.title,
            tracks = trackList,
            attachmentsContent = attachments,
            cues = cues?.let(::parseCues),
            statistics = tags.fold(HashMap()) { acc, t -> acc.apply { putAll(parseStatistics(t)) } },
            firstClusterPosition = firstCluster,
            notes = notes.toList(),
        )
    }

    /** The files in the Attachments element: names, types and where their bytes are. */
    suspend fun listAttachments(content: LongRange): List<MkvAttachment> {
        val out = mutableListOf<MkvAttachment>()
        var at = content.first
        while (at <= content.last) {
            val h = header(at) ?: break
            if (!h.sizeKnown) throw MkvFormatException("attachment of unknown size")
            val end = at + h.headerLength + h.size
            if (h.id == Ebml.ATTACHED_FILE) {
                var name: String? = null
                var mime: String? = null
                var description: String? = null
                var uid: Long? = null
                var dataPos = -1L
                var dataSize = 0L
                var child = at + h.headerLength
                while (child < end) {
                    val c = header(child) ?: throw MkvFormatException("truncated attachment")
                    val value = child + c.headerLength
                    when (c.id) {
                        Ebml.FILE_DATA -> {
                            dataPos = value
                            dataSize = c.size
                        }
                        Ebml.FILE_NAME, Ebml.FILE_MIME_TYPE, Ebml.FILE_DESCRIPTION, Ebml.FILE_UID -> {
                            val bytes = source.read(value, c.size.toInt(), window())
                            when (c.id) {
                                Ebml.FILE_NAME -> name = Ebml.string(bytes, 0, bytes.size)
                                Ebml.FILE_MIME_TYPE -> mime = Ebml.string(bytes, 0, bytes.size)
                                Ebml.FILE_DESCRIPTION -> description = Ebml.string(bytes, 0, bytes.size)
                                else -> uid = Ebml.uint(bytes, 0, bytes.size)
                            }
                        }
                    }
                    child = value + c.size
                }
                if (dataPos >= 0) {
                    out += MkvAttachment(out.size, name ?: "attachment-${out.size}", mime, description, uid, dataPos, dataSize)
                }
            }
            at = end
        }
        return out
    }

    private suspend fun header(position: Long): Ebml.Header? {
        if (position >= source.size) return null
        val bytes = source.read(position, 12, window())
        return Ebml.header(bytes, 0)
    }

    private fun readSeekHead(data: ByteArray, dataPos: Long, into: MutableMap<Long, Long>) {
        Ebml.children(data, 0, data.size) { h, at ->
            if (h.id != Ebml.SEEK) return@children
            var id = 0L
            var position = -1L
            Ebml.children(data, at, at + h.size.toInt()) { c, v ->
                when (c.id) {
                    Ebml.SEEK_ID -> id = Ebml.uint(data, v, c.size.toInt())
                    Ebml.SEEK_POSITION -> position = Ebml.uint(data, v, c.size.toInt())
                }
            }
            if (id != 0L && position >= 0) into[dataPos + position] = id
        }
    }

    private class InfoValues(val scale: Long, val duration: Double?, val uid: ByteArray?, val title: String?)

    private fun parseInfo(data: ByteArray?): InfoValues {
        if (data == null) {
            notes += "no Info: assuming the default timestamp scale"
            return InfoValues(1_000_000, null, null, null)
        }
        var scale = 1_000_000L
        var duration: Double? = null
        var uid: ByteArray? = null
        var title: String? = null
        Ebml.children(data, 0, data.size) { h, at ->
            val n = h.size.toInt()
            when (h.id) {
                Ebml.TIMESTAMP_SCALE -> scale = Ebml.uint(data, at, n)
                Ebml.DURATION -> duration = Ebml.float(data, at, n)
                Ebml.SEGMENT_UID -> uid = data.copyOfRange(at, at + n)
                Ebml.TITLE -> title = Ebml.string(data, at, n)
            }
        }
        return InfoValues(scale.takeIf { it > 0 } ?: 1_000_000, duration, uid, title)
    }

    private fun parseTracks(data: ByteArray): List<MkvTrack> {
        val tracks = mutableListOf<MkvTrack>()
        Ebml.children(data, 0, data.size) { entry, entryAt ->
            if (entry.id != Ebml.TRACK_ENTRY) return@children
            var number = 0L
            var uid = 0L
            var type = 0L
            var codec = ""
            var name: String? = null
            var language: String? = "eng"
            var bcp47: String? = null
            var isDefault = true
            var forced = false
            var private: ByteArray? = null
            val encodings = mutableListOf<ContentEncoding>()
            Ebml.children(data, entryAt, entryAt + entry.size.toInt()) { h, at ->
                val n = h.size.toInt()
                when (h.id) {
                    Ebml.TRACK_NUMBER -> number = Ebml.uint(data, at, n)
                    Ebml.TRACK_UID -> uid = Ebml.uint(data, at, n)
                    Ebml.TRACK_TYPE -> type = Ebml.uint(data, at, n)
                    Ebml.CODEC_ID -> codec = Ebml.string(data, at, n)
                    Ebml.NAME -> name = Ebml.string(data, at, n)
                    Ebml.LANGUAGE -> language = Ebml.string(data, at, n)
                    Ebml.LANGUAGE_BCP47 -> bcp47 = Ebml.string(data, at, n)
                    Ebml.FLAG_DEFAULT -> isDefault = Ebml.uint(data, at, n) != 0L
                    Ebml.FLAG_FORCED -> forced = Ebml.uint(data, at, n) != 0L
                    Ebml.CODEC_PRIVATE -> private = data.copyOfRange(at, at + n)
                    Ebml.CONTENT_ENCODINGS -> Ebml.children(data, at, at + n) { e, eAt ->
                        if (e.id == Ebml.CONTENT_ENCODING) encodings += parseEncoding(data, eAt, eAt + e.size.toInt())
                    }
                }
            }
            tracks += MkvTrack(number, uid, type, codec, name, language, bcp47, isDefault, forced, private, encodings)
        }
        return tracks
    }

    private fun parseEncoding(data: ByteArray, from: Int, to: Int): ContentEncoding {
        var order = 0L
        var scope = 1L
        var type = 0L
        var algorithm = ContentEncoding.ZLIB
        var settings: ByteArray? = null
        Ebml.children(data, from, to) { h, at ->
            val n = h.size.toInt()
            when (h.id) {
                Ebml.CONTENT_ENCODING_ORDER -> order = Ebml.uint(data, at, n)
                Ebml.CONTENT_ENCODING_SCOPE -> scope = Ebml.uint(data, at, n)
                Ebml.CONTENT_ENCODING_TYPE -> type = Ebml.uint(data, at, n)
                Ebml.CONTENT_COMPRESSION -> Ebml.children(data, at, at + n) { c, cAt ->
                    when (c.id) {
                        Ebml.CONTENT_COMP_ALGO -> algorithm = Ebml.uint(data, cAt, c.size.toInt())
                        Ebml.CONTENT_COMP_SETTINGS -> settings = data.copyOfRange(cAt, cAt + c.size.toInt())
                    }
                }
                Ebml.CONTENT_ENCRYPTION -> type = 1L
            }
        }
        return ContentEncoding(order, scope, type, algorithm, settings)
    }

    private fun parseCues(data: ByteArray): List<CueEntry> {
        val out = ArrayList<CueEntry>()
        Ebml.children(data, 0, data.size) { point, pointAt ->
            if (point.id != Ebml.CUE_POINT) return@children
            var time = -1L
            val positions = mutableListOf<Pair<Int, Int>>()
            Ebml.children(data, pointAt, pointAt + point.size.toInt()) { h, at ->
                when (h.id) {
                    Ebml.CUE_TIME -> time = Ebml.uint(data, at, h.size.toInt())
                    Ebml.CUE_TRACK_POSITIONS -> positions += at to at + h.size.toInt()
                }
            }
            if (time < 0) return@children
            positions.forEach { (from, to) ->
                var track = -1L
                var cluster = -1L
                var relative: Long? = null
                var duration: Long? = null
                var block: Long? = null
                Ebml.children(data, from, to) { h, at ->
                    val n = h.size.toInt()
                    when (h.id) {
                        Ebml.CUE_TRACK -> track = Ebml.uint(data, at, n)
                        Ebml.CUE_CLUSTER_POSITION -> cluster = Ebml.uint(data, at, n)
                        Ebml.CUE_RELATIVE_POSITION -> relative = Ebml.uint(data, at, n)
                        Ebml.CUE_DURATION -> duration = Ebml.uint(data, at, n)
                        Ebml.CUE_BLOCK_NUMBER -> block = Ebml.uint(data, at, n)
                    }
                }
                if (track >= 0 && cluster >= 0) out += CueEntry(time, track, cluster, relative, duration, block)
            }
        }
        return out
    }

    /** mkvmerge's statistics tags: NUMBER_OF_FRAMES and NUMBER_OF_BYTES of each track, by its UID. */
    private fun parseStatistics(data: ByteArray): Map<Long, TrackStatistics> {
        val out = HashMap<Long, TrackStatistics>()
        Ebml.children(data, 0, data.size) { tag, tagAt ->
            if (tag.id != Ebml.TAG) return@children
            val uids = mutableListOf<Long>()
            var frames: Long? = null
            var bytes: Long? = null
            Ebml.children(data, tagAt, tagAt + tag.size.toInt()) { h, at ->
                when (h.id) {
                    Ebml.TARGETS -> Ebml.children(data, at, at + h.size.toInt()) { t, tAt ->
                        if (t.id == Ebml.TAG_TRACK_UID) uids += Ebml.uint(data, tAt, t.size.toInt())
                    }
                    Ebml.SIMPLE_TAG -> {
                        var name: String? = null
                        var value: String? = null
                        Ebml.children(data, at, at + h.size.toInt()) { s, sAt ->
                            when (s.id) {
                                Ebml.TAG_NAME -> name = Ebml.string(data, sAt, s.size.toInt())
                                Ebml.TAG_STRING -> value = Ebml.string(data, sAt, s.size.toInt())
                            }
                        }
                        when (name?.uppercase()) {
                            "NUMBER_OF_FRAMES" -> frames = value?.trim()?.toLongOrNull()
                            "NUMBER_OF_BYTES" -> bytes = value?.trim()?.toLongOrNull()
                        }
                    }
                }
            }
            if (frames != null || bytes != null) uids.forEach { out[it] = TrackStatistics(frames, bytes) }
        }
        return out
    }

    private companion object {
        val WANTED = setOf(Ebml.INFO, Ebml.TRACKS, Ebml.CUES, Ebml.TAGS, Ebml.ATTACHMENTS, Ebml.SEEK_HEAD)
    }
}
