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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Every Subtitle Downloader job, newest first: pause, resume, cancel, raise the budget, remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleQueueDialog(onDismiss: () -> Unit) {
    val state by SubtitleDownloaderRepository.state.collectAsState()
    val jobs = state.jobs.sortedWith(compareByDescending<SubtitleJob> { it.isActive }.thenByDescending { it.createdAtMs })
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.subdl_queue), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.subdl_close)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = SubtitleDownloaderRepository::pauseAll) { Text(stringResource(Res.string.subdl_pause_all)) }
                    TextButton(onClick = SubtitleDownloaderRepository::resumeAll) { Text(stringResource(Res.string.subdl_resume_all)) }
                    TextButton(onClick = SubtitleDownloaderRepository::clearHistory) { Text(stringResource(Res.string.subdl_clear_history)) }
                }
                Text(
                    stringResource(Res.string.subdl_settings_cache, state.settings.memoryCacheMb, state.settings.diskCacheMb) + " · " +
                        stringResource(Res.string.subdl_settings_parallel, state.settings.maxParallelJobs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(48 to 128, 96 to 256, 256 to 1024, 512 to 2048).forEach { (ram, disk) ->
                        TextButton(onClick = { SubtitleDownloaderRepository.updateSettings { it.copy(memoryCacheMb = ram, diskCacheMb = disk) } }) {
                            Text("$ram/$disk MB")
                        }
                    }
                    (1..3).forEach { n ->
                        TextButton(onClick = { SubtitleDownloaderRepository.updateSettings { it.copy(maxParallelJobs = n) } }) { Text("×$n") }
                    }
                }
                if (jobs.isEmpty()) Text(stringResource(Res.string.subdl_queue_empty))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(jobs, key = { it.id }) { job ->
                        JobCard(job) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (job.isActive) {
                                    TextButton(onClick = { SubtitleDownloaderRepository.pause(job.id) }) { Text(stringResource(Res.string.subdl_pause)) }
                                    TextButton(onClick = { SubtitleDownloaderRepository.cancel(job.id) }) { Text(stringResource(Res.string.subdl_cancel)) }
                                }
                                if (job.status == JobStatus.PAUSED || job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED || job.status == JobStatus.PARTIAL) {
                                    TextButton(onClick = { SubtitleDownloaderRepository.resume(job.id) }) {
                                        Text(stringResource(if (job.status == JobStatus.PAUSED) Res.string.subdl_resume else Res.string.subdl_retry))
                                    }
                                }
                                if (job.status == JobStatus.NEEDS_BUDGET || job.status == JobStatus.PARTIAL) {
                                    val raised = nextBudget(job.budgetPercent)
                                    if (raised != null) {
                                        TextButton(onClick = { SubtitleDownloaderRepository.resume(job.id, raised) }) { Text(stringResource(Res.string.subdl_raise_budget, raised)) }
                                    }
                                }
                                if (!job.isActive) {
                                    TextButton(onClick = { SubtitleDownloaderRepository.remove(job.id) }) { Text(stringResource(Res.string.subdl_remove)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nextBudget(current: Int): Int? = listOf(10, 25, 50, 75, 90).firstOrNull { it > current }
