package com.nuvio.app.features.subtitledownloader.engine

import java.io.File
import java.util.TreeMap

/**
 * Byte ranges of one version of one remote file, kept so nothing is fetched twice: in memory up
 * to [memoryLimit] bytes, the rest on disk up to [diskLimit] bytes, under a folder named after the
 * version, so a different file (or a changed one behind the same link) never reads another's bytes.
 */
internal class SparseCache(
    val versionKey: String,
    private val memoryLimit: Long,
    private val diskDirectory: File?,
    private val diskLimit: Long,
) {
    private val lock = Any()
    private val memory = TreeMap<Long, ByteArray>()
    private val disk = TreeMap<Long, Long>()
    private val lastUse = HashMap<Long, Long>()
    private var memoryBytes = 0L
    private var tick = 0L

    init {
        diskDirectory?.listFiles()?.forEach { file ->
            val parts = file.name.removeSuffix(".bin").split('-')
            val start = parts.getOrNull(0)?.toLongOrNull()
            val length = parts.getOrNull(1)?.toLongOrNull()
            if (file.name.endsWith(".bin") && start != null && length != null && file.length() == length) {
                disk[start] = length
            } else {
                file.delete()
            }
        }
    }

    fun put(start: Long, data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            memory[start] = data
            memoryBytes += data.size
            lastUse[start] = ++tick
            evict()
        }
    }

    /** The bytes from [start] to [start] + [length], or null when any of them is missing. */
    fun get(start: Long, length: Int): ByteArray? = synchronized(lock) {
        if (missing(start, length.toLong()).isNotEmpty()) return null
        val out = ByteArray(length)
        var filledTo = start
        val end = start + length
        while (filledTo < end) {
            val piece = pieceAt(filledTo) ?: return null
            val (pieceStart, bytes) = piece
            val from = (filledTo - pieceStart).toInt()
            val count = minOf(bytes.size - from, (end - filledTo).toInt())
            System.arraycopy(bytes, from, out, (filledTo - start).toInt(), count)
            filledTo += count
        }
        out
    }

    /** The parts of [start] .. [start] + [length] that aren't cached, in order. */
    fun missing(start: Long, length: Long): List<LongRange> = synchronized(lock) {
        val end = start + length
        val gaps = mutableListOf<LongRange>()
        var at = start
        while (at < end) {
            val covered = coveredUntil(at)
            if (covered > at) {
                at = covered
                continue
            }
            val next = nextStartAfter(at)?.coerceAtMost(end) ?: end
            gaps += at until next
            at = next
        }
        gaps
    }

    fun cachedBytes(): Long = synchronized(lock) { memoryBytes + disk.values.sum() }

    fun clear() = synchronized(lock) {
        memory.clear()
        disk.clear()
        lastUse.clear()
        memoryBytes = 0
        diskDirectory?.listFiles()?.forEach { it.delete() }
    }

    /** How far the cached bytes run without a gap from [at]; [at] when it isn't cached. */
    private fun coveredUntil(at: Long): Long {
        var reach = at
        var moved = true
        while (moved) {
            moved = false
            memory.floorEntry(reach)?.let { (s, b) -> if (s + b.size > reach) { reach = s + b.size; moved = true } }
            disk.floorEntry(reach)?.let { (s, l) -> if (s + l > reach) { reach = s + l; moved = true } }
        }
        return reach
    }

    private fun nextStartAfter(at: Long): Long? {
        val a = memory.higherKey(at)
        val b = disk.higherKey(at)
        return listOfNotNull(a, b).minOrNull()
    }

    /** A cached piece holding the byte at [position]: its start and bytes. */
    private fun pieceAt(position: Long): Pair<Long, ByteArray>? {
        memory.floorEntry(position)?.let { (s, b) ->
            if (s + b.size > position) {
                lastUse[s] = ++tick
                return s to b
            }
        }
        disk.floorEntry(position)?.let { (s, l) ->
            if (s + l > position) {
                val bytes = runCatching { File(diskDirectory, "$s-$l.bin").readBytes() }.getOrNull()
                if (bytes == null || bytes.size.toLong() != l) {
                    disk.remove(s)
                    return null
                }
                return s to bytes
            }
        }
        return null
    }

    private fun evict() {
        while (memoryBytes > memoryLimit && memory.isNotEmpty()) {
            val oldest = memory.keys.minByOrNull { lastUse[it] ?: 0L } ?: break
            val bytes = memory.remove(oldest) ?: break
            memoryBytes -= bytes.size
            lastUse.remove(oldest)
            spill(oldest, bytes)
        }
    }

    private fun spill(start: Long, bytes: ByteArray) {
        val directory = diskDirectory ?: return
        if (diskLimit <= 0 || bytes.size > diskLimit) return
        if (disk[start]?.let { it >= bytes.size } == true) return
        runCatching {
            directory.mkdirs()
            while (disk.values.sum() + bytes.size > diskLimit && disk.isNotEmpty()) {
                val first = disk.firstKey()
                val length = disk.remove(first) ?: break
                File(directory, "$first-$length.bin").delete()
            }
            File(directory, "$start-${bytes.size}.bin").writeBytes(bytes)
            disk[start] = bytes.size.toLong()
        }
    }

    /** Writes what is in memory to disk, so a later run of the same file finds it. */
    fun persist() = synchronized(lock) {
        memory.entries.toList().forEach { (start, bytes) -> spill(start, bytes) }
    }
}
