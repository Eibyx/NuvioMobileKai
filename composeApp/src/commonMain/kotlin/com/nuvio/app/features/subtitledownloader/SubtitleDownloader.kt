package com.nuvio.app.features.subtitledownloader

import androidx.compose.runtime.Composable
import com.nuvio.app.features.streams.StreamItem

/** The stream and the title it belongs to, as the stream picker hands them to Subtitle Downloader. */
data class SubtitleDownloaderRequest(
    val stream: StreamItem,
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
)

/** Whether this platform can extract ASS/SSA subtitles and fonts from remote MKV files. */
expect val subtitleDownloaderAvailable: Boolean

/** Subtitle Downloader for [request]'s stream, or nothing while it is null. */
@Composable
expect fun SubtitleDownloaderHost(request: SubtitleDownloaderRequest?, onDismiss: () -> Unit)
