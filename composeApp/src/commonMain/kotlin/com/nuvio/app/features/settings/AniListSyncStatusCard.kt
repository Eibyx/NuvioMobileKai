package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.anilist.AniListQueueState
import com.nuvio.app.features.anilist.AniListTracker
import com.nuvio.app.features.anilist.AniListTrackingWrites
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anilist_sync_error_not_found
import nuvio.composeapp.generated.resources.anilist_sync_error_reason
import nuvio.composeapp.generated.resources.anilist_sync_last
import nuvio.composeapp.generated.resources.anilist_sync_never
import nuvio.composeapp.generated.resources.anilist_sync_notify
import nuvio.composeapp.generated.resources.anilist_sync_notify_description
import nuvio.composeapp.generated.resources.anilist_sync_pending_count
import nuvio.composeapp.generated.resources.anilist_sync_pending_item
import nuvio.composeapp.generated.resources.anilist_sync_pending_none
import nuvio.composeapp.generated.resources.anilist_sync_pending_retry
import nuvio.composeapp.generated.resources.anilist_sync_reason_auth
import nuvio.composeapp.generated.resources.anilist_sync_reason_mapping
import nuvio.composeapp.generated.resources.anilist_sync_reason_network
import nuvio.composeapp.generated.resources.anilist_sync_reason_not_found
import nuvio.composeapp.generated.resources.anilist_sync_reason_profile
import nuvio.composeapp.generated.resources.anilist_sync_reason_rate_limit
import nuvio.composeapp.generated.resources.anilist_sync_reason_server
import nuvio.composeapp.generated.resources.anilist_sync_reconnect
import nuvio.composeapp.generated.resources.anilist_sync_recent_errors
import nuvio.composeapp.generated.resources.anilist_sync_retry_now
import nuvio.composeapp.generated.resources.anilist_sync_status_title
import org.jetbrains.compose.resources.stringResource

/** Settings > Tracking > AniList: last sync, pending sends, recent errors and the sync notice switch. */
@Composable
internal fun AniListSyncStatusCard(modifier: Modifier = Modifier) {
    val auth by AniListTracker.authState.collectAsStateWithLifecycle()
    val state by AniListTracker.queue.state.collectAsStateWithLifecycle()
    // Kept visible while sends wait for the user to connect AniList again.
    if (!auth.isAuthenticated && state.pending.isEmpty()) return
    AniListSyncStatusContent(
        state = state,
        signedIn = auth.isAuthenticated,
        onRetryNow = AniListTracker::retryPendingNow,
        onNotifyChange = AniListTracker::setNotifyOnSync,
        modifier = modifier,
    )
}

@Composable
internal fun AniListSyncStatusContent(
    state: AniListQueueState,
    signedIn: Boolean = true,
    onRetryNow: () -> Unit,
    onNotifyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = tokens.shapes.card,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.anilist_sync_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.lastSyncAtMs?.let { at ->
                    stringResource(
                        Res.string.anilist_sync_last,
                        formatLocalDateTime(at),
                        state.lastSyncTitle.orEmpty(),
                        state.lastSyncEpisode ?: 0,
                    )
                } ?: stringResource(Res.string.anilist_sync_never),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = if (state.pending.isEmpty()) {
                    stringResource(Res.string.anilist_sync_pending_none)
                } else {
                    stringResource(Res.string.anilist_sync_pending_count, state.pending.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!signedIn) {
                Text(
                    text = stringResource(Res.string.anilist_sync_reconnect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
            state.pending.forEach { pending ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(Res.string.anilist_sync_pending_item, pending.title, pending.episode ?: 1),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pending.lastError?.let { reason ->
                        Text(
                            text = stringResource(
                                Res.string.anilist_sync_pending_retry,
                                formatLocalDateTime(pending.nextAttemptAtMs),
                                aniListReasonText(reason),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.failures.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                Text(
                    text = stringResource(Res.string.anilist_sync_recent_errors),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                state.failures.take(5).forEach { failure ->
                    Text(
                        text = if (failure.notFound) {
                            stringResource(Res.string.anilist_sync_error_not_found, failure.title)
                        } else {
                            stringResource(Res.string.anilist_sync_error_reason, failure.title, aniListReasonText(failure.reason))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failure.notFound) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onRetryNow, enabled = signedIn && state.pending.isNotEmpty()) {
                Text(stringResource(Res.string.anilist_sync_retry_now))
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(Res.string.anilist_sync_notify),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.anilist_sync_notify_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(checked = state.notifyOnSync, onCheckedChange = onNotifyChange)
            }
        }
    }
}

/** The reasons the queue logs in English, in the user's language. */
@Composable
private fun aniListReasonText(reason: String): String = when {
    reason == AniListTrackingWrites.AUTH_EXPIRED_REASON -> stringResource(Res.string.anilist_sync_reason_auth)
    reason.startsWith("AniList rate limit") -> stringResource(Res.string.anilist_sync_reason_rate_limit)
    reason.startsWith("AniList answered") ->
        stringResource(Res.string.anilist_sync_reason_server, reason.substringAfterLast(' '))
    reason.startsWith("ARM") || reason.startsWith("AniList media lookup") -> stringResource(Res.string.anilist_sync_reason_mapping)
    reason.startsWith("no AniList entry") || reason.startsWith("AniList has no entry") || reason.startsWith("not found") ->
        stringResource(Res.string.anilist_sync_reason_not_found)
    reason == "profile changed" -> stringResource(Res.string.anilist_sync_reason_profile)
    else -> stringResource(Res.string.anilist_sync_reason_network)
}
