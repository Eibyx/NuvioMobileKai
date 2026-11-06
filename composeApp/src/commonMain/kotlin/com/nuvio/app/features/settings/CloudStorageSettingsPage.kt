package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cloudstorage.CloudAccount
import com.nuvio.app.features.cloudstorage.CloudFailure
import com.nuvio.app.features.cloudstorage.CloudProviderKind
import com.nuvio.app.features.cloudstorage.CloudStorageException
import com.nuvio.app.features.cloudstorage.CloudStorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The account the account page shows, chosen on the list page. */
internal object CloudStorageSelection {
    var accountId: String? = null
}

internal fun LazyListScope.cloudStorageSettingsContent(
    isTablet: Boolean,
    onAccountClick: () -> Unit,
) {
    item {
        val uiState by remember {
            CloudStorageRepository.ensureLoaded()
            CloudStorageRepository.uiState
        }.collectAsStateWithLifecycle()
        var sheet by remember { mutableStateOf<CloudConnectSheet?>(null) }
        fun open(account: CloudAccount) {
            CloudStorageSelection.accountId = account.id
            onAccountClick()
        }

        SettingsSection(title = stringResource(Res.string.cloud_storage_section_accounts), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                if (uiState.accounts.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.cloud_storage_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                uiState.accounts.forEachIndexed { index, account ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = account.name,
                        description = account.summary(),
                        icon = Icons.Rounded.Cloud,
                        isTablet = isTablet,
                        onClick = { open(account) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.cloud_storage_section_add), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = CloudProviderKind.GOOGLE_DRIVE.displayName,
                    description = stringResource(Res.string.cloud_storage_add_drive_description),
                    icon = Icons.Rounded.Add,
                    isTablet = isTablet,
                    onClick = { sheet = CloudConnectSheet.Drive() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = CloudProviderKind.TERABOX.displayName,
                    description = stringResource(Res.string.cloud_storage_add_terabox_description),
                    icon = Icons.Rounded.Add,
                    isTablet = isTablet,
                    onClick = { sheet = CloudConnectSheet.TeraBox() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.cloud_storage_import_rclone),
                    description = stringResource(Res.string.cloud_storage_import_rclone_description),
                    icon = Icons.Rounded.Upload,
                    isTablet = isTablet,
                    onClick = { sheet = CloudConnectSheet.Import },
                )
            }
        }
        Text(
            text = stringResource(Res.string.cloud_storage_secrets_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        sheet?.let { current ->
            CloudConnectSheetHost(
                sheet = current,
                onSheetChange = { sheet = it },
                onConnected = { account ->
                    sheet = null
                    open(account)
                },
                onDismiss = { sheet = null },
            )
        }
    }
}

internal fun LazyListScope.cloudStorageAccountSettingsContent(
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    item {
        val uiState by remember {
            CloudStorageRepository.ensureLoaded()
            CloudStorageRepository.uiState
        }.collectAsStateWithLifecycle()
        val account = uiState.accounts.firstOrNull { it.id == CloudStorageSelection.accountId }
        if (account == null) {
            Text(
                text = stringResource(Res.string.cloud_storage_error_not_found),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            return@item
        }
        val scope = rememberCoroutineScope()
        var browsing by remember { mutableStateOf(false) }
        val syncing = account.id in uiState.syncing

        fun run(action: suspend () -> String?) {
            scope.launch {
                try {
                    action()?.let { NuvioToastController.show(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: CloudStorageException) {
                    NuvioToastController.show(getString(error.failure.messageRes()))
                } catch (_: Exception) {
                    NuvioToastController.show(getString(Res.string.cloud_storage_error_failed))
                }
            }
        }

        SettingsSection(title = account.kind.displayName, isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.cloud_storage_account_enabled),
                    checked = account.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled -> CloudStorageRepository.update(account.id) { it.copy(enabled = enabled) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.cloud_storage_account_show_first),
                    description = stringResource(Res.string.cloud_storage_account_show_first_description),
                    checked = account.showFirst,
                    isTablet = isTablet,
                    onCheckedChange = { first -> CloudStorageRepository.update(account.id) { it.copy(showFirst = first) } },
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.cloud_storage_section_folders), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                if (account.folders.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.cloud_storage_no_folders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                account.folders.forEach { folder ->
                    SettingsNavigationRow(
                        title = folder.path.ifBlank { "/" },
                        description = stringResource(Res.string.cloud_storage_remove_folder),
                        icon = Icons.Rounded.Folder,
                        isTablet = isTablet,
                        onClick = { CloudStorageRepository.setFolders(account.id, account.folders - folder) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.cloud_storage_add_folder),
                    description = null,
                    icon = Icons.Rounded.Add,
                    isTablet = isTablet,
                    onClick = { browsing = true },
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.cloud_storage_section_library), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(if (syncing) Res.string.cloud_storage_syncing else Res.string.cloud_storage_sync),
                    description = account.syncText(),
                    icon = Icons.Rounded.CloudSync,
                    enabled = !syncing && account.folders.isNotEmpty(),
                    isTablet = isTablet,
                    onClick = { run { CloudStorageRepository.sync(account.id); null } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.cloud_storage_check),
                    description = account.lastError?.let { stringResource(CloudFailure.valueOf(it).messageRes()) },
                    icon = Icons.Rounded.WifiTethering,
                    isTablet = isTablet,
                    onClick = {
                        run {
                            val label = CloudStorageRepository.verify(account.id)
                            listOfNotNull(getString(Res.string.cloud_storage_check_ok), label).joinToString(" · ")
                        }
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.cloud_storage_disconnect),
                    description = stringResource(Res.string.cloud_storage_disconnect_description),
                    icon = Icons.Rounded.LinkOff,
                    isTablet = isTablet,
                    onClick = {
                        run {
                            CloudStorageRepository.disconnect(account.id)
                            onBack()
                            null
                        }
                    },
                )
            }
        }

        if (browsing) {
            CloudFolderBrowserSheet(account = account, onDismiss = { browsing = false })
        }
    }
}

@Composable
private fun CloudAccount.summary(): String =
    listOfNotNull(kind.displayName, syncText()).joinToString(" · ")

@Composable
private fun CloudAccount.syncText(): String {
    val at = lastSyncEpochMs ?: return stringResource(Res.string.cloud_storage_never_synced)
    val time = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault())
    val label = "${time.day.toString().padStart(2, '0')}/${time.month.ordinal.plus(1).toString().padStart(2, '0')} " +
        "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    return stringResource(Res.string.cloud_storage_last_sync, indexedFiles, label)
}

internal fun CloudFailure.messageRes() = when (this) {
    CloudFailure.AUTH -> Res.string.cloud_storage_error_auth
    CloudFailure.NOT_FOUND -> Res.string.cloud_storage_error_not_found
    CloudFailure.RATE_LIMITED -> Res.string.cloud_storage_error_rate_limited
    CloudFailure.NETWORK -> Res.string.cloud_storage_error_network
    CloudFailure.UNSUPPORTED -> Res.string.cloud_storage_error_unsupported
    CloudFailure.FAILED -> Res.string.cloud_storage_error_failed
}
