package com.nuvio.app.features.anilist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.components.DetailActionBadge
import com.nuvio.app.features.details.components.DetailSecondaryAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import com.nuvio.app.features.tracking.parseAnimeIdRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.anilist_part_not_on_list
import nuvio.composeapp.generated.resources.anilist_part_progress
import nuvio.composeapp.generated.resources.anilist_part_progress_unknown
import nuvio.composeapp.generated.resources.anilist_sync_now
import nuvio.composeapp.generated.resources.anilist_sync_now_started
import org.jetbrains.compose.resources.getString
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anilist_icon_linked
import nuvio.composeapp.generated.resources.anilist_icon_not_found
import nuvio.composeapp.generated.resources.anilist_icon_pending
import nuvio.composeapp.generated.resources.anilist_link_auto
import nuvio.composeapp.generated.resources.anilist_logo
import nuvio.composeapp.generated.resources.anilist_link_manual
import nuvio.composeapp.generated.resources.anilist_link_current
import nuvio.composeapp.generated.resources.anilist_link_description
import nuvio.composeapp.generated.resources.anilist_link_episodes
import nuvio.composeapp.generated.resources.anilist_link_movie
import nuvio.composeapp.generated.resources.anilist_link_no_results
import nuvio.composeapp.generated.resources.anilist_link_remove
import nuvio.composeapp.generated.resources.anilist_link_search_failed
import nuvio.composeapp.generated.resources.anilist_link_search_hint
import nuvio.composeapp.generated.resources.anilist_link_season
import nuvio.composeapp.generated.resources.anilist_link_title
import org.jetbrains.compose.resources.stringResource

/** True for titles AniList could track: animation, anime ids, or a title the AniList list already maps. */
fun MetaDetails.isAniListCandidate(): Boolean =
    parseAnimeIdRef(id) != null ||
        genres.any { it.equals("Anime", ignoreCase = true) || it.equals("Animation", ignoreCase = true) } ||
        AniListTracker.knownMediaTitle(id) != null

/** The season the link applies to when the page opens: the only one, else the first regular season. */
private fun MetaDetails.linkSeasons(): List<Int> =
    videos.mapNotNull { it.season }.filter { it > 0 }.distinct().sorted().ifEmpty { listOf(1) }

/** What the AniList icon on the detail page shows for a title. */
internal sealed interface AniListLinkStatus {
    /** Linked (automatically or by hand): [titles] are the AniList entries, one per linked season. */
    data class Linked(val titles: List<String>) : AniListLinkStatus
    /** Not linked yet: it links itself when the user starts watching. */
    data object Pending : AniListLinkStatus
    /** A season could not be matched: the user should link it by hand. */
    data object NotFound : AniListLinkStatus
}

/** The status of [keys] (one per season, or the movie): a missing match wins, then any link. */
internal fun aniListLinkStatus(keys: List<String>, state: AniListQueueState): AniListLinkStatus {
    if (keys.any { it in state.unmatched }) return AniListLinkStatus.NotFound
    val titles = keys.mapNotNull { key -> state.links.firstOrNull { it.key == key }?.title }.distinct()
    return if (titles.isEmpty()) AniListLinkStatus.Pending else AniListLinkStatus.Linked(titles)
}

/**
 * The AniList button of the detail page's action row (or its "more" menu): the AniList logo, with
 * a check when linked and a warning mark when no entry was found. It opens [AniListLinkSheet].
 * Null when AniList is not connected or the title does not look like anime.
 */
@Composable
fun aniListDetailAction(meta: MetaDetails): DetailSecondaryAction? {
    aniListPreviewStatus?.let { return aniListAction(it, onClick = {}) }
    val auth by AniListTracker.authState.collectAsStateWithLifecycle()
    val candidate = remember(meta) { meta.isAniListCandidate() }
    if (!auth.isAuthenticated || !candidate) return null
    val queueState by AniListTracker.queue.state.collectAsStateWithLifecycle()
    val isMovie = meta.type.equals("movie", ignoreCase = true)
    val seasons: List<Int?> = if (isMovie) listOf(null) else meta.linkSeasons()
    val keys = seasons.map { AniListTracker.linkKey(meta.id, meta.type, it) }
    val status = aniListLinkStatus(keys, queueState)
    var showSheet by remember(meta.id) { mutableStateOf(false) }
    if (showSheet) {
        val firstUnmatched = seasons.firstOrNull { AniListTracker.linkKey(meta.id, meta.type, it) in queueState.unmatched }
        val firstLinked = seasons.firstOrNull { season ->
            queueState.links.any { it.key == AniListTracker.linkKey(meta.id, meta.type, season) }
        }
        AniListLinkSheet(
            meta = meta,
            initialSeason = firstUnmatched ?: firstLinked ?: seasons.firstOrNull(),
            onDismiss = { showSheet = false },
        )
    }
    return aniListAction(status, onClick = { showSheet = true })
}

