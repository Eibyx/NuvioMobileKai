package com.nuvio.app.features.subtitledownloader.engine

/** What walking one cluster found. */
internal class ClusterScan(
    val position: Long,
    /** Absolute end: where the next level-1 element starts. */
    val end: Long,
    val sizeKnown: Boolean,
    val events: List<AssEvent>,
    /** Subtitle blocks seen per track, read or not. */
    val blocks: Map<Long, Int>,
    val laced: Int,
)

/**
 * Walks a cluster's children by their headers alone: subtitle blocks of the [wanted] tracks are
 * read whole, every other block (audio, video, other subtitles) is stepped over by its size, so its
 * bytes are fetched only when they fall inside a window worth reading through.
 */
internal class ClusterWalker(
    private val source: ByteSource,
    private val layout: MkvLayout,
    private val wanted: Map<Long, MkvTrack>,
    private val window: () -> Long,
) {
    suspend fun scan(position: Long): ClusterScan {
        val head = source.read(position, 12, window())
        val header = Ebml.header(head, 0) ?: throw MkvFormatException("truncated cluster at $position")
        if (header.id != Ebml.CLUSTER) throw MkvFormatException("no cluster at $position")
        val dataStart = position + header.headerLength
        var end: Long? = if (header.sizeKnown) minOf(layout.segmentEnd, dataStart + header.size) else null
        val limit = end ?: layout.segmentEnd
        var timestamp: Long? = null
        val pending = mutableListOf<Pending>()
        val blocks = HashMap<Long, Int>()
        var laced = 0
        var at = dataStart
        while (at < limit) {
            val bytes = source.read(at, 12, window())
            val child = Ebml.header(bytes, 0) ?: break
            if (end == null && child.id in Ebml.LEVEL1) {
                end = at
                break
            }
            if (!child.sizeKnown) throw MkvFormatException("cluster child of unknown size at $at")
            val content = at + child.headerLength
            val next = content + child.size
            when (child.id) {
                Ebml.TIMESTAMP -> timestamp = Ebml.uint(source.read(content, child.size.toInt(), window()), 0, child.size.toInt())
                Ebml.SIMPLE_BLOCK -> {
                    val track = trackAt(content, child.size)
                    if (track != null && layout.track(track)?.isSubtitle == true) blocks.merge(track, 1, Int::plus)
                    track?.let { wanted[it] }?.let { t ->
                        val data = source.read(content, child.size.toInt(), window())
                        val block = Blocks.parse(data, 0, data.size)
                        if (block.laced) laced++
                        pending += Pending(block, t, null, at)
                    }
                }
                Ebml.BLOCK_GROUP -> groupAt(content, next, at, blocks)?.let { (p, wasLaced) ->
                    pending += p
                    if (wasLaced) laced++
                }
            }
            at = next
        }
        val clusterEnd = end ?: minOf(at, layout.segmentEnd)
        if (pending.isNotEmpty() && timestamp == null) throw MkvFormatException("cluster at $position has blocks but no timestamp")
        val events = pending.flatMap { Blocks.events(it.block, it.track, timestamp ?: 0L, it.duration, it.position) }
        return ClusterScan(position, clusterEnd, header.sizeKnown, events, blocks, laced)
    }

    private class Pending(val block: ParsedBlock, val track: MkvTrack, val duration: Long?, val position: Long)

    private suspend fun trackAt(content: Long, size: Long): Long? {
        val peek = source.read(content, minOf(size, 8L).toInt(), window())
        return Blocks.trackOf(peek, 0, peek.size)?.first
    }

    /** A BlockGroup: its Block's track first, and only a wanted one is read with its duration. */
    private suspend fun groupAt(from: Long, to: Long, groupPosition: Long, blocks: MutableMap<Long, Int>): Pair<Pending, Boolean>? {
        var at = from
        var track: MkvTrack? = null
        var block: ParsedBlock? = null
        var duration: Long? = null
        while (at < to) {
            val bytes = source.read(at, 12, window())
            val child = Ebml.header(bytes, 0) ?: throw MkvFormatException("truncated block group at $at")
            if (!child.sizeKnown) throw MkvFormatException("block group child of unknown size")
            val content = at + child.headerLength
            when (child.id) {
                Ebml.BLOCK -> {
                    val number = trackAt(content, child.size) ?: return null
                    if (layout.track(number)?.isSubtitle == true) blocks.merge(number, 1, Int::plus)
                    // Not wanted: the whole group is stepped over, its track now known.
                    track = wanted[number] ?: return null
                    val data = source.read(content, child.size.toInt(), window())
                    block = Blocks.parse(data, 0, data.size)
                }
                Ebml.BLOCK_DURATION -> duration = Ebml.uint(source.read(content, child.size.toInt(), window()), 0, child.size.toInt())
            }
            at = content + child.size
        }
        val found = block ?: return null
        return Pending(found, track!!, duration, groupPosition) to found.laced
    }
}
