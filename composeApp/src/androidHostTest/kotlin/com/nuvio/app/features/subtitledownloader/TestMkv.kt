package com.nuvio.app.features.subtitledownloader

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random

/** A file made of real bytes and stretches of filler (video and audio payloads), read by range. */
class VirtualFile(private val parts: List<Part>) {
    sealed class Part(val length: Long)
    class Bytes(val data: ByteArray) : Part(data.size.toLong())
    class Filler(length: Long, val seed: Int) : Part(length)

    private val starts = LongArray(parts.size)
    val size: Long

    init {
        var at = 0L
        parts.forEachIndexed { i, p -> starts[i] = at; at += p.length }
        size = at
    }

    fun read(start: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var index = java.util.Arrays.binarySearch(starts, start).let { if (it >= 0) it else -it - 2 }
        var filled = 0
        while (filled < length && index < parts.size) {
            val part = parts[index]
            val partStart = starts[index]
            val from = (start + filled - partStart).toInt()
            val count = minOf(length - filled, (part.length - from).toInt())
            when (part) {
                is Bytes -> System.arraycopy(part.data, from, out, filled, count)
                is Filler -> for (i in 0 until count) out[filled + i] = ((from + i) * 31 + part.seed).toByte()
            }
            filled += count
            index++
        }
        return out
    }

    fun materialize(): ByteArray = read(0, size.toInt())
}

/** An ASS event as the test means it: what the extracted file must say. */
data class TestEvent(
    val readOrder: Int,
    val startMs: Long,
    val durationMs: Long,
    val layer: Int,
    val style: String,
    val name: String,
    val effect: String,
    val text: String,
)

class TestTrack(
    val number: Int,
    val codecId: String = "S_TEXT/ASS",
    val language: String = "eng",
    val name: String? = null,
    val header: String,
    val events: List<TestEvent>,
    val zlib: Boolean = false,
    /** Events sharing a start go into one Xiph-laced block. */
    val lace: Boolean = false,
    /** Stored as SimpleBlocks: no BlockDuration. */
    val simpleBlocks: Boolean = false,
    val headerStripping: ByteArray? = null,
) {
    val uid: Long = 1000L + number

    /** The script the extraction must reproduce, byte for byte. */
    fun expectedAss(): String {
        val sb = StringBuilder(header)
        if (!header.endsWith("\n")) sb.append('\n')
        val ordered = events.sortedBy { it.readOrder }
        val starts = events.map { it.startMs }.distinct().sorted()
        for (e in ordered) {
            val end = if (simpleBlocks) starts.getOrNull(starts.indexOf(e.startMs) + 1) ?: e.startMs else e.startMs + e.durationMs
            sb.append("Dialogue: ${e.layer},${time(e.startMs)},${time(end)},${e.style},${e.name},0,0,0,${e.effect},${e.text}\n")
        }
        return sb.toString()
    }

    private fun time(ms: Long): String {
        val cs = ms / 10
        return "%d:%02d:%02d.%02d".format(cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100)
    }
}

class TestAttachment(val name: String, val mime: String, val data: ByteArray)

enum class CueStyle { NONE, VIDEO_ONLY, ALL_WITH_POSITION, SUBTITLES_WITHOUT_POSITION }

/**
 * Builds a Matroska file the way mkvmerge lays one out: SeekHead, Info, Tracks, Attachments,
 * clusters of video, audio and subtitle blocks interleaved by time, then Cues and Tags.
 */
