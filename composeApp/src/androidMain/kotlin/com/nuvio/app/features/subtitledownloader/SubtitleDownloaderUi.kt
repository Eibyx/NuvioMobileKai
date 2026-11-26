package com.nuvio.app.features.subtitledownloader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.subtitledownloader.engine.EngineProgress
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import com.nuvio.app.features.subtitledownloader.engine.MkvFormatException
import com.nuvio.app.features.subtitledownloader.engine.RangeNotSupportedException
import com.nuvio.app.features.subtitledownloader.engine.RemoteHttpException
import com.nuvio.app.features.subtitledownloader.engine.Strategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

actual val subtitleDownloaderAvailable: Boolean = true

@Composable
actual fun SubtitleDownloaderHost(request: SubtitleDownloaderRequest?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    remember { SubtitleDownloaderRepository.initialize(context) }
    var queueOpen by remember { mutableStateOf(false) }
    var seasonFor by remember { mutableStateOf<SeasonLaunch?>(null) }
    var diagnosticsFor by remember { mutableStateOf<SubtitleDownloaderRequest?>(null) }
    if (request != null) {
        SubtitleDownloaderSheet(
            request = request,
            onDismiss = onDismiss,
            onOpenQueue = { queueOpen = true },
            onSeason = { seasonFor = it },
            onDiagnostics = { diagnosticsFor = request },
        )
    }
    if (queueOpen) SubtitleQueueDialog(onDismiss = { queueOpen = false })
    seasonFor?.let { launch -> SubtitleSeasonDialog(launch, onDismiss = { seasonFor = null }, onQueued = { seasonFor = null; queueOpen = true }) }
    diagnosticsFor?.let { SubtitleDiagnosticsDialog(it, onDismiss = { diagnosticsFor = null }) }
}

/** What the season dialog needs from the episode it was opened on. */
internal class SeasonLaunch(val request: SubtitleDownloaderRequest, val detected: DetectedSource, val keys: List<TrackKey>)

private sealed interface DetectUi {
    data object Resolving : DetectUi
    data object Reading : DetectUi
    class Ready(val detected: DetectedSource) : DetectUi
    class Failed(val message: StringResource, val arg: String? = null) : DetectUi
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubtitleDownloaderSheet(
    request: SubtitleDownloaderRequest,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit,
    onSeason: (SeasonLaunch) -> Unit,
    onDiagnostics: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var ui by remember(request.stream) { mutableStateOf<DetectUi>(DetectUi.Resolving) }
    var attempt by remember { mutableStateOf(0) }
    var queuedJobId by remember(request.stream) { mutableStateOf<String?>(null) }
    LaunchedEffect(request.stream, attempt) {
        ui = DetectUi.Resolving
        ui = try {
            DetectUi.Ready(SubtitleDownloaderRepository.detect(request.stream, request.season, request.episode) { ui = DetectUi.Reading })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }
    val close: () -> Unit = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) } }
    NuvioModalBottomSheet(onDismissRequest = close, sheetState = sheetState, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.subdl_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(request.title, request.episodeTitle, request.stream.streamLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (val state = ui) {
                DetectUi.Resolving, DetectUi.Reading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (state == DetectUi.Resolving) Res.string.subdl_resolving else Res.string.subdl_detecting))
                }
                is DetectUi.Failed -> {
                    Text(if (state.arg != null) stringResource(state.message, state.arg) else stringResource(state.message), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { attempt++ }) { Text(stringResource(Res.string.subdl_retry)) }
                        OutlinedButton(onClick = onDiagnostics) { Text(stringResource(Res.string.subdl_diagnostics)) }
                    }
                }
                is DetectUi.Ready -> ReadyContent(
                    request = request,
                    detected = state.detected,
                    queuedJobId = queuedJobId,
                    onQueued = { queuedJobId = it },
                    onSeason = onSeason,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenQueue) { Text(stringResource(Res.string.subdl_queue)) }
                TextButton(onClick = onDiagnostics) { Text(stringResource(Res.string.subdl_diagnostics)) }
            }
        }
    }
}

private fun failure(e: Throwable): DetectUi.Failed = when (e) {
    is RangeNotSupportedException -> DetectUi.Failed(Res.string.subdl_error_no_range)
    is MkvFormatException -> DetectUi.Failed(Res.string.subdl_error_not_mkv)
    is RemoteHttpException -> DetectUi.Failed(Res.string.subdl_error_http, e.code.toString())
    is UnsupportedSourceException -> DetectUi.Failed(errorText(e.message))
    else -> DetectUi.Failed(Res.string.subdl_error_generic)
}

