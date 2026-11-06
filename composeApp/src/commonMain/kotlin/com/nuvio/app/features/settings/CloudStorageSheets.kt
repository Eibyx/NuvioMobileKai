package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cloudstorage.CloudAccount
import com.nuvio.app.features.cloudstorage.CloudEntry
import com.nuvio.app.features.cloudstorage.CloudFailure
import com.nuvio.app.features.cloudstorage.CloudFolderRef
import com.nuvio.app.features.cloudstorage.CloudManualLink
import com.nuvio.app.features.cloudstorage.CloudOAuthLoopback
import com.nuvio.app.features.cloudstorage.CloudProviderKind
import com.nuvio.app.features.cloudstorage.CloudStorageException
import com.nuvio.app.features.cloudstorage.CloudStorageRepository
import com.nuvio.app.features.cloudstorage.GoogleDriveOAuth
import com.nuvio.app.features.cloudstorage.RcloneConfigException
import com.nuvio.app.features.cloudstorage.RcloneConfigImport
import com.nuvio.app.features.cloudstorage.RcloneRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal sealed class CloudConnectSheet {
    data class TeraBox(val name: String = "", val cookie: String = "", val domain: String = "") : CloudConnectSheet()
    data class Drive(
        val name: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val refreshToken: String = "",
    ) : CloudConnectSheet()
    data object Import : CloudConnectSheet()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudConnectSheetHost(
    sheet: CloudConnectSheet,
    onSheetChange: (CloudConnectSheet) -> Unit,
    onConnected: (CloudAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        is CloudConnectSheet.TeraBox -> TeraBoxConnectSheet(sheet, onConnected, onDismiss)
        is CloudConnectSheet.Drive -> DriveConnectSheet(sheet, onConnected, onDismiss)
        CloudConnectSheet.Import -> RcloneImportSheet(onSheetChange, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeraBoxConnectSheet(
    initial: CloudConnectSheet.TeraBox,
    onConnected: (CloudAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial.name) }
    var cookie by remember { mutableStateOf(initial.cookie) }
    var domain by remember { mutableStateOf(initial.domain) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CloudFailure?>(null) }

    fun connect() {
        if (connecting || cookie.isBlank()) return
        connecting = true
        error = null
        scope.launch {
            try {
                val account = CloudStorageRepository.addTeraBox(name, cookie, domain.takeIf { it.isNotBlank() })
                cookie = ""
                dismissNuvioBottomSheet(sheetState) { onConnected(account) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudStorageException) {
                error = failure.failure
            } catch (_: Exception) {
                error = CloudFailure.FAILED
            } finally {
                connecting = false
            }
        }
    }

    CloudSheet(
        title = stringResource(Res.string.cloud_storage_terabox_title),
        subtitle = stringResource(Res.string.cloud_storage_terabox_subtitle),
        sheetState = sheetState,
        busy = connecting,
        onDismiss = onDismiss,
    ) {
        CloudTextField(name, { name = it }, stringResource(Res.string.cloud_storage_account_name), enabled = !connecting)
        SettingsSecretTextField(
            value = cookie,
            onValueChange = { cookie = it; error = null },
            label = stringResource(Res.string.cloud_storage_terabox_cookie),
            modifier = Modifier.fillMaxWidth(),
            enabled = !connecting,
            shape = MaterialTheme.nuvio.shapes.button,
            colors = cloudFieldColors(),
        )
        CloudTextField(domain, { domain = it }, stringResource(Res.string.cloud_storage_terabox_domain), enabled = !connecting, uri = true)
        CloudNote(stringResource(Res.string.cloud_storage_terabox_warning))
        error?.let { CloudError(it.messageRes()) }
        CloudPrimaryButton(
            text = stringResource(if (connecting) Res.string.cloud_storage_connecting else Res.string.cloud_storage_connect),
            busy = connecting,
            enabled = cookie.isNotBlank() && !connecting,
            onClick = ::connect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveConnectSheet(
    initial: CloudConnectSheet.Drive,
    onConnected: (CloudAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial.name) }
    var clientId by remember { mutableStateOf(initial.clientId) }
    var clientSecret by remember { mutableStateOf(initial.clientSecret) }
    var refreshToken by remember { mutableStateOf(initial.refreshToken) }
    var busy by remember { mutableStateOf(false) }
    var waitingForBrowser by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CloudFailure?>(null) }

    fun finish(token: String) {
        scope.launch {
            try {
                val connected = CloudStorageRepository.addGoogleDrive(name, clientId, clientSecret, token)
                refreshToken = ""
                clientSecret = ""
                dismissNuvioBottomSheet(sheetState) { onConnected(connected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudStorageException) {
                error = failure.failure
            } catch (_: Exception) {
                error = CloudFailure.FAILED
            } finally {
                busy = false
                waitingForBrowser = false
            }
        }
    }

    fun signIn() {
        if (busy || clientId.isBlank()) return
        busy = true
        waitingForBrowser = true
        error = null
        scope.launch {
            try {
                val token = GoogleDriveOAuth.signIn(CloudStorageRepository.http, clientId.trim(), clientSecret.trim()) { url ->
                    uriHandler.openUri(url)
                }
                finish(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudStorageException) {
                error = failure.failure
                busy = false
                waitingForBrowser = false
            } catch (_: Exception) {
                error = CloudFailure.FAILED
                busy = false
                waitingForBrowser = false
            }
        }
    }

    CloudSheet(
        title = stringResource(Res.string.cloud_storage_drive_title),
        subtitle = stringResource(Res.string.cloud_storage_drive_subtitle),
        sheetState = sheetState,
        busy = busy,
        onDismiss = onDismiss,
    ) {
        CloudTextField(name, { name = it }, stringResource(Res.string.cloud_storage_account_name), enabled = !busy)
        CloudTextField(clientId, { clientId = it; error = null }, stringResource(Res.string.cloud_storage_drive_client_id), enabled = !busy)
        SettingsSecretTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it; error = null },
            label = stringResource(Res.string.cloud_storage_drive_client_secret),
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            shape = MaterialTheme.nuvio.shapes.button,
            colors = cloudFieldColors(),
        )
        if (CloudOAuthLoopback.supported) {
            CloudPrimaryButton(
                text = stringResource(if (waitingForBrowser) Res.string.cloud_storage_drive_waiting else Res.string.cloud_storage_drive_sign_in),
                busy = waitingForBrowser,
                enabled = clientId.isNotBlank() && !busy,
                onClick = ::signIn,
            )
        }
        SettingsSecretTextField(
            value = refreshToken,
            onValueChange = { refreshToken = it; error = null },
            label = stringResource(Res.string.cloud_storage_drive_refresh_token),
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            shape = MaterialTheme.nuvio.shapes.button,
            colors = cloudFieldColors(),
        )
        error?.let { CloudError(it.messageRes()) }
        CloudPrimaryButton(
            text = stringResource(if (busy && !waitingForBrowser) Res.string.cloud_storage_connecting else Res.string.cloud_storage_connect),
            busy = busy && !waitingForBrowser,
            enabled = clientId.isNotBlank() && refreshToken.isNotBlank() && !busy,
            onClick = {
                busy = true
                error = null
                finish(refreshToken)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RcloneImportSheet(
    onSheetChange: (CloudConnectSheet) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var remotes by remember { mutableStateOf<List<RcloneRemote>?>(null) }
    var message by remember { mutableStateOf<StringResource?>(null) }

    CloudSheet(
        title = stringResource(Res.string.cloud_storage_import_title),
        subtitle = stringResource(Res.string.cloud_storage_import_subtitle),
        sheetState = sheetState,
        busy = false,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; remotes = null; message = null },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(stringResource(Res.string.cloud_storage_import_text)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            shape = MaterialTheme.nuvio.shapes.button,
            colors = cloudFieldColors(),
            maxLines = 8,
        )
        CloudPrimaryButton(
            text = stringResource(Res.string.cloud_storage_import_read),
            busy = false,
            enabled = text.isNotBlank(),
            onClick = {
                try {
                    val parsed = RcloneConfigImport.parse(text).filterNot { it is RcloneRemote.Unsupported }
                    remotes = parsed
                    message = if (parsed.isEmpty()) Res.string.cloud_storage_import_none else null
                } catch (_: RcloneConfigException) {
                    remotes = null
                    message = Res.string.cloud_storage_import_encrypted
                }
            },
        )
        message?.let { CloudNote(stringResource(it)) }
        remotes?.forEach { remote ->
            when (remote) {
                is RcloneRemote.TeraBox -> OutlinedButton(
                    onClick = {
                        text = ""
                        onSheetChange(CloudConnectSheet.TeraBox(remote.name, remote.cookie, remote.domain.orEmpty()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${remote.name} · ${CloudProviderKind.TERABOX.displayName}") }
                is RcloneRemote.GoogleDrive -> {
                    OutlinedButton(
                        onClick = {
                            text = ""
                            onSheetChange(
                                CloudConnectSheet.Drive(
                                    name = remote.name,
                                    clientId = remote.clientId.orEmpty(),
                                    clientSecret = remote.clientSecret.orEmpty(),
                                    refreshToken = if (remote.usesOwnClient) remote.refreshToken.orEmpty() else "",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${remote.name} · ${CloudProviderKind.GOOGLE_DRIVE.displayName}") }
                    if (!remote.usesOwnClient) CloudNote(stringResource(Res.string.cloud_storage_import_shared_client))
                    else if (remote.refreshToken == null) CloudNote(stringResource(Res.string.cloud_storage_import_no_token))
                }
                is RcloneRemote.Unsupported -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudFolderBrowserSheet(account: CloudAccount, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stack by remember { mutableStateOf(listOf<CloudFolderRef>()) }
    var entries by remember { mutableStateOf<List<CloudEntry>?>(null) }
    var failure by remember { mutableStateOf<CloudFailure?>(null) }
    var folderIdInput by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf<CloudEntry?>(null) }
    val current = stack.lastOrNull()

    LaunchedEffect(current) {
        entries = null
        failure = null
        try {
            entries = CloudStorageRepository.browse(account.id, current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CloudStorageException) {
            failure = error.failure
        } catch (_: Exception) {
            failure = CloudFailure.FAILED
        }
    }

    fun choose(folder: CloudFolderRef) {
        CloudStorageRepository.setFolders(account.id, account.folders + folder)
        onDismiss()
    }

    CloudSheet(
        title = stringResource(Res.string.cloud_storage_browse_title),
        subtitle = current?.path ?: "/",
        sheetState = sheetState,
        busy = false,
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            if (stack.isNotEmpty()) {
                OutlinedButton(onClick = { stack = stack.dropLast(1) }) { Text(stringResource(Res.string.cloud_storage_browse_up)) }
            }
            Button(
                onClick = {
                    choose(current ?: CloudFolderRef(id = if (account.kind == CloudProviderKind.GOOGLE_DRIVE) "root" else "/", path = "/"))
                },
            ) { Text(stringResource(Res.string.cloud_storage_browse_choose)) }
        }
        if (account.kind == CloudProviderKind.GOOGLE_DRIVE) {
            CloudTextField(folderIdInput, { folderIdInput = it }, stringResource(Res.string.cloud_storage_browse_drive_id), enabled = true, uri = true)
            OutlinedButton(
                onClick = {
                    driveFolderId(folderIdInput)?.let { id -> choose(CloudFolderRef(id = id, path = "/$id")) }
                },
                enabled = driveFolderId(folderIdInput) != null,
            ) { Text(stringResource(Res.string.cloud_storage_browse_add_id)) }
        }
        failure?.let { CloudError(it.messageRes()) }
        val list = entries
        if (list == null && failure == null) {
            NuvioLoadingIndicator(modifier = Modifier.size(MaterialTheme.nuvio.icons.md))
        } else if (list != null && list.isEmpty()) {
            CloudNote(stringResource(Res.string.cloud_storage_browse_empty))
        }
        list?.forEach { entry ->
            val linked = account.manualLinks.firstOrNull { it.fileId == entry.id }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.isFolder) stack = stack + CloudFolderRef(entry.id, entry.path) else linking = entry
                    }
                    .padding(vertical = NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Icon(if (entry.isFolder) Icons.Rounded.Folder else Icons.Rounded.Movie, contentDescription = null)
                Text(
                    text = if (linked != null) "${entry.name}  ✓ ${linked.contentId}" else entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    linking?.let { entry ->
        CloudManualLinkSheet(account = account, entry = entry, onDismiss = { linking = null })
    }
}

/** Accepts a bare Drive folder id or a `drive.google.com/drive/folders/<id>` link. */
private fun driveFolderId(value: String): String? {
    val trimmed = value.trim()
    val id = Regex("""folders/([A-Za-z0-9_-]{10,})""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    return id.takeIf { Regex("""^[A-Za-z0-9_-]{10,}$""").matches(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudManualLinkSheet(account: CloudAccount, entry: CloudEntry, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = account.manualLinks.firstOrNull { it.fileId == entry.id }
    var contentId by remember { mutableStateOf(existing?.contentId.orEmpty()) }
    var season by remember { mutableStateOf(existing?.season?.toString().orEmpty()) }
    var episode by remember { mutableStateOf(existing?.episode?.toString().orEmpty()) }
    val valid = Regex("""^(tt\d+|(tmdb|anilist|mal|kitsu|anidb|tvdb):\d+)$""").matches(contentId.trim())

    CloudSheet(
        title = stringResource(Res.string.cloud_storage_manual_link_title),
        subtitle = entry.name,
        sheetState = sheetState,
        busy = false,
        onDismiss = onDismiss,
    ) {
        CloudNote(stringResource(Res.string.cloud_storage_manual_link_subtitle))
        CloudTextField(contentId, { contentId = it }, stringResource(Res.string.cloud_storage_manual_link_content), enabled = true)
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            CloudTextField(season, { season = it.filter(Char::isDigit).take(3) }, stringResource(Res.string.cloud_storage_manual_link_season), enabled = true, number = true, modifier = Modifier.weight(1f))
            CloudTextField(episode, { episode = it.filter(Char::isDigit).take(4) }, stringResource(Res.string.cloud_storage_manual_link_episode), enabled = true, number = true, modifier = Modifier.weight(1f))
        }
        CloudPrimaryButton(
            text = stringResource(Res.string.cloud_storage_manual_link_save),
            busy = false,
            enabled = valid,
            onClick = {
                CloudStorageRepository.setManualLink(
                    account.id,
                    CloudManualLink(entry.id, contentId.trim(), season.toIntOrNull(), episode.toIntOrNull()),
                )
                onDismiss()
            },
        )
        if (existing != null) {
            OutlinedButton(
                onClick = {
                    CloudStorageRepository.removeManualLink(account.id, entry.id)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.cloud_storage_manual_link_remove)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSheet(
    title: String,
    subtitle: String,
    sheetState: androidx.compose.material3.SheetState,
    busy: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = tokens.spacing.sheetPadding, end = tokens.spacing.sheetPadding, bottom = tokens.spacing.sheetPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
            content()
        }
    }
}

@Composable
private fun CloudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    uri: Boolean = false,
    number: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = when {
                number -> KeyboardType.Number
                uri -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
        ),
        shape = MaterialTheme.nuvio.shapes.button,
        colors = cloudFieldColors(),
    )
}

@Composable
private fun CloudPrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(NuvioTokens.Space.s56),
        enabled = enabled,
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), verticalAlignment = Alignment.CenterVertically) {
            if (busy) NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm), color = tokens.colors.onAccent)
            Text(text)
        }
    }
}

@Composable
private fun CloudNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
private fun CloudError(message: StringResource) {
    Text(text = stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.danger)
}

@Composable
private fun cloudFieldColors(): TextFieldColors {
    val colors = MaterialTheme.nuvio.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.borderFocus,
        unfocusedBorderColor = colors.borderDefault,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
    )
}
