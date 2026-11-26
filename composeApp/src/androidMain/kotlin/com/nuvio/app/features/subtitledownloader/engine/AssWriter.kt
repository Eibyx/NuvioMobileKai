package com.nuvio.app.features.subtitledownloader.engine

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/**
 * Puts an ASS/SSA script back together from a track's CodecPrivate (script info, styles, the
 * events' Format line) and its events: every field and override tag byte for byte, Start and End
 * from the file's timestamps, in ReadOrder.
 */
internal object AssWriter {
    private const val ASS_FORMAT = "Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
    private const val SSA_FORMAT = "Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    class Output(val bytes: ByteArray, val malformedEvents: Int, val inferredDurations: Int)

    fun write(track: MkvTrack, codecPrivate: ByteArray?, events: List<AssEvent>, timestampScale: Long): Output {
        val header = codecPrivate ?: ByteArray(0)
        val text = String(header, Charsets.UTF_8)
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val out = ByteArrayOutputStream(header.size + events.size * 160)
        out.write(header)
        if (header.isNotEmpty() && !text.endsWith("\n")) out.write(newline.toByteArray())

        val eventsAt = Regex("""(?im)^\s*\[events]\s*$""").find(text)
        var format = formatLine(text, eventsAt?.range?.last)
        if (eventsAt == null) {
            if (header.isNotEmpty()) out.write(newline.toByteArray())
            out.write("[Events]$newline".toByteArray())
        }
        if (format == null) {
            format = if (track.codecId == MkvTrack.CODEC_SSA) SSA_FORMAT else ASS_FORMAT
            out.write("Format: $format$newline".toByteArray())
        }
        val fields = format.split(',').map { it.trim() }
        val startIndex = fields.indexOfFirst { it.equals("Start", true) }
        val endIndex = fields.indexOfFirst { it.equals("End", true) }
        val stored = fields.filterIndexed { i, _ -> i != startIndex && i != endIndex }

        val ordered = order(events)
        val ends = endsFor(ordered)
        var malformed = 0
        var inferred = 0
        val prefix = "Dialogue: ".toByteArray()
        val comma = ','.code
        for (event in ordered) {
            val startNs = event.start * timestampScale
            val endTicks = when {
                event.duration != null -> event.start + event.duration
                else -> ends[event]?.also { inferred++ } ?: event.start
            }
            val endNs = endTicks * timestampScale
            val values = splitFields(event.fields, stored.size)
            if (values == null) malformed++
            out.write(prefix)
            if (values == null || startIndex < 0 || endIndex < 0) {
                // Not the fields the Format line promises: kept whole after the times rather than lost.
                out.write(time(startNs).toByteArray()); out.write(comma)
                out.write(time(endNs).toByteArray()); out.write(comma)
                out.write(event.fields)
            } else {
                var storedAt = 0
                for (i in fields.indices) {
                    if (i > 0) out.write(comma)
                    when (i) {
                        startIndex -> out.write(time(startNs).toByteArray())
                        endIndex -> out.write(time(endNs).toByteArray())
                        else -> out.write(values[storedAt++])
                    }
                }
            }
            out.write(newline.toByteArray())
        }
        return Output(out.toByteArray(), malformed, inferred)
    }

    /** ReadOrder when the file numbers its events, else time, then file position. */
    fun order(events: List<AssEvent>): List<AssEvent> {
        val numbered = events.all { it.readOrder != null }
        return if (numbered) {
            events.sortedWith(compareBy<AssEvent> { it.readOrder }.thenBy { it.start }.thenBy { it.position }.thenBy { it.frameIndex })
        } else {
            events.sortedWith(compareBy<AssEvent> { it.start }.thenBy { it.position }.thenBy { it.frameIndex })
        }
    }

    /** Ends for events without a duration: the start of the track's next event, as Matroska defines it. */
    private fun endsFor(events: List<AssEvent>): Map<AssEvent, Long> {
        val missing = events.filter { it.duration == null }
        if (missing.isEmpty()) return emptyMap()
        val starts = events.map { it.start }.distinct().sorted()
        return missing.associateWith { event ->
            val index = starts.binarySearch(event.start)
            starts.getOrNull(index + 1) ?: event.start
        }
    }

    private fun formatLine(text: String, eventsHeaderEnd: Int?): String? {
        if (eventsHeaderEnd == null) return null
        val rest = text.substring(eventsHeaderEnd + 1)
        val line = Regex("""(?im)^\s*format\s*:(.*)$""").find(rest) ?: return null
        // Only the Format line of the [Events] section, not of a later section.
        val nextSection = Regex("""(?m)^\s*\[""").find(rest)?.range?.first
        if (nextSection != null && nextSection < line.range.first) return null
        return line.groupValues[1].trim().trimEnd('\r').takeIf { it.isNotEmpty() }
    }

    /** [count] comma-separated values; the last keeps the rest, commas and all. */
    private fun splitFields(data: ByteArray, count: Int): List<ByteArray>? {
        if (count <= 0) return null
        val out = ArrayList<ByteArray>(count)
        var from = 0
        for (i in 0 until count - 1) {
            var at = from
            while (at < data.size && data[at] != ','.code.toByte()) at++
            if (at >= data.size) return null
            out += data.copyOfRange(from, at)
            from = at + 1
        }
        out += data.copyOfRange(from, data.size)
        return out
    }

    /** `H:MM:SS.cc`, rounded to the nearest centisecond. */
    fun time(nanoseconds: Long): String {
        val centis = (nanoseconds.coerceAtLeast(0) / 10_000_000.0).roundToLong()
        val hours = centis / 360_000
        val minutes = (centis / 6_000) % 60
        val seconds = (centis / 100) % 60
        val cs = centis % 100
        return "$hours:${minutes.pad()}:${seconds.pad()}.${cs.pad()}"
    }

    private fun Long.pad() = toString().padStart(2, '0')
}
