package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sd
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cloudstorage.CloudAccount
import com.nuvio.app.features.cloudstorage.CloudProviderKind
import com.nuvio.app.features.cloudstorage.CloudStorageRepository
import com.nuvio.app.features.player.formatByteSize
import com.nuvio.app.features.subtitlelibrary.LibraryVolume
import com.nuvio.app.features.subtitlelibrary.SubtitleFolder
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryPlatform
import com.nuvio.app.features.subtitlelibrary.SubtitleLibrarySettings
import com.nuvio.app.features.subtitlelibrary.SubtitleLibraryStore
import com.nuvio.app.features.subtitlelibrary.SubtitleOrigin
import com.nuvio.app.features.subtitlelibrary.rememberSubtitleFolderPicker
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_cloud_storage
import nuvio.composeapp.generated.resources.subtitle_folders_add
import nuvio.composeapp.generated.resources.subtitle_folders_add_drive
import nuvio.composeapp.generated.resources.subtitle_folders_add_storage
import nuvio.composeapp.generated.resources.subtitle_folders_add_storage_description
import nuvio.composeapp.generated.resources.subtitle_folders_add_terabox
import nuvio.composeapp.generated.resources.subtitle_folders_auto_select
import nuvio.composeapp.generated.resources.subtitle_folders_auto_select_description
import nuvio.composeapp.generated.resources.subtitle_folders_cache
import nuvio.composeapp.generated.resources.subtitle_folders_cache_value
import nuvio.composeapp.generated.resources.subtitle_folders_choose_volume
import nuvio.composeapp.generated.resources.subtitle_folders_clear_cache
import nuvio.composeapp.generated.resources.subtitle_folders_clear_cache_description
import nuvio.composeapp.generated.resources.subtitle_folders_connect_account
import nuvio.composeapp.generated.resources.subtitle_folders_description
import nuvio.composeapp.generated.resources.subtitle_folders_enabled
import nuvio.composeapp.generated.resources.subtitle_folders_none
import nuvio.composeapp.generated.resources.subtitle_folders_origin_drive
import nuvio.composeapp.generated.resources.subtitle_folders_origin_storage
import nuvio.composeapp.generated.resources.subtitle_folders_origin_terabox
import nuvio.composeapp.generated.resources.subtitle_folders_refresh
import nuvio.composeapp.generated.resources.subtitle_folders_refresh_done
import nuvio.composeapp.generated.resources.subtitle_folders_remove
import nuvio.composeapp.generated.resources.subtitle_folders_title
import nuvio.composeapp.generated.resources.subtitle_folders_volume_other
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Subtitles > My subtitle folders: the user's own folders of ASS subtitles
 * and their fonts (TeraBox, Google Drive, phone storage / SD / USB). Android only.
 */
