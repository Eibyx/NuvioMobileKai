package com.nuvio.app.features.player

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.subtitlelibrary.LibraryUnavailable
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryPlatform
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryStore
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_cloud_storage
import nuvio.composeapp.generated.resources.subtitle_folders_account_disconnected
import nuvio.composeapp.generated.resources.subtitle_folders_empty_folder
import nuvio.composeapp.generated.resources.subtitle_folders_link
import nuvio.composeapp.generated.resources.subtitle_folders_link_this
import nuvio.composeapp.generated.resources.subtitle_folders_needs_link
import nuvio.composeapp.generated.resources.subtitle_folders_read_failed
import nuvio.composeapp.generated.resources.subtitle_folders_search
import nuvio.composeapp.generated.resources.subtitle_folders_searching
import nuvio.composeapp.generated.resources.subtitle_folders_storage_unavailable
import nuvio.composeapp.generated.resources.subtitle_folders_title
import nuvio.composeapp.generated.resources.subtitle_folders_up
import nuvio.composeapp.generated.resources.subtitle_folders_usb_disconnected
import org.jetbrains.compose.resources.stringResource

/** What the subtitle menu shows about the user's subtitle folders. */
data class SubtitleLibraryMenu(
    val searching: Boolean,
    /** "USB no conectado: X", "Almacenamiento no disponible: X"… */
    val notices: List<String>,
    val showLink: Boolean,
    val onSearch: () -> Unit,
    val onLink: () -> Unit,
)

/** Null when the feature is not offered or no folder is enabled: the menu stays as it was. */
@Composable
internal fun PlayerScreenRuntime.rememberSubtitleLibraryMenu(): SubtitleLibraryMenu? {
    if (!SubtitleLibraryPlatform.isSupported || isLiveTvPlayback) return null
    val settings by SubtitleLibraryStore.settings.collectAsState()
    if (settings.enabledFolders.isEmpty()) return null
    val result = libraryResult?.takeIf { librarySubtitlesVideoId == activeVideoId }
    val cloudPage = stringResource(Res.string.compose_settings_page_cloud_storage)
    val notices = result?.unavailable.orEmpty().map { (folder, reason) ->
        when (reason) {
            LibraryUnavailable.USB_DISCONNECTED -> if (folder.volume != null) {
                stringResource(Res.string.subtitle_folders_usb_disconnected, folder.displayName)
            } else {
                stringResource(Res.string.subtitle_folders_storage_unavailable, folder.displayName)
            }
            LibraryUnavailable.PERMISSION_MISSING -> stringResource(Res.string.subtitle_folders_storage_unavailable, folder.displayName)
            LibraryUnavailable.ACCOUNT_DISCONNECTED -> stringResource(Res.string.subtitle_folders_account_disconnected, folder.displayName, cloudPage)
            LibraryUnavailable.FAILED -> stringResource(Res.string.subtitle_folders_read_failed, folder.displayName)
        }
    } + result?.needsLink.orEmpty().map { folder -> stringResource(Res.string.subtitle_folders_needs_link, folder.displayName) }
    return SubtitleLibraryMenu(
        searching = librarySearching,
        notices = notices,
        showLink = result != null && (result.needsLink.isNotEmpty() || result.animeFolders.isEmpty()),
        onSearch = { openSubtitleLibraryBrowser(linking = false) },
        onLink = { openSubtitleLibraryBrowser(linking = true) },
    )
}

/** The rows at the top of the subtitle menu's language rail. */
@Composable
internal fun SubtitleLibraryMenuRows(menu: SubtitleLibraryMenu) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SubtitleLibraryActionRow(Icons.Rounded.FolderOpen, stringResource(Res.string.subtitle_folders_search), menu.onSearch)
        if (menu.showLink) {
            SubtitleLibraryActionRow(Icons.Rounded.Link, stringResource(Res.string.subtitle_folders_link), menu.onLink)
        }
        if (menu.searching) {
            Text(
                text = stringResource(Res.string.subtitle_folders_searching),
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        menu.notices.forEach { notice ->
            Text(
                text = notice,
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun SubtitleLibraryActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(20.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Buscar en mis carpetas…" / "Vincular carpeta…": the user's folders, read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreenRuntime.SubtitleLibraryBrowserHost() {
    val state = libraryBrowser ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tokens = MaterialTheme.nuvio
    NuvioModalBottomSheet(onDismissRequest = { libraryBrowser = null }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(if (state.linking) Res.string.subtitle_folders_link else Res.string.subtitle_folders_search),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            val root = state.root
            Text(
                text = listOfNotNull(root?.displayName ?: stringResource(Res.string.subtitle_folders_title))
                    .plus(state.stack.map { it.name })
                    .joinToString(" / "),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.stack.isNotEmpty() || (root != null && subtitleLibraryRoots().size > 1)) {
                    OutlinedButton(onClick = { subtitleLibraryBrowserUp() }) { Text(stringResource(Res.string.subtitle_folders_up)) }
                }
                if (state.linking && state.stack.isNotEmpty()) {
                    Button(onClick = { linkSubtitleLibraryFolder() }) { Text(stringResource(Res.string.subtitle_folders_link_this)) }
                }
            }
            if (root == null) {
                subtitleLibraryRoots().forEach { folder ->
                    BrowserRow(Icons.Rounded.Folder, folder.displayName) { openSubtitleLibraryRoot(folder) }
                }
                return@Column
            }
            val entries = state.entries
            when {
                state.busy || (entries == null && !state.failed) -> NuvioLoadingIndicator(modifier = Modifier.size(28.dp))
                state.failed -> Text(stringResource(Res.string.subtitle_folders_read_failed, root.displayName), color = tokens.colors.textMuted)
                entries.isNullOrEmpty() -> Text(stringResource(Res.string.subtitle_folders_empty_folder), color = tokens.colors.textMuted)
                else -> entries.forEach { entry ->
                    BrowserRow(if (entry.isFolder) Icons.Rounded.Folder else Icons.Rounded.Subtitles, entry.name) {
                        openSubtitleLibraryEntry(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