/** Screenshot tests only: shows the button in this state without a connected account. */
internal var aniListPreviewStatus: AniListLinkStatus? = null

/** The action for [status]; kept apart from the tracker so screenshots can show every state. */
@Composable
internal fun aniListAction(status: AniListLinkStatus, onClick: () -> Unit): DetailSecondaryAction =
    DetailSecondaryAction(
        label = when (status) {
            is AniListLinkStatus.Linked -> stringResource(Res.string.anilist_icon_linked, status.titles.joinToString(", "))
            AniListLinkStatus.Pending -> stringResource(Res.string.anilist_icon_pending)
            AniListLinkStatus.NotFound -> stringResource(Res.string.anilist_icon_not_found)
        },
        icon = Icons.Rounded.Link,
        drawable = Res.drawable.anilist_logo,
        keepDrawableColors = true,
        badge = when (status) {
            is AniListLinkStatus.Linked -> DetailActionBadge.Linked
            AniListLinkStatus.Pending -> DetailActionBadge.None
            AniListLinkStatus.NotFound -> DetailActionBadge.Warning
        },
        onClick = onClick,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListLinkSheet(meta: MetaDetails, initialSeason: Int?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isMovie = meta.type.equals("movie", ignoreCase = true)
    val seasons = remember(meta) { if (isMovie) emptyList() else meta.linkSeasons() }
    var season by remember(meta.id) { mutableStateOf(initialSeason ?: seasons.firstOrNull()) }
    var query by remember(meta.id) { mutableStateOf(meta.name) }
    var results by remember { mutableStateOf<List<AniListMedia>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val queueState by AniListTracker.queue.state.collectAsStateWithLifecycle()
    val key = AniListTracker.linkKey(meta.id, meta.type, season)
    val currentLink = queueState.links.firstOrNull { it.key == key }
    val imports by AniListTracker.importer.imports.collectAsStateWithLifecycle()
    val seasonImport = imports[key]
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(query) {
        delay(350)
        failed = false
        results = null
        results = try {
            AniListTracker.searchAnime(query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            InAppLogger.warn("Sync/AniList", "Search failed: ${InAppLogger.throwableSummary(error)}")
            failed = true
            emptyList()
        }
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.anilist_link_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.anilist_link_description),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            if (seasons.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { candidate ->
                        FilterChip(
                            selected = candidate == season,
                            onClick = { season = candidate },
                            label = { Text(stringResource(Res.string.anilist_link_season, candidate)) },
                        )
                    }
                }
            }
            currentLink?.let { link ->
                val origin = stringResource(if (link.manual) Res.string.anilist_link_manual else Res.string.anilist_link_auto)
                Text(
                    text = stringResource(Res.string.anilist_link_current, link.title) + " ($origin)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                )
            }
            seasonImport?.parts?.forEach { part ->
                Text(
                    text = when {
                        part.status == null -> stringResource(Res.string.anilist_part_not_on_list, part.title)
                        part.total != null -> stringResource(Res.string.anilist_part_progress, part.title, part.watched, part.total)
                        else -> stringResource(Res.string.anilist_part_progress_unknown, part.title, part.watched)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
            if (!isMovie) {
                OutlinedButton(
                    onClick = {
                        AniListTracker.syncTitle(meta)
                        scope.launch { NuvioToastController.show(getString(Res.string.anilist_sync_now_started, meta.name)) }
                    },
                ) {
                    Text(stringResource(Res.string.anilist_sync_now), color = colors.primary)
                }
            }
            NuvioInputField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(Res.string.anilist_link_search_hint),
            )
            if (currentLink != null) {
                TextButton(
                    onClick = {
                        AniListTracker.unlink(key)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(Res.string.anilist_link_remove), color = colors.error)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                val list = results
                when {
                    list == null -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        color = colors.primary,
                    )
                    list.isEmpty() -> Text(
                        text = stringResource(
                            if (failed) Res.string.anilist_link_search_failed else Res.string.anilist_link_no_results,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = AniListMedia::id) { media ->
                            AniListSearchResult(
                                media = media,
                                selected = currentLink?.parts?.any { it.mediaId == media.id } == true,
                                onClick = {
                                    AniListTracker.link(key, media)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AniListSearchResult(media: AniListMedia, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media.posterUrl(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = media.displayTitle(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.primary else tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    if (media.isMovie) stringResource(Res.string.anilist_link_movie) else media.format,
                    media.releaseYear()?.toString(),
                    media.episodes?.takeIf { !media.isMovie && it > 0 }?.let { stringResource(Res.string.anilist_link_episodes, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}
