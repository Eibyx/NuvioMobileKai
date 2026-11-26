package com.nuvio.app.features.subtitledownloader.engine

/** One way a track's frames (or its CodecPrivate) were encoded. */
internal class ContentEncoding(
    val order: Long,
    /** 1 frames, 2 CodecPrivate, 4 the next encoding's settings. */
    val scope: Long,
    /** 0 compression, 1 encryption. */
    val type: Long,
    /** 0 zlib, 1 bzlib, 2 lzo1x, 3 header stripping. */
    val compressionAlgorithm: Long,
    val compressionSettings: ByteArray?,
) {
    val appliesToFrames: Boolean get() = scope and 1L != 0L
    val appliesToCodecPrivate: Boolean get() = scope and 2L != 0L
    val isEncryption: Boolean get() = type == 1L

    /** Why frames encoded this way can't be read, or null when they can. */
    val unsupportedReason: String?
        get() = when {
            isEncryption -> "encrypted"
            compressionAlgorithm == ZLIB || compressionAlgorithm == HEADER_STRIPPING -> null
            compressionAlgorithm == 1L -> "bzlib"
            compressionAlgorithm == 2L -> "lzo1x"
            else -> "compression $compressionAlgorithm"
        }

    companion object {
        const val ZLIB = 0L
        const val HEADER_STRIPPING = 3L
    }
}

internal class MkvTrack(
    val number: Long,
    val uid: Long,
    /** 1 video, 2 audio, 17 subtitle. */
    val type: Long,
    val codecId: String,
    val name: String?,
    val language: String?,
    val languageBcp47: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    /** As stored, before any CodecPrivate encoding is undone. */
    val codecPrivateRaw: ByteArray?,
    val encodings: List<ContentEncoding>,
) {
    val isAss: Boolean get() = codecId == CODEC_ASS || codecId == CODEC_SSA
    val isSubtitle: Boolean get() = type == 17L || codecId.startsWith("S_")
    val formatLabel: String
        get() = when (codecId) {
            CODEC_ASS -> "ASS"
            CODEC_SSA -> "SSA"
            "S_TEXT/UTF8" -> "SRT"
            "S_TEXT/WEBVTT" -> "WebVTT"
            "S_HDMV/PGS" -> "PGS"
            "S_VOBSUB" -> "VobSub"
            else -> codecId.removePrefix("S_")
        }

    /** Frame encodings in the order they must be undone: highest ContentEncodingOrder first. */
    val frameDecodings: List<ContentEncoding> get() = encodings.filter { it.appliesToFrames }.sortedByDescending { it.order }

    val unsupportedReason: String? get() = frameDecodings.firstNotNullOfOrNull { it.unsupportedReason }

    /** The best language code the file gives. */
    val languageCode: String? get() = languageBcp47?.takeIf { it.isNotBlank() } ?: language?.takeIf { it.isNotBlank() && it != "und" }

    companion object {
        const val CODEC_ASS = "S_TEXT/ASS"
        const val CODEC_SSA = "S_TEXT/SSA"
    }
}

internal class MkvAttachment(
    val index: Int,
    val name: String,
    val mimeType: String?,
    val description: String?,
    val uid: Long?,
    /** Absolute position and size of the file's bytes. */
    val dataPosition: Long,
    val dataSize: Long,
) {
    val isFont: Boolean get() = isFontAttachment(name, mimeType)
}

internal fun isFontAttachment(name: String, mime: String?): Boolean {
    val type = mime?.lowercase().orEmpty()
    if ("font" in type || "truetype" in type || "opentype" in type || type == "application/vnd.ms-opentype") return true
    return name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "otc", "woff", "woff2", "fon", "pfb")
}

/** One CueTrackPositions of a CuePoint. Positions relative to the segment's data. */
internal class CueEntry(
    val time: Long,
    val track: Long,
    val clusterPosition: Long,
    val relativePosition: Long?,
    val duration: Long?,
    val blockNumber: Long?,
)

internal class TrackStatistics(val frames: Long?, val bytes: Long?)

/** What the file's headers and indexes say, before any cluster is read. */
internal class MkvLayout(
    val fileSize: Long,
    val segmentDataPosition: Long,
    /** Absolute end of the segment's data (the file's end when its size is unknown). */
    val segmentEnd: Long,
    val timestampScale: Long,
    val segmentUid: ByteArray?,
    val durationTicks: Double?,
    val title: String?,
    val tracks: List<MkvTrack>,
    /** Absolute range of the Attachments element's content, when the file has one. */
    val attachmentsContent: LongRange?,
    val cues: List<CueEntry>?,
    val statistics: Map<Long, TrackStatistics>,
    /** Absolute position of the first cluster, when known. */
    val firstClusterPosition: Long?,
    val notes: List<String>,
) {
    val assTracks: List<MkvTrack> get() = tracks.filter { it.isAss }
    val subtitleTracks: List<MkvTrack> get() = tracks.filter { it.isSubtitle }

    fun track(number: Long): MkvTrack? = tracks.firstOrNull { it.number == number }

    /** Absolute position of a cluster the index places at [clusterPosition]. */
    fun absolute(clusterPosition: Long): Long = segmentDataPosition + clusterPosition

    fun cuesFor(track: Long): List<CueEntry> = cues.orEmpty().filter { it.track == track }

    fun expectedFrames(track: MkvTrack): Long? = statistics[track.uid]?.frames

    val durationMs: Long? get() = durationTicks?.let { (it * timestampScale / 1_000_000.0).toLong() }
}
