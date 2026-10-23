package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.shaders.ShaderLibrary
import com.nuvio.app.features.player.shaders.ShaderSettings
import com.nuvio.app.features.player.shaders.rememberShaderLibraryActions
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Settings > Playback > Shaders: imported shaders and own chains, with export and import. */
@Composable
internal fun ShaderLibrarySection(settings: ShaderSettings, isTablet: Boolean) {
    val actions = rememberShaderLibraryActions { message -> NuvioToastController.show(message, 4_000L) } ?: return
    val tokens = MaterialTheme.nuvio
    Text(
        text = stringResource(Res.string.settings_shaders_library),
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.textMuted,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    SettingsGroup(isTablet = isTablet) {
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_shaders_import_files),
            description = stringResource(Res.string.settings_shaders_import_files_description),
            isTablet = isTablet,
            onClick = actions.importFiles,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_shaders_import_folder),
            description = stringResource(Res.string.settings_shaders_import_folder_description),
            isTablet = isTablet,
            onClick = actions.importFolder,
        )
        settings.imported.forEach { shader ->
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = shader.title,
                description = stringResource(
                    if (shader.upscaler) Res.string.settings_shaders_imported_upscaler else Res.string.settings_shaders_imported_other,
                ),
                isTablet = isTablet,
                trailingContent = {
                    IconButton(onClick = { ShaderLibrary.remove(shader.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.settings_shaders_remove, shader.title), tint = tokens.colors.textSecondary)
                    }
                },
                onClick = {},
            )
        }
    }
    Text(
        text = stringResource(Res.string.settings_shaders_chains),
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.textMuted,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    SettingsGroup(isTablet = isTablet) {
        if (settings.chains.isEmpty()) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_chains_empty),
                description = stringResource(Res.string.settings_shaders_chains_empty_description),
                enabled = false,
                isTablet = isTablet,
                onClick = {},
            )
        }
        settings.chains.forEach { chain ->
            SettingsNavigationRow(
                title = chain.name,
                description = chain.shaderIds.joinToString(" → ") { ShaderLibrary.title(it) },
                isTablet = isTablet,
                trailingContent = {
                    IconButton(onClick = { ShaderLibrary.deleteChain(chain.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.settings_shaders_remove, chain.name), tint = tokens.colors.textSecondary)
                    }
                },
                onClick = {},
            )
            SettingsGroupDivider(isTablet = isTablet)
        }
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_shaders_chains_export),
            description = pluralStringResource(Res.plurals.settings_shaders_chains_count, settings.chains.size, settings.chains.size),
            enabled = settings.chains.isNotEmpty(),
            isTablet = isTablet,
            onClick = actions.exportChains,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_shaders_chains_import),
            description = stringResource(Res.string.settings_shaders_chains_import_description),
            isTablet = isTablet,
            onClick = actions.importChains,
        )
    }
}
