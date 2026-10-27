package com.nuvio.app.features.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.player.seekpreview.SeekPreviewController
import com.nuvio.app.features.player.seekpreview.SeekPreviewOverlay
import com.nuvio.app.features.streams.StreamBadge
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.i18n.localizedEpisodeTitle

internal val PlayerTimelineContentInset = 2.dp



@Composable
internal fun PlayerTimelineDetails(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    releaseInfo: String?,
    metrics: PlayerLayoutMetrics,
    badges: List<StreamBadge> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    Column(modifier.padding(horizontal = PlayerTimelineContentInset)) {
        Text(
            text = title,
            style = typeScale.titleLg.copy(
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * 1.16f,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (seasonNumber != null && episodeNumber != null) {
            val episodeCode = stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
            val displayEpisodeTitle = localizedEpisodeTitle(episodeTitle, episodeNumber)
            Text(
                text = if (displayEpisodeTitle.isNullOrBlank()) episodeCode else "$episodeCode • $displayEpisodeTitle",
                style = typeScale.bodyMd.copy(fontSize = metrics.episodeInfoSize),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badges.isNotEmpty()) {
            PlayerBadges(badges = badges, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (!releaseInfo.isNullOrBlank()) {
            Text(
                text = releaseInfo,
                style = typeScale.labelSm.copy(fontSize = metrics.metadataSize),
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ReportTimelineTouch(touching: Boolean, onTouchChange: (Boolean) -> Unit) {
    val latestOnTouchChange by rememberUpdatedState(onTouchChange)
    LaunchedEffect(touching) { latestOnTouchChange(touching) }
    DisposableEffect(Unit) {
        onDispose { latestOnTouchChange(false) }
    }
}

@Composable
internal fun PlayerTimeline(
    snapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    seekPreview: SeekPreviewController? = null,
    onTouchChange: (Boolean) -> Unit = {},
    /** Each chapter after the first gets a cut where it starts. */
    chapters: List<PlayerChapter> = emptyList(),
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        PlayerProgressBar(
            positionMs = displayedPositionMs,
            durationMs = snapshot.durationMs,
            bufferedPositionMs = snapshot.bufferedPositionMs,
            onScrubChange = onScrubChange,
            onScrubFinished = onScrubFinished,
            enabled = enabled,
            chapters = chapters,
            seekPreview = seekPreview,
            onTouchChange = onTouchChange,
            // The bar takes 24 dp in the layout; its 48 dp touch area reaches above it.
            lineCenter = 35.dp,
            modifier = modifier
                .fillMaxWidth()
                .height(24.dp)
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .requiredHeight(PlayerProgressBarSpec.TouchHeight),
        )
    }
}

/** The name of the chapter at [positionMs], or "Chapter N" when the file gives it none. */
@Composable
internal fun PlayerChapterTitle(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val index = PlayerChapters.indexAt(chapters, positionMs)
    if (index < 0) return
    Text(
        text = chapters[index].title ?: stringResource(Res.string.compose_player_chapter_number, index + 1),
        style = MaterialTheme.nuvioTypeScale.bodyMd,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = PlayerTimelineContentInset),
    )
}
