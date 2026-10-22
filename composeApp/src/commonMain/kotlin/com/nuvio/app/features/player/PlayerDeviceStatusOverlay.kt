package com.nuvio.app.features.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import kotlin.math.roundToLong
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_status_ends_at
import nuvio.composeapp.generated.resources.player_status_network_cellular
import nuvio.composeapp.generated.resources.player_status_network_offline
import nuvio.composeapp.generated.resources.player_status_network_unknown
import nuvio.composeapp.generated.resources.player_status_network_wifi
import nuvio.composeapp.generated.resources.player_status_time_left
import org.jetbrains.compose.resources.stringResource

/*
 * The clock, battery, network and finish time over the video, ported from yesnt10's Enhanced fork.
 * It shows while the controls are hidden, so it never sits on top of them.
 */

@Composable
internal fun PlayerDeviceStatusOverlay(
    playbackSnapshot: PlayerPlaybackSnapshot,
    metrics: PlayerLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    val status = rememberPlayerDeviceStatus()
    val colors = MaterialTheme.nuvio.colors
    val remainingLabel = playerFinishRemainingLabel(playbackSnapshot)
    val finishClockLabel = playerFinishClockLabel(status.timeLabel, playbackSnapshot)
    Surface(
        modifier = modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(999.dp),
        color = colors.playerControlsBackground.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, colors.playerControlsForeground.copy(alpha = 0.14f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerDeviceStatusItem(
                icon = when (status.networkType) {
                    PlayerDeviceNetworkType.Wifi -> Icons.Rounded.Wifi
                    PlayerDeviceNetworkType.Cellular -> Icons.Rounded.SignalCellularAlt
                    PlayerDeviceNetworkType.Offline -> Icons.Rounded.WifiOff
                    PlayerDeviceNetworkType.Unknown -> Icons.Rounded.Wifi
                },
                text = stringResource(
                    when (status.networkType) {
                        PlayerDeviceNetworkType.Wifi -> Res.string.player_status_network_wifi
                        PlayerDeviceNetworkType.Cellular -> Res.string.player_status_network_cellular
                        PlayerDeviceNetworkType.Offline -> Res.string.player_status_network_offline
                        PlayerDeviceNetworkType.Unknown -> Res.string.player_status_network_unknown
                    },
                ),
                metrics = metrics,
            )
            PlayerDeviceStatusText(text = status.timeLabel, metrics = metrics)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerBatteryGlyph(
                    percent = status.batteryPercent,
                    charging = status.batteryCharging,
                    metrics = metrics,
                )
                PlayerDeviceStatusText(text = status.batteryPercent?.let { "$it%" } ?: "--", metrics = metrics)
            }
            if (remainingLabel != null) {
                PlayerDeviceStatusItem(
                    icon = Icons.Rounded.AccessTime,
                    text = finishClockLabel?.let { finishClock ->
                        stringResource(Res.string.player_status_ends_at, finishClock, remainingLabel)
                    } ?: stringResource(Res.string.player_status_time_left, remainingLabel),
                    metrics = metrics,
                )
            }
        }
    }
}

@Composable
private fun PlayerDeviceStatusItem(
    icon: ImageVector,
    text: String,
    metrics: PlayerLayoutMetrics,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.nuvio.colors.playerControlsForeground.copy(alpha = 0.88f),
            modifier = Modifier.size(metrics.headerIconSize * 0.74f),
        )
        PlayerDeviceStatusText(text = text, metrics = metrics)
    }
}

@Composable
private fun PlayerDeviceStatusText(text: String, metrics: PlayerLayoutMetrics) {
    Text(
        text = text,
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(
            fontSize = metrics.metadataSize,
            lineHeight = metrics.metadataSize * 1.2f,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.nuvio.colors.playerControlsForeground.copy(alpha = 0.9f),
        maxLines = 1,
    )
}

@Composable
private fun PlayerBatteryGlyph(
    percent: Int?,
    charging: Boolean,
    metrics: PlayerLayoutMetrics,
) {
    val colors = MaterialTheme.nuvio.colors
    val level = percent?.coerceIn(0, 100)
    val activeColor = if (charging) colors.success else colors.playerControlsForeground.copy(alpha = 0.9f)
    val outlineColor = colors.playerControlsForeground.copy(alpha = 0.72f)
    val bodyShape = RoundedCornerShape(3.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = metrics.headerIconSize * 0.86f, height = metrics.headerIconSize * 0.46f)
                .clip(bodyShape)
                .border(width = 1.2.dp, color = outlineColor, shape = bodyShape)
                .padding(2.dp),
        ) {
            if (level != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(level / 100f)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(activeColor),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(width = metrics.headerIconSize * 0.08f, height = metrics.headerIconSize * 0.24f)
                .clip(RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp))
                .background(outlineColor),
        )
    }
}

/** Minutes left at the current speed, or null when there is no known duration. */
private fun remainingMinutes(snapshot: PlayerPlaybackSnapshot): Long? {
    val durationMs = snapshot.durationMs.takeIf { it > 0L } ?: return null
    val remainingMs = (durationMs - snapshot.positionMs).coerceAtLeast(0L)
    if (remainingMs <= 0L) return null
    val speed = snapshot.playbackSpeed.takeIf { it > 0.05f } ?: 1f
    return ((remainingMs / speed) / 60_000f).roundToLong().coerceAtLeast(1L)
}

private fun playerFinishRemainingLabel(snapshot: PlayerPlaybackSnapshot): String? {
    val minutesLeft = remainingMinutes(snapshot) ?: return null
    val hours = minutesLeft / 60L
    val minutes = minutesLeft % 60L
    return when {
        hours <= 0L -> "${minutes}m"
        minutes <= 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

/** The clock time playback ends, in 24-hour form, worked out from the displayed clock. */
private fun playerFinishClockLabel(currentTimeLabel: String, snapshot: PlayerPlaybackSnapshot): String? {
    val minutesLeft = remainingMinutes(snapshot) ?: return null
    val clockMatch = Regex("""(\d{1,2})\D+(\d{2})""").find(currentTimeLabel) ?: return null
    var hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
    val minute = clockMatch.groupValues[2].toIntOrNull() ?: return null
    // A 12-hour clock: move afternoon times onto the 24-hour clock before adding.
    val afternoon = currentTimeLabel.contains("PM", ignoreCase = true) || currentTimeLabel.contains("p.", ignoreCase = true)
    if (afternoon && hour < 12) hour += 12
    if (!afternoon && hour == 12 && currentTimeLabel.contains("AM", ignoreCase = true)) hour = 0
    val minutesPerDay = 24L * 60L
    val totalMinutes = ((hour * 60L + minute + minutesLeft) % minutesPerDay + minutesPerDay) % minutesPerDay
    val finishHour = (totalMinutes / 60L).toInt()
    val finishMinute = (totalMinutes % 60L).toInt()
    return "${finishHour.toString().padStart(2, '0')}:${finishMinute.toString().padStart(2, '0')}"
}