@Composable
internal fun SubtitleFoldersSection(isTablet: Boolean) {
    if (!SubtitleLibraryPlatform.isSupported) return
    val settings by SubtitleLibraryStore.settings.collectAsState()
    val cloudState by CloudStorageRepository.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var showAdd by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<CloudAccount?>(null) }
    LaunchedEffect(Unit) {
        CloudStorageRepository.ensureLoaded()
        cacheBytes = withContext(Dispatchers.Default) { SubtitleLibraryPlatform.cacheBytes() }
    }
    val picker = rememberSubtitleFolderPicker { folder -> SubtitleLibraryStore.addFolder(folder) }

    SettingsSection(title = stringResource(Res.string.subtitle_folders_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Text(
                text = stringResource(if (settings.folders.isEmpty()) Res.string.subtitle_folders_none else Res.string.subtitle_folders_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
            )
            settings.folders.forEach { folder ->
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = folder.displayName,
                    description = "${originLabel(folder)} · ${stringResource(Res.string.subtitle_folders_enabled)}",
                    checked = folder.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { SubtitleLibraryStore.setFolderEnabled(folder.id, it) },
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.subtitle_folders_refresh),
                    description = null,
                    isTablet = isTablet,
                    onClick = {
                        SubtitleLibraryPlatform.invalidate(folder.id)
                        scope.launch { NuvioToastController.show(getString(Res.string.subtitle_folders_refresh_done, folder.displayName)) }
                    },
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.subtitle_folders_remove),
                    description = null,
                    isTablet = isTablet,
                    onClick = {
                        SubtitleLibraryPlatform.release(folder)
                        SubtitleLibraryStore.removeFolder(folder.id)
                    },
                )
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.subtitle_folders_add),
                description = null,
                icon = Icons.Rounded.CreateNewFolder,
                isTablet = isTablet,
                onClick = { showAdd = true },
            )
        }
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.subtitle_folders_auto_select),
                description = stringResource(Res.string.subtitle_folders_auto_select_description),
                checked = settings.autoSelect,
                isTablet = isTablet,
                onCheckedChange = SubtitleLibraryStore::setAutoSelect,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.subtitle_folders_cache),
                description = stringResource(Res.string.subtitle_folders_cache_value, settings.cacheLimitMb, formatByteSize(cacheBytes)),
                isTablet = isTablet,
                onClick = {
                    val options = SubtitleLibrarySettings.CACHE_LIMIT_OPTIONS_MB
                    val next = options.getOrNull(options.indexOf(settings.cacheLimitMb) + 1) ?: options.first()
                    SubtitleLibraryStore.setCacheLimitMb(next)
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.subtitle_folders_clear_cache),
                description = stringResource(Res.string.subtitle_folders_clear_cache_description),
                isTablet = isTablet,
                onClick = {
                    scope.launch {
                        cacheBytes = withContext(Dispatchers.Default) {
                            SubtitleLibraryPlatform.clearCache()
                            SubtitleLibraryPlatform.cacheBytes()
                        }
                    }
                },
            )
        }
    }

    if (showAdd) {
        AddSubtitleFolderSheet(
            accounts = cloudState.accounts,
            volumes = remember(showAdd) { picker.volumes() },
            onCloud = { account ->
                showAdd = false
                browsing = account
            },
            onVolume = { volume ->
                showAdd = false
                picker.pickVolume(volume)
            },
            onOtherFolder = {
                showAdd = false
                picker.pickFolder()
            },
            onDismiss = { showAdd = false },
        )
    }
    browsing?.let { account ->
        CloudFolderBrowserSheet(
            account = account,
            onDismiss = { browsing = null },
            onChoose = { ref ->
                val origin = if (account.kind == CloudProviderKind.GOOGLE_DRIVE) SubtitleOrigin.DRIVE else SubtitleOrigin.TERABOX
                SubtitleLibraryStore.addFolder(
                    SubtitleFolder(
                        id = "${origin.id}-" + Random.nextLong().toULong().toString(16),
                        origin = origin.id,
                        displayName = ref.path.trimEnd('/').substringAfterLast('/').ifBlank { account.name },
                        accountId = account.id,
                        folderId = ref.id,
                        folderPath = ref.path,
                    ),
                )
            },
        )
    }
}

@Composable
private fun originLabel(folder: SubtitleFolder): String = stringResource(
    when (folder.originKind) {
        SubtitleOrigin.TERABOX -> Res.string.subtitle_folders_origin_terabox
        SubtitleOrigin.DRIVE -> Res.string.subtitle_folders_origin_drive
        else -> Res.string.subtitle_folders_origin_storage
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSubtitleFolderSheet(
    accounts: List<CloudAccount>,
    volumes: List<LibraryVolume>,
    onCloud: (CloudAccount) -> Unit,
    onVolume: (LibraryVolume) -> Unit,
    onOtherFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tokens = MaterialTheme.nuvio
    val cloudPage = stringResource(Res.string.compose_settings_page_cloud_storage)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.subtitle_folders_add),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            listOf(
                CloudProviderKind.TERABOX to Res.string.subtitle_folders_add_terabox,
                CloudProviderKind.GOOGLE_DRIVE to Res.string.subtitle_folders_add_drive,
            ).forEach { (kind, label) ->
                val connected = accounts.filter { it.kind == kind }
                if (connected.isEmpty()) {
                    AddRow(Icons.Rounded.Cloud, stringResource(label), stringResource(Res.string.subtitle_folders_connect_account, cloudPage), enabled = false) {}
                } else {
                    connected.forEach { account ->
                        AddRow(Icons.Rounded.Cloud, stringResource(label), account.name, enabled = true) { onCloud(account) }
                    }
                }
            }
            Text(
                text = stringResource(Res.string.subtitle_folders_add_storage),
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (volumes.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.subtitle_folders_choose_volume),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                volumes.forEach { volume ->
                    AddRow(
                        icon = if (!volume.removable) Icons.Rounded.PhoneAndroid else if (volume.description.contains("USB", ignoreCase = true)) Icons.Rounded.Usb else Icons.Rounded.Sd,
                        title = volume.description,
                        description = null,
                        enabled = true,
                    ) { onVolume(volume) }
                }
            }
            AddRow(
                icon = Icons.Rounded.CreateNewFolder,
                title = stringResource(Res.string.subtitle_folders_volume_other),
                description = stringResource(Res.string.subtitle_folders_add_storage_description),
                enabled = true,
                onClick = onOtherFolder,
            )
        }
    }
}

@Composable
private fun AddRow(icon: ImageVector, title: String, description: String?, enabled: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tokens.colors.accent else tokens.colors.textMuted)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
        }
    }
}
