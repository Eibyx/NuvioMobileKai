package com.nuvio.app.features.subtitledownloader

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus { QUEUED, RESOLVING, RUNNING, PAUSED, DONE, PARTIAL, FAILED, CANCELLED, NEEDS_BUDGET }

/** Enough of a stream to find it again after the app restarts or its link expires. */
@Serializable
data class StreamRef(
    val addonId: String,
    val addonName: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val cloudConnectionId: String? = null,
    val cloudItemId: String? = null,
    val headers: Map<String, String>? = null,
    val bingeGroup: String? = null,
) {
    val label: String get() = listOfNotNull(name, title).joinToString(" · ").ifBlank { addonName }

    fun toStream(): StreamItem = StreamItem(
        name = name,
        title = title,
        description = description,
        url = url,
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = addonName,
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
            filename = filename,
            videoSize = videoSize,
            proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
        ),
        serverTarget = if (cloudConnectionId != null && cloudItemId != null) {
            ServerPlaybackTarget(ServerItemRef(cloudConnectionId, cloudItemId), null)
        } else null,
    )

    companion object {
        fun of(stream: StreamItem) = StreamRef(
            addonId = stream.addonId,
            addonName = stream.addonName,
            name = stream.name,
            title = stream.title,
            description = stream.description,
            url = stream.url,
            filename = stream.behaviorHints.filename,
            videoSize = stream.behaviorHints.videoSize,
            infoHash = stream.infoHash,
            fileIdx = stream.fileIdx,
            cloudConnectionId = stream.serverTarget?.item?.connectionId,
            cloudItemId = stream.serverTarget?.item?.itemId,
            headers = stream.behaviorHints.proxyHeaders?.request,
            bingeGroup = stream.behaviorHints.bingeGroup,
        )
    }
}

/** Which tracks to save: all ASS/SSA, or those matching these (by number in the same file, else by language and name). */
@Serializable
data class TrackChoice(val all: Boolean, val keys: List<TrackKey> = emptyList(), val languages: List<String> = emptyList())

@Serializable
data class TrackKey(val number: Long? = null, val language: String? = null, val name: String? = null)

@Serializable
data class FontChoice(val all: Boolean, val names: List<String> = emptyList()) {
    val none: Boolean get() = !all && names.isEmpty()
}

@Serializable
data class TrackOutcome(
    val fileName: String,
    val language: String?,
    val trackName: String?,
    val events: Int,
    val expected: Long?,
    val integrity: String,
    val notes: List<String> = emptyList(),
)

@Serializable
data class JobResult(
    val tracks: List<TrackOutcome>,
    val fontsSaved: Int,
    val fontsReused: Int,
    val fontsTotal: Int,
    val strategy: String,
    val elapsedMs: Long,
    val trafficBytes: Long,
    val requests: Long,
    val fileSize: Long,
    val folder: String,
    val notes: List<String> = emptyList(),
)

@Serializable
data class SubtitleJob(
    val id: String,
    val createdAtMs: Long,
    val showTitle: String,
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** The stream to read; null until one is matched (episodes of a season). */
    val stream: StreamRef? = null,
    /** The release chosen on the first episode, matched in the others. */
    val reference: StreamRef? = null,
    val referenceSeason: Int? = null,
    val referenceEpisode: Int? = null,
    val tracks: TrackChoice,
    val fonts: FontChoice,
    val mode: String,
    val budgetPercent: Int,
    val treeUri: String? = null,
    val status: JobStatus = JobStatus.QUEUED,
    val message: String? = null,
    val matchedRelease: String? = null,
    val result: JobResult? = null,
    val updatedAtMs: Long = createdAtMs,
    val batchId: String? = null,
) {
    val isActive: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RESOLVING || status == JobStatus.RUNNING
    val label: String
        get() = buildString {
            append(showTitle)
            if (season != null && episode != null) append(" · T${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}")
            episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
}

@Serializable
data class SubtitleDownloaderSettings(
    val mode: String = "AUTO",
    /** The most of each MKV the downloader may transfer, in percent of its size. */
    val budgetPercent: Int = 25,
    val treeUri: String? = null,
    val treeLabel: String? = null,
    val memoryCacheMb: Int = 96,
    val diskCacheMb: Int = 256,
    val maxParallelJobs: Int = 2,
)

@Serializable
internal data class SubtitleDownloaderState(
    val jobs: List<SubtitleJob> = emptyList(),
    val settings: SubtitleDownloaderSettings = SubtitleDownloaderSettings(),
)