internal fun errorText(code: String?): StringResource = when {
    code == "torrent" -> Res.string.subdl_error_torrent
    code == "no-link" -> Res.string.subdl_error_no_link
    code == "missing-api-key" -> Res.string.subdl_error_api_key
    code == "not-cached" -> Res.string.subdl_error_not_cached
    code == "no-range" -> Res.string.subdl_error_no_range
    code == "not-mkv" -> Res.string.subdl_error_not_mkv
    code == "file-changed" -> Res.string.subdl_error_changed
    code == "no-match" -> Res.string.subdl_error_no_match
    code == "budget" -> Res.string.subdl_error_budget
    code == "no-ass" -> Res.string.subdl_error_no_ass
    code?.startsWith("http-") == true -> Res.string.subdl_error_http
    else -> Res.string.subdl_error_generic
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(
    request: SubtitleDownloaderRequest,
    detected: DetectedSource,
    queuedJobId: String?,
    onQueued: (String) -> Unit,
    onSeason: (SeasonLaunch) -> Unit,
) {
    val detection = detected.detection
    val layout = detection.layout
    val settingsState by SubtitleDownloaderRepository.state.collectAsState()
    val settings = settingsState.settings
    val tracks = layout.assTracks
    val fonts = detection.attachments.filter { it.isFont }
    val selectedTracks = remember(detected) { mutableStateListOf<Long>().apply { addAll(tracks.filter { it.unsupportedReason == null }.map { it.number }) } }
    val selectedFonts = remember(detected) { mutableStateListOf<Int>().apply { addAll(fonts.map { it.index }) } }
    var mode by remember { mutableStateOf(runCatching { ExtractionMode.valueOf(settings.mode) }.getOrDefault(ExtractionMode.AUTO)) }
    var budget by remember { mutableStateOf(settings.budgetPercent) }
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val label = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            SubtitleDownloaderRepository.updateSettings { it.copy(treeUri = uri.toString(), treeLabel = label) }
        }
    }
    val queuedText = stringResource(Res.string.subdl_queued)

    NuvioSurfaceCard {
        Text(
            stringResource(Res.string.subdl_file_info, formatBytes(layout.fileSize), tracks.size, fonts.size),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                Res.string.subdl_detected_in,
                formatDuration(detection.detectionMs),
                formatBytes(detected.session.stats.trafficBytes),
                formatBytes(layout.fileSize),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val indexText = when {
            detection.tracks.isNotEmpty() && detection.tracks.all { it.indexedWithPosition > 0 && (it.expectedEvents == null || it.indexedWithPosition >= it.expectedEvents) } -> Res.string.subdl_index_full
            detection.tracks.any { it.indexedEvents > 0 } -> Res.string.subdl_index_partial
            else -> Res.string.subdl_index_none
        }
        Text(stringResource(indexText), style = MaterialTheme.typography.bodySmall)
        if (detection.strategy != Strategy.NONE) {
            val strategyName = stringResource(strategyText(detection.strategy))
            Text(stringResource(Res.string.subdl_estimate, strategyName, formatDuration((detection.estimatedSeconds * 1000).toLong())), style = MaterialTheme.typography.bodySmall)
            if (detection.estimatedSeconds > 120) {
                val reason = if (detection.strategy == Strategy.INDEX) {
                    stringResource(Res.string.subdl_reason_density)
                } else {
                    stringResource(Res.string.subdl_reason_latency, formatDuration((detected.session.model.latency() * 1000).toLong()))
                }
                Text(stringResource(Res.string.subdl_estimate_slow, reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        val others = layout.subtitleTracks.filter { !it.isAss }
        if (others.isNotEmpty()) {
            Text(
                stringResource(Res.string.subdl_other_tracks, others.joinToString { "${it.formatLabel} ${it.languageCode.orEmpty()}" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    NuvioSectionLabel(stringResource(Res.string.subdl_tracks))
    if (tracks.isEmpty()) Text(stringResource(Res.string.subdl_no_ass))
    tracks.forEach { track ->
        val estimate = detection.tracks.firstOrNull { it.track.number == track.number }
        val unsupported = track.unsupportedReason
        val details = buildList {
            add(track.formatLabel)
            estimate?.expectedEvents?.let { add(stringResourceFormat(Res.string.subdl_events, formatCount(it))) }
            if (track.isDefault) add(stringResourceFormat(Res.string.subdl_default))
            if (track.isForced) add(stringResourceFormat(Res.string.subdl_forced))
            track.frameDecodings.firstOrNull()?.let { e -> if (e.unsupportedReason == null) add(stringResourceFormat(Res.string.subdl_compressed, if (e.compressionAlgorithm == 0L) "zlib" else "header stripping")) }
            if (unsupported != null) add(stringResourceFormat(Res.string.subdl_unsupported, unsupported))
        }
        CheckRow(
            checked = track.number in selectedTracks,
            enabled = unsupported == null,
            title = SubtitleDownloaderRepository.fileName(track),
            subtitle = details.joinToString(" · "),
        ) { checked -> if (checked) selectedTracks += track.number else selectedTracks -= track.number }
    }

    NuvioSectionLabel(stringResource(Res.string.subdl_fonts))
    if (fonts.isEmpty()) {
        Text(stringResource(Res.string.subdl_no_fonts))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selectedFonts.clear(); selectedFonts.addAll(fonts.map { it.index }) }) { Text(stringResource(Res.string.subdl_select_all)) }
            TextButton(onClick = { selectedFonts.clear() }) { Text(stringResource(Res.string.subdl_select_none)) }
        }
        fonts.forEach { font ->
            CheckRow(checked = font.index in selectedFonts, title = font.name, subtitle = formatBytes(font.dataSize)) { checked ->
                if (checked) selectedFonts += font.index else selectedFonts -= font.index
            }
        }
    }

    NuvioSectionLabel(stringResource(Res.string.subdl_mode))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ExtractionMode.AUTO to Res.string.subdl_mode_auto,
            ExtractionMode.SAVER to Res.string.subdl_mode_saver,
            ExtractionMode.SPEED to Res.string.subdl_mode_speed,
        ).forEach { (value, label) ->
            FilterChip(selected = mode == value, onClick = {
                mode = value
                SubtitleDownloaderRepository.updateSettings { it.copy(mode = value.name) }
            }, label = { Text(stringResource(label)) })
        }
    }
    NuvioSectionLabel(stringResource(Res.string.subdl_budget))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 25, 50, 75).forEach { percent ->
            FilterChip(selected = budget == percent, onClick = {
                budget = percent
                SubtitleDownloaderRepository.updateSettings { it.copy(budgetPercent = percent) }
            }, label = { Text("$percent %") })
        }
    }
    Text(
        stringResource(Res.string.subdl_budget_value, budget, formatBytes(layout.fileSize * budget / 100)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NuvioSectionLabel(stringResource(Res.string.subdl_destination))
    Text(
        if (settings.treeUri == null) stringResource(Res.string.subdl_destination_default) else (settings.treeLabel ?: stringResource(Res.string.subdl_folder_chosen)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { runCatching { folderPicker.launch(null) } }) { Text(stringResource(Res.string.subdl_change_folder)) }
        if (settings.treeUri != null) {
            TextButton(onClick = { SubtitleDownloaderRepository.updateSettings { it.copy(treeUri = null, treeLabel = null) } }) {
                Text(stringResource(Res.string.subdl_default_folder))
            }
        }
    }

    fun queue(trackChoice: TrackChoice, fontChoice: FontChoice) {
        val job = SubtitleDownloaderRepository.enqueue(
            detected = detected,
            stream = request.stream,
            showTitle = request.title,
            contentType = request.contentType,
            videoId = request.videoId,
            parentMetaId = request.parentMetaId,
            parentMetaType = request.parentMetaType,
            season = request.season,
            episode = request.episode,
            episodeTitle = request.episodeTitle,
            tracks = trackChoice,
            fonts = fontChoice,
            mode = mode,
            budgetPercent = budget,
        )
        onQueued(job.id)
        NuvioToastController.show(queuedText)
    }
    val chosenKeys = tracks.filter { it.number in selectedTracks }.map { TrackKey(it.number, it.languageCode, it.name) }
    val chosenFonts = FontChoice(all = selectedFonts.size == fonts.size && fonts.isNotEmpty(), names = fonts.filter { it.index in selectedFonts }.map { it.name })

    if (queuedJobId != null) {
        val state by SubtitleDownloaderRepository.state.collectAsState()
        state.jobs.firstOrNull { it.id == queuedJobId }?.let { JobCard(it) }
    }
    NuvioPrimaryButton(
        text = stringResource(Res.string.subdl_download_selection),
        enabled = chosenKeys.isNotEmpty() || !chosenFonts.none,
        onClick = { queue(TrackChoice(all = false, keys = chosenKeys), chosenFonts) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tracks.isNotEmpty()) {
            OutlinedButton(onClick = { queue(TrackChoice(all = true), FontChoice(all = false)) }) { Text(stringResource(Res.string.subdl_download_all_tracks)) }
        }
        if (fonts.isNotEmpty()) {
            OutlinedButton(onClick = { queue(TrackChoice(all = false), FontChoice(all = true)) }) { Text(stringResource(Res.string.subdl_download_fonts_only)) }
        }
        OutlinedButton(onClick = { queue(TrackChoice(all = true), FontChoice(all = true)) }) { Text(stringResource(Res.string.subdl_download_everything)) }
        if (request.episode != null && request.season != null) {
            OutlinedButton(onClick = { onSeason(SeasonLaunch(request, detected, chosenKeys)) }) { Text(stringResource(Res.string.subdl_season)) }
        }
    }
}

@Composable
internal fun CheckRow(checked: Boolean, title: String, subtitle: String?, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A job as the queue shows it: its state, live progress while it runs, and what it saved. */
@Composable
internal fun JobCard(job: SubtitleJob, actions: (@Composable () -> Unit)? = null) {
    val progressMap by SubtitleDownloaderRepository.progress.collectAsState()
    val progress = progressMap[job.id]
    NuvioSurfaceCard {
        Text(job.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(statusText(job.status)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        job.matchedRelease?.let { Text(stringResource(Res.string.subdl_release, it), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (progress != null && job.status == JobStatus.RUNNING) ProgressBlock(progress)
        job.message?.takeIf { job.status == JobStatus.FAILED || job.status == JobStatus.NEEDS_BUDGET }?.let { code ->
            val text = errorText(code)
            Text(if (code.startsWith("http-")) stringResource(text, code.removePrefix("http-")) else stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        job.result?.let { result ->
            result.tracks.forEach { t ->
                Text(
                    stringResource(Res.string.subdl_result_track, t.fileName, formatCount(t.events.toLong()), stringResource(integrityText(t.integrity))),
                    style = MaterialTheme.typography.bodySmall,
                )
                t.notes.forEach { note -> Text("· $note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (result.fontsTotal > 0) Text(stringResource(Res.string.subdl_result_fonts, result.fontsSaved, result.fontsReused), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    Res.string.subdl_result_summary,
                    stringResource(strategyText(runCatching { Strategy.valueOf(result.strategy) }.getOrDefault(Strategy.NONE))),
                    formatCount(result.requests),
                    formatBytes(result.trafficBytes),
                    percent(result.trafficBytes, result.fileSize),
                    formatDuration(result.elapsedMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(Res.string.subdl_saved_in, result.folder), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions?.invoke()
    }
}

@Composable
internal fun ProgressBlock(p: EngineProgress) {
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(progress = { p.coveredFraction.toFloat() }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    Text(stringResource(Res.string.subdl_strategy, stringResource(strategyText(p.strategy))), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(Res.string.subdl_progress_line, formatCount(p.events.toLong()) + (p.expectedEvents?.let { " / ${formatCount(it)}" } ?: ""), p.fontsDone, p.fontsTotal), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(Res.string.subdl_stats_line, "${(p.coveredFraction * 100).toInt()} %", formatBytes(p.bytesPerSecond.toLong()), formatDuration(p.elapsedMs), p.etaMs?.let(::formatDuration) ?: "—"),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(Res.string.subdl_net_line, formatCount(p.requests), formatBytes(p.trafficBytes), percent(p.trafficBytes, p.fileSize), p.concurrency),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun strategyText(strategy: Strategy): StringResource = when (strategy) {
    Strategy.INDEX -> Res.string.subdl_strategy_INDEX
    Strategy.SPARSE -> Res.string.subdl_strategy_SPARSE
    Strategy.HYBRID -> Res.string.subdl_strategy_HYBRID
    Strategy.NONE -> Res.string.subdl_strategy_NONE
}

internal fun integrityText(name: String): StringResource = when (name) {
    "VERIFIED_STATISTICS" -> Res.string.subdl_integrity_VERIFIED_STATISTICS
    "VERIFIED_STRUCTURE" -> Res.string.subdl_integrity_VERIFIED_STRUCTURE
    "CONSISTENT_INDEX" -> Res.string.subdl_integrity_CONSISTENT_INDEX
    "UNSUPPORTED" -> Res.string.subdl_integrity_UNSUPPORTED
    else -> Res.string.subdl_integrity_INCOMPLETE
}

internal fun statusText(status: JobStatus): StringResource = when (status) {
    JobStatus.QUEUED -> Res.string.subdl_status_queued
    JobStatus.RESOLVING -> Res.string.subdl_status_resolving
    JobStatus.RUNNING -> Res.string.subdl_status_running
    JobStatus.PAUSED -> Res.string.subdl_status_paused
    JobStatus.DONE -> Res.string.subdl_status_done
    JobStatus.PARTIAL -> Res.string.subdl_status_partial
    JobStatus.FAILED -> Res.string.subdl_status_failed
    JobStatus.CANCELLED -> Res.string.subdl_status_cancelled
    JobStatus.NEEDS_BUDGET -> Res.string.subdl_status_budget
}

/** A resource string formatted outside composition-only helpers, for building lists in composables. */
@Composable
private fun stringResourceFormat(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "%d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> "%d min %02d s".format(seconds / 60, seconds % 60)
        ms >= 10_000 -> "$seconds s"
        else -> "%.1f s".format(ms / 1000.0)
    }
}

internal fun formatCount(n: Long): String = "%,d".format(n).replace(',', '.')

internal fun percent(part: Long, whole: Long): String = if (whole <= 0) "—" else "%.1f %%".format(part * 100.0 / whole)
