package com.nuvio.app.features.artwork

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the logo, background or poster a title shows, from TMDB and (with a key) Fanart.tv.
 * The choice is kept on the device for that title; "Restore" goes back to the addon's artwork.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtworkPickerSheet(
    id: String,
    type: String,
    name: String,
    imdbId: String?,
    tvdbId: String?,
    language: String?,
    initialKind: ArtworkKind = ArtworkKind.LOGO,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(initialKind) }
    var candidates by remember { mutableStateOf<ArtworkCandidates?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var keyText by remember { mutableStateOf(ArtworkChoices.fanartKey.orEmpty()) }
    val choice = rememberArtworkChoice(id)
    LaunchedEffect(id, reload) {
        candidates = null
        failed = false
        try {
            candidates = ArtworkCandidateLoader.load(id, type, imdbId, tvdbId, language?.substringBefore('-'))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
    }
    val close: () -> Unit = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) } }
    NuvioModalBottomSheet(onDismissRequest = close, sheetState = sheetState, fullHeight = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = nuvioSafeBottomPadding(12.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.artwork_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ArtworkKind.LOGO to Res.string.artwork_kind_logo,
                    ArtworkKind.BACKGROUND to Res.string.artwork_kind_background,
                    ArtworkKind.POSTER to Res.string.artwork_kind_poster,
                ).forEach { (value, label) ->
                    FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(stringResource(label)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chosen = when (kind) {
                    ArtworkKind.LOGO -> choice?.logo
                    ArtworkKind.BACKGROUND -> choice?.background
                    ArtworkKind.POSTER -> choice?.poster
                }
                Text(
                    stringResource(if (chosen != null) Res.string.artwork_custom else Res.string.artwork_from_addon),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (chosen != null) {
                    TextButton(onClick = { ArtworkChoices.set(id, kind, null) }) { Text(stringResource(Res.string.artwork_restore)) }
                }
                if (choice != null && !choice.isEmpty) {
                    TextButton(onClick = { ArtworkChoices.reset(id) }) { Text(stringResource(Res.string.artwork_restore_all)) }
                }
            }
            val loaded = candidates
            when {
                failed -> Text(stringResource(Res.string.artwork_load_failed), color = MaterialTheme.colorScheme.error)
                loaded == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.artwork_loading))
                }
                else -> {
                    when (loaded.fanartIssue) {
                        "empty" -> Res.string.artwork_fanart_empty
                        "no-id" -> Res.string.artwork_fanart_no_id
                        else -> null
                    }?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (ArtworkChoices.fanartKey == null || loaded.fanartFailed) {
                        Text(
                            stringResource(
                                when {
                                    loaded.fanartIssue == "key" -> Res.string.artwork_fanart_failed
                                    loaded.fanartIssue == "network" -> Res.string.artwork_fanart_network
                                    else -> Res.string.artwork_fanart_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = keyText,
                                onValueChange = { keyText = it },
                                singleLine = true,
                                label = { Text(stringResource(Res.string.artwork_fanart_key)) },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                ArtworkChoices.fanartKey = keyText
                                ArtworkCandidateLoader.clearCache()
                                reload++
                            }) { Text(stringResource(Res.string.artwork_fanart_save)) }
                        }
                    }
                    val list = loaded.of(kind)
                    if (list.isEmpty()) {
                        Text(stringResource(Res.string.artwork_none))
                    } else {
                        val selected = when (kind) {
                            ArtworkKind.LOGO -> choice?.logo
                            ArtworkKind.BACKGROUND -> choice?.background
                            ArtworkKind.POSTER -> choice?.poster
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (kind == ArtworkKind.POSTER) 3 else 2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().height(520.dp),
                        ) {
                            items(list, key = { it.url }) { candidate ->
                                CandidateTile(candidate, kind, candidate.url == selected) {
                                    ArtworkChoices.set(id, kind, candidate.url)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateTile(candidate: ArtworkCandidate, kind: ArtworkKind, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    when (kind) {
                        ArtworkKind.LOGO -> 2.4f
                        ArtworkKind.BACKGROUND -> 16f / 9f
                        ArtworkKind.POSTER -> 2f / 3f
                    },
                )
                .clip(RoundedCornerShape(6.dp))
                .background(if (kind == ArtworkKind.LOGO) Color(0xFF3A3A3A) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = candidate.previewUrl,
                contentDescription = null,
                contentScale = if (kind == ArtworkKind.LOGO) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().padding(if (kind == ArtworkKind.LOGO) 6.dp else 0.dp),
            )
        }
        Text(
            listOfNotNull(candidate.source, candidate.language?.uppercase(), candidate.detail).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
