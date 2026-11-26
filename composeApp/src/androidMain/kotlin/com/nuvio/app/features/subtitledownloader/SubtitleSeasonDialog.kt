package com.nuvio.app.features.subtitledownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Picks episodes of the season to extract with the same release, tracks and fonts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleSeasonDialog(launch: SeasonLaunch, onDismiss: () -> Unit, onQueued: () -> Unit) {
    val request = launch.request
    var episodes by remember { mutableStateOf<List<MetaVideo>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var sameTracks by remember { mutableStateOf(launch.keys.isNotEmpty()) }
    val tracks = launch.detected.detection.layout.assTracks
    val languages = tracks.mapNotNull { it.languageCode }.distinct()
    val chosenLanguages = remember { mutableStateListOf<String>() }
    LaunchedEffect(request.parentMetaId) {
        val meta = runCatching { MetaDetailsRepository.fetch(request.parentMetaType, request.parentMetaId) }.getOrNull()
        val list = meta?.videos.orEmpty().filter { it.season == request.season && it.episode != null }.sortedBy { it.episode }
        episodes = list
        selected.clear()
        selected.addAll(list.map { it.id })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.subdl_season_title, request.season ?: 1) + " · " + request.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.subdl_close)) }
                }
                Text(stringResource(Res.string.subdl_season_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NuvioSectionLabel(stringResource(Res.string.subdl_season_tracks))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (launch.keys.isNotEmpty()) {
                        FilterChip(selected = sameTracks, onClick = { sameTracks = true }, label = { Text(stringResource(Res.string.subdl_season_tracks_same)) })
                    }
                    FilterChip(selected = !sameTracks, onClick = { sameTracks = false }, label = { Text(stringResource(Res.string.subdl_season_tracks_all)) })
                }
                if (!sameTracks && languages.size > 1) {
                    NuvioSectionLabel(stringResource(Res.string.subdl_season_languages))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languages.forEach { code ->
                            FilterChip(
                                selected = code in chosenLanguages,
                                onClick = { if (code in chosenLanguages) chosenLanguages -= code else chosenLanguages += code },
                                label = { Text(SubtitleStore.displayLanguage(code)) },
                            )
                        }
                    }
                }
                Text(stringResource(Res.string.subdl_season_fonts), style = MaterialTheme.typography.bodySmall)
                NuvioSectionLabel(stringResource(Res.string.subdl_season_episodes))
                val list = episodes
                if (list == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.subdl_season_loading))
                    }
                } else {
                    Row {
                        TextButton(onClick = { selected.clear(); selected.addAll(list.map { it.id }) }) { Text(stringResource(Res.string.subdl_select_all)) }
                        TextButton(onClick = { selected.clear() }) { Text(stringResource(Res.string.subdl_select_none)) }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(list, key = { it.id }) { video ->
                            CheckRow(
                                checked = video.id in selected,
                                title = "E${video.episode.toString().padStart(2, '0')} · ${video.title}",
                                subtitle = video.released?.take(10),
                            ) { checked -> if (checked) selected += video.id else selected -= video.id }
                        }
                    }
                    val count = minOf(200, list.count { it.id in selected })
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.subdl_season_start, count),
                        enabled = count > 0,
                        onClick = {
                            val settings = SubtitleDownloaderRepository.settings
                            SubtitleDownloaderRepository.enqueueEpisodes(
                                reference = request.stream,
                                referenceSeason = request.season,
                                referenceEpisode = request.episode,
                                showTitle = request.title,
                                contentType = request.contentType,
                                parentMetaId = request.parentMetaId,
                                parentMetaType = request.parentMetaType,
                                episodes = list.filter { it.id in selected }.map {
                                    SubtitleDownloaderRepository.EpisodeTarget(it.id, it.season, it.episode, it.title)
                                },
                                tracks = if (sameTracks) {
                                    TrackChoice(all = false, keys = launch.keys.map { it.copy(number = null) })
                                } else if (chosenLanguages.isNotEmpty()) {
                                    TrackChoice(all = false, languages = chosenLanguages.toList())
                                } else {
                                    TrackChoice(all = true)
                                },
                                fonts = FontChoice(all = true),
                                mode = runCatching { ExtractionMode.valueOf(settings.mode) }.getOrDefault(ExtractionMode.AUTO),
                                budgetPercent = settings.budgetPercent,
                            )
                            onQueued()
                        },
                    )
                }
            }
        }
    }
}