class TestMkvBuilder(
    val durationSeconds: Int = 120,
    val videoBitsPerSecond: Long = 2_000_000,
    val clusterSeconds: Int = 5,
    val tracks: List<TestTrack>,
    val attachments: List<TestAttachment> = emptyList(),
    val cueStyle: CueStyle = CueStyle.ALL_WITH_POSITION,
    val statistics: Boolean = true,
    val unknownSizeClusters: Boolean = false,
    val crcInClusters: Boolean = false,
    val seed: Int = 7,
) {
    private val random = Random(seed)

    class Built(val file: VirtualFile, val clusterCount: Int, val subtitleBlockCount: Int)

    fun build(): Built {
        val parts = mutableListOf<VirtualFile.Part>()
        fun bytes(b: ByteArray) { parts += VirtualFile.Bytes(b) }

        // Head: EBML header, then the segment (8-byte size patched later).
        val ebml = element(0x1A45DFA3, concat(uintEl(0x4286, 1), uintEl(0x42F7, 1), uintEl(0x42F2, 4), uintEl(0x42F3, 8), strEl(0x4282, "matroska"), uintEl(0x4287, 4), uintEl(0x4285, 2)))
        val info = element(0x1549A966, concat(uintEl(0x2AD7B1, 1_000_000), floatEl(0x4489, durationSeconds * 1000.0), element(0x73A4, ByteArray(16) { it.toByte() }), strEl(0x7BA9, "Test")))
        val tracksEl = element(0x1654AE6B, concat(
            element(0xAE, concat(uintEl(0xD7, 1), uintEl(0x73C5, 1), uintEl(0x83, 1), strEl(0x86, "V_MPEG4/ISO/AVC"))),
            element(0xAE, concat(uintEl(0xD7, 2), uintEl(0x73C5, 2), uintEl(0x83, 2), strEl(0x86, "A_AAC"))),
            *tracks.map { t ->
                val encodings = when {
                    t.zlib -> element(0x6D80, element(0x6240, concat(uintEl(0x5031, 0), uintEl(0x5032, 1), uintEl(0x5033, 0), element(0x5034, uintEl(0x4254, 0)))))
                    t.headerStripping != null -> element(0x6D80, element(0x6240, concat(uintEl(0x5031, 0), uintEl(0x5032, 1), uintEl(0x5033, 0), element(0x5034, concat(uintEl(0x4254, 3), element(0x4255, t.headerStripping))))))
                    else -> ByteArray(0)
                }
                element(0xAE, concat(
                    uintEl(0xD7, t.number.toLong()), uintEl(0x73C5, t.uid), uintEl(0x83, 17), strEl(0x86, t.codecId),
                    strEl(0x22B59C, t.language), t.name?.let { strEl(0x536E, it) } ?: ByteArray(0),
                    element(0x63A2, t.header.toByteArray()), encodings,
                ))
            }.toTypedArray(),
        ))
        val attachmentsEl = if (attachments.isEmpty()) ByteArray(0) else element(0x1941A469, concat(*attachments.mapIndexed { i, a ->
            element(0x61A7, concat(strEl(0x466E, a.name), strEl(0x4660, a.mime), element(0x465C, a.data), uintEl(0x46AE, 5000L + i)))
        }.toTypedArray()))
        // SeekHead with fixed-width positions, filled once the end is laid out.
        val seekPositions = LongArray(5)
        fun seekHead(): ByteArray = element(0x114D9B74, concat(
            seek(0x1549A966, seekPositions[0]), seek(0x1654AE6B, seekPositions[1]),
            if (attachments.isNotEmpty()) seek(0x1941A469, seekPositions[2]) else ByteArray(0),
            if (cueStyle != CueStyle.NONE) seek(0x1C53BB6B, seekPositions[3]) else ByteArray(0),
            if (statistics) seek(0x1254C367, seekPositions[4]) else ByteArray(0),
        ))
        val seekLength = seekHead().size
        val headLength = seekLength + info.size + tracksEl.size + attachmentsEl.size
        seekPositions[0] = seekLength.toLong()
        seekPositions[1] = (seekLength + info.size).toLong()
        seekPositions[2] = (seekLength + info.size + tracksEl.size).toLong()

        // Clusters.
        data class Block(val timeMs: Long, val kind: Int, val bytes: ByteArray?, val filler: Long, val track: Int)
        val subtitleBlocks = mutableListOf<Pair<Long, ByteArray>>() // per block: time and the encoded element
        val cuePoints = mutableListOf<ByteArray>()
        val clusterParts = mutableListOf<VirtualFile.Part>()
        var clusterOffset = headLength.toLong()
        var subtitleCount = 0
        var clusters = 0
        val framesPerSecond = 24
        val frameBytes = videoBitsPerSecond / 8 / framesPerSecond
        val trackFrames = HashMap<Int, Long>()
        for (clusterStart in 0 until durationSeconds * 1000L step clusterSeconds * 1000L) {
            val clusterEnd = minOf(clusterStart + clusterSeconds * 1000L, durationSeconds * 1000L)
            val children = mutableListOf<VirtualFile.Part>()
            if (crcInClusters) children += VirtualFile.Bytes(element(0xBF, ByteArray(4)))
            children += VirtualFile.Bytes(uintEl(0xE7, clusterStart))
            val items = mutableListOf<Triple<Long, Int, Any>>() // time, order, payload
            var frame = 0
            var t = clusterStart
            while (t < clusterEnd) {
                val keyframe = frame == 0
                val size = if (keyframe) frameBytes * 6 else (frameBytes * (0.3 + random.nextDouble() * 1.2)).toLong()
                items += Triple(t, 0, Pair(1, size))
                frame++
                t = clusterStart + frame * 1000L / framesPerSecond
            }
            var a = clusterStart
            while (a < clusterEnd) {
                items += Triple(a, 1, Pair(2, 300L + random.nextInt(200)))
                a += 64
            }
            for (track in tracks) {
                val inCluster = track.events.filter { it.startMs in clusterStart until clusterEnd }
                val groups = if (track.lace) inCluster.groupBy { it.startMs to it.durationMs }.values.toList() else inCluster.map { listOf(it) }
                for (group in groups) items += Triple(group.first().startMs, 2, track to group)
            }
            items.sortWith(compareBy({ it.first }, { it.second }))
            var relative = 0L
            val subtitleCues = mutableListOf<Pair<Long, Pair<Int, Long>>>() // time, (track, relative position)
            for ((time, _, payload) in items) {
                val tc = (time - clusterStart).toInt()
                @Suppress("UNCHECKED_CAST")
                val part: List<VirtualFile.Part> = if (payload is Pair<*, *> && payload.first is Int) {
                    val (track, size) = payload as Pair<Int, Long>
                    val head = concat(byteArrayOf((0x80 or track).toByte()), byteArrayOf((tc shr 8).toByte(), tc.toByte()), byteArrayOf(if (track == 1 && size > frameBytes * 2) 0x80.toByte() else 0))
                    listOf(VirtualFile.Bytes(concat(byteArrayOf(0xA3.toByte()), sizeVint(head.size + size, 8), head)), VirtualFile.Filler(size, track))
                } else {
                    val (track, group) = payload as Pair<TestTrack, List<TestEvent>>
                    val frames = group.map { e ->
                        var raw = "${e.readOrder},${e.layer},${e.style},${e.name},0,0,0,${e.effect},${e.text}".toByteArray()
                        if (track.zlib) raw = deflate(raw)
                        if (track.headerStripping != null) {
                            require(raw.take(track.headerStripping.size).toByteArray().contentEquals(track.headerStripping))
                            raw = raw.copyOfRange(track.headerStripping.size, raw.size)
                        }
                        raw
                    }
                    trackFrames.merge(track.number, frames.size.toLong(), Long::plus)
                    val blockHead = concat(byteArrayOf((0x80 or track.number).toByte()), byteArrayOf((tc shr 8).toByte(), tc.toByte()))
                    val laced = frames.size > 1
                    val flags = if (laced) 0x02 else 0
                    val lacing = if (laced) concat(byteArrayOf((frames.size - 1).toByte()), *frames.dropLast(1).map { xiph(it.size) }.toTypedArray()) else ByteArray(0)
                    val body = concat(blockHead, byteArrayOf(flags.toByte()), lacing, *frames.toTypedArray())
                    subtitleCount++
                    subtitleCues += time to (track.number to relative)
                    val el = if (track.simpleBlocks) {
                        element(0xA3, body.also { it[blockHead.size] = (flags or 0x80).toByte() })
                    } else {
                        element(0xA0, concat(element(0xA1, body), uintEl(0x9B, group.first().durationMs)))
                    }
                    listOf(VirtualFile.Bytes(el))
                }
                children += part
                relative += part.sumOf { it.length }
            }
            // The cluster's own header, its children's total in an 8-byte size (or unknown).
            val childrenLength = children.sumOf { it.length }
            val prefixLength = children.takeWhile { it !is VirtualFile.Bytes || !(it.data.firstOrNull() == 0xE7.toByte()) }.sumOf { it.length }
            val header = concat(id(0x1F43B675), if (unknownSizeClusters) byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) else sizeVint(childrenLength, 8))
            clusterParts += VirtualFile.Bytes(header)
            clusterParts += children
            val clusterPosition = clusterOffset
            if (cueStyle != CueStyle.NONE) {
                cuePoints += element(0xBB, concat(uintEl(0xB3, clusterStart), element(0xB7, concat(uintEl(0xF7, 1), uintEl(0xF1, clusterPosition), uintEl(0xF0, 0)))))
            }
            if (cueStyle == CueStyle.ALL_WITH_POSITION || cueStyle == CueStyle.SUBTITLES_WITHOUT_POSITION) {
                val timestampLength = uintEl(0xE7, clusterStart).size + (if (crcInClusters) 6 else 0)
                for ((time, entry) in subtitleCues) {
                    val (track, rel) = entry
                    val positions = concat(uintEl(0xF7, track.toLong()), uintEl(0xF1, clusterPosition),
                        if (cueStyle == CueStyle.ALL_WITH_POSITION) uintEl(0xF0, rel + timestampLength - (if (crcInClusters) 0 else 0)) else ByteArray(0),
                        uintEl(0xB2, 1000))
                    cuePoints += element(0xBB, concat(uintEl(0xB3, time), element(0xB7, positions)))
                }
            }
            clusterOffset += header.size + childrenLength
            clusters++
            check(prefixLength >= 0)
        }
        // Cues and Tags after the clusters.
        val cues = if (cueStyle != CueStyle.NONE) element(0x1C53BB6B, concat(*cuePoints.toTypedArray())) else ByteArray(0)
        seekPositions[3] = clusterOffset
        val tags = if (statistics) element(0x1254C367, concat(*tracks.map { t ->
            element(0x7373, concat(element(0x63C0, uintEl(0x63C5, t.uid)),
                element(0x67C8, concat(strEl(0x45A3, "NUMBER_OF_FRAMES"), strEl(0x4487, (trackFrames[t.number] ?: 0).toString())))))
        }.toTypedArray())) else ByteArray(0)
        seekPositions[4] = clusterOffset + cues.size
        val segmentContentLength = headLength + (clusterOffset - headLength) + cues.size + tags.size
        bytes(ebml)
        bytes(concat(id(0x18538067), sizeVint(segmentContentLength, 8)))
        bytes(seekHead())
        bytes(info); bytes(tracksEl); if (attachmentsEl.isNotEmpty()) bytes(attachmentsEl)
        parts += clusterParts
        if (cues.isNotEmpty()) bytes(cues)
        if (tags.isNotEmpty()) bytes(tags)
        return Built(VirtualFile(parts), clusters, subtitleCount)
    }

    private fun seek(target: Long, position: Long) = element(0x4DBB, concat(element(0x53AB, id(target)), element(0x53AC, fixed(position, 8))))

    companion object {
        fun id(value: Long): ByteArray {
            val length = when {
                value > 0xFFFFFF -> 4
                value > 0xFFFF -> 3
                value > 0xFF -> 2
                else -> 1
            }
            return ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
        }

        fun sizeVint(size: Long, length: Int = 0): ByteArray {
            val n = if (length > 0) length else (1..8).first { size < (1L shl (7 * it)) - 1 }
            val out = ByteArray(n) { i -> (size shr (8 * (n - 1 - i))).toByte() }
            out[0] = (out[0].toInt() or (0x80 shr (n - 1))).toByte()
            return out
        }

        fun element(id: Long, content: ByteArray) = concat(id(id), sizeVint(content.size.toLong()), content)
        fun fixed(value: Long, length: Int) = ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
        fun uintEl(id: Long, value: Long): ByteArray {
            val length = (1..8).first { value < (1L shl (8 * it)) || it == 8 }
            return element(id, fixed(value, length))
        }
        fun strEl(id: Long, value: String) = element(id, value.toByteArray())
        fun floatEl(id: Long, value: Double) = element(id, fixed(java.lang.Double.doubleToLongBits(value), 8))
        fun concat(vararg arrays: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            arrays.forEach { out.write(it) }
            return out.toByteArray()
        }
        fun xiph(size: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var left = size
            while (left >= 255) { out.write(255); left -= 255 }
            out.write(left)
            return out.toByteArray()
        }
        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater()
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return out.toByteArray()
        }

        const val HEADER = "[Script Info]\nTitle: Prueba ✓ 日本語\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Open Sans Semibold,62,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,120,120,50,1\nStyle: Sign,Gandhi Sans,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

        private val TEXTS = listOf(
            "¿Qué pasó, Subaru-kun? \\N¡Responde!",
            "{\\an8\\pos(960,120)\\fnGandhi Sans\\c&H3A2F1E&}Tienda de conveniencia",
            "{\\k20}Ki{\\k15}mi {\\k30}no {\\kf40}na{\\ko25}ma{\\k35}e",
            "{\\p1\\pos(400,300)}m 0 0 l 100 0 100 100 0 100{\\p0}",
            "{\\move(100,200,1800,200,0,3000)\\t(0,500,\\frz30\\fscx120)}Movimiento, con comas, y más",
            "{\\clip(100,100,800,600)\\blur2\\bord4\\3c&H000000&}Señal recortada",
            "日本語のテキスト、カンマ付き。",
            "Línea normal sin etiquetas",
        )

        /** Events in bursts the way typeset anime has them: dialogue lines, and signs or karaoke that pile up. */
        fun events(count: Int, durationSeconds: Int, random: Random, step: Int = 0): List<TestEvent> {
            val out = mutableListOf<TestEvent>()
            while (out.size < count) {
                val burst = if (random.nextInt(5) == 0) 8 + random.nextInt(40) else 1
                val start = (random.nextLong(durationSeconds * 100L - 600) * 10)
                val duration = (50 + random.nextInt(400)) * 10L
                repeat(minOf(burst, count - out.size)) { i ->
                    val sign = burst > 1
                    out += TestEvent(
                        readOrder = 0,
                        startMs = if (sign) start + (i % 4) * 10L else start,
                        durationMs = duration,
                        layer = if (sign) i % 5 else 0,
                        style = if (sign) "Sign" else "Default",
                        name = if (sign) "" else "Emilia",
                        effect = "",
                        text = TEXTS[(out.size + step) % TEXTS.size],
                    )
                }
            }
            // ReadOrder is the script's order: mostly by time, a few out of order.
            val scripted = out.sortedBy { it.startMs }.toMutableList()
            for (i in 0 until scripted.size / 50) {
                val a = random.nextInt(scripted.size)
                val b = (a + 1 + random.nextInt(3)).coerceAtMost(scripted.size - 1)
                val tmp = scripted[a]; scripted[a] = scripted[b]; scripted[b] = tmp
            }
            return scripted.mapIndexed { i, e -> e.copy(readOrder = i) }
        }
    }
}
