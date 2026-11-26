package com.nuvio.app.features.subtitledownloader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val DIAGNOSTIC_BUDGET = 48L * 1024 * 1024

/** Runs [SubtitleDiagnostics] on the request's link and exports the report as TXT or JSON. */
@Composable
internal fun SubtitleDiagnosticsDialog(request: SubtitleDownloaderRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    val savedText = stringResource(Res.string.subdl_diag_exported)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val content = pending ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) } } }
                    .onSuccess { NuvioToastController.show(savedText) }
            }
        }
        pending = null
    }
    Dialog(onDismissRequest = { job?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.subdl_diag_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(stringResource(Res.string.subdl_close)) }
                }
                Text(stringResource(Res.string.subdl_diag_intro, formatBytes(DIAGNOSTIC_BUDGET)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.subdl_diag_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val running = step != null
                NuvioPrimaryButton(text = stringResource(Res.string.subdl_diag_run), enabled = !running, onClick = {
                    report = null
                    error = null
                    job = scope.launch(Dispatchers.Default) {
                        try {
                            report = SubtitleDiagnostics.run(request.stream, request.season, request.episode, DIAGNOSTIC_BUDGET) { step = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            error = e.javaClass.simpleName
                        } finally {
                            step = null
                        }
                    }
                })
                step?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.subdl_diag_running, it))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                report?.let { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pending = SubtitleDiagnostics.toText(r); exporter.launch("nuvio-subtitle-diagnostics.txt") }) {
                            Text(stringResource(Res.string.subdl_diag_export_txt))
                        }
                        OutlinedButton(onClick = { pending = SubtitleDiagnostics.toJson(r); exporter.launch("nuvio-subtitle-diagnostics.json") }) {
                            Text(stringResource(Res.string.subdl_diag_export_json))
                        }
                    }
                    SelectionContainer {
                        Text(SubtitleDiagnostics.toText(r), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
