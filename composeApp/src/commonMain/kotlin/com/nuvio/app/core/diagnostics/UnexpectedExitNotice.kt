package com.nuvio.app.core.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioBottomNavigationBarInsets
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.diagnostics_export
import nuvio.composeapp.generated.resources.diagnostics_export_failed
import nuvio.composeapp.generated.resources.diagnostics_notice_kind_anr
import nuvio.composeapp.generated.resources.diagnostics_notice_kind_crash
import nuvio.composeapp.generated.resources.diagnostics_notice_kind_low_memory
import nuvio.composeapp.generated.resources.diagnostics_notice_kind_native
import nuvio.composeapp.generated.resources.diagnostics_notice_kind_other
import nuvio.composeapp.generated.resources.diagnostics_notice_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Shows [UnexpectedExitNoticeCard] after a crash, at the bottom of the screen above the tab bar. */
@Composable
fun UnexpectedExitNoticeHost(modifier: Modifier = Modifier) {
    if (!AppDiagnostics.isSupported) return
    val notice by AppDiagnostics.notice.collectAsState()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val navBarBottom = nuvioBottomNavigationBarInsets().asPaddingValues().calculateBottomPadding()

    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val current = notice ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = navBarBottom + NuvioTokens.Space.s72)
                .padding(horizontal = MaterialTheme.nuvio.spacing.screenHorizontal),
            contentAlignment = Alignment.BottomCenter,
        ) {
            UnexpectedExitNoticeCard(
                kind = current.kind,
                exporting = exporting,
                onExport = {
                    exporting = true
                    scope.launch {
                        AppDiagnostics.exportAndShare()
                            .onSuccess { AppDiagnostics.dismissNotice() }
                            .onFailure { NuvioToastController.show(getString(Res.string.diagnostics_export_failed)) }
                        exporting = false
                    }
                },
                onDismiss = AppDiagnostics::dismissNotice,
            )
        }
    }
}

@Composable
fun UnexpectedExitNoticeCard(
    kind: UnexpectedExitKind,
    exporting: Boolean,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = tokens.shapes.card,
        color = tokens.colors.surfacePopover,
        contentColor = tokens.colors.textPrimary,
        shadowElevation = tokens.elevation.modal,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp).size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.diagnostics_notice_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(kind.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                TextButton(
                    onClick = onExport,
                    enabled = !exporting,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.diagnostics_export),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = tokens.colors.textMuted,
                )
            }
        }
    }
}

private fun UnexpectedExitKind.descriptionRes() = when (this) {
    UnexpectedExitKind.Crash -> Res.string.diagnostics_notice_kind_crash
    UnexpectedExitKind.NativeCrash -> Res.string.diagnostics_notice_kind_native
    UnexpectedExitKind.Anr -> Res.string.diagnostics_notice_kind_anr
    UnexpectedExitKind.LowMemory -> Res.string.diagnostics_notice_kind_low_memory
    UnexpectedExitKind.Other -> Res.string.diagnostics_notice_kind_other
}
