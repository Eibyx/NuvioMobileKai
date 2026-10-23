package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.shaders.ColorExtra
import com.nuvio.app.features.player.shaders.DebandLevel
import com.nuvio.app.features.player.shaders.FinalScaler
import com.nuvio.app.features.player.shaders.ShaderFiles
import com.nuvio.app.features.player.shaders.ShaderMode
import com.nuvio.app.features.player.shaders.ShaderPresets
import com.nuvio.app.features.player.shaders.ShaderQuality
import com.nuvio.app.features.player.shaders.ShaderSettings
import com.nuvio.app.features.player.shaders.ShaderSettingsRepository
import com.nuvio.app.features.player.shaders.SharpenExtra
import com.nuvio.app.features.player.shaders.colorExtraLabel
import com.nuvio.app.features.player.shaders.debandLabel
import com.nuvio.app.features.player.shaders.finalScalerDescription
import com.nuvio.app.features.player.shaders.finalScalerLabel
import com.nuvio.app.features.player.shaders.shaderPresetDescription
import com.nuvio.app.features.player.shaders.shaderPresetLabel
import com.nuvio.app.features.player.shaders.shaderQualityDescription
import com.nuvio.app.features.player.shaders.shaderQualityLabel
import com.nuvio.app.features.player.shaders.sharpenExtraLabel
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class ShaderDialog { Mode, AnimePreset, GeneralPreset, ManualPreset, Quality, Sharpen, Color, Deband, Scaler }

/** Settings > Playback > Shaders (mpv only). Android only: the iOS player has no user shaders. */
@Composable
internal fun ShaderSettingsSection(isTablet: Boolean) {
    if (!ShaderFiles.isSupported) return
    LaunchedEffect(Unit) { ShaderSettingsRepository.ensureLoaded() }
    val settings by ShaderSettingsRepository.settings.collectAsState()
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    ShaderSettingsContent(
        settings = settings,
        mpvEngine = playerSettings.androidPlaybackEngine == AndroidPlaybackEngine.Libmpv,
        isTablet = isTablet,
    )
}

@Composable
internal fun ShaderSettingsContent(settings: ShaderSettings, mpvEngine: Boolean, isTablet: Boolean) {
    var dialog by remember { mutableStateOf<ShaderDialog?>(null) }
    val enabled = settings.enabled
    SettingsSection(
        title = stringResource(Res.string.settings_shaders_section),
        isTablet = isTablet,
    ) {
        if (!mpvEngine) {
            Text(
                text = stringResource(Res.string.settings_shaders_mpv_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_shaders_enabled),
                description = stringResource(Res.string.settings_shaders_enabled_description),
                checked = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = ShaderSettingsRepository::setEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_shaders_apply_always),
                description = stringResource(Res.string.settings_shaders_apply_always_description),
                checked = settings.applyAlways,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = ShaderSettingsRepository::setApplyAlways,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_mode),
                description = modeLabel(settings.mode),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Mode },
            )
            if (settings.mode == ShaderMode.Automatic) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_shaders_anime_preset),
                    description = shaderPresetLabel(settings.animePreset, settings.chains),
                    enabled = enabled,
                    isTablet = isTablet,
                    onClick = { dialog = ShaderDialog.AnimePreset },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_shaders_general_preset),
                    description = shaderPresetLabel(settings.generalPreset, settings.chains),
                    enabled = enabled,
                    isTablet = isTablet,
                    onClick = { dialog = ShaderDialog.GeneralPreset },
                )
            } else {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_shaders_preset),
                    description = shaderPresetLabel(settings.manualPreset, settings.chains),
                    enabled = enabled,
                    isTablet = isTablet,
                    onClick = { dialog = ShaderDialog.ManualPreset },
                )
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_quality),
                description = shaderQualityLabel(settings.quality),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Quality },
            )
        }
        Text(
            text = stringResource(Res.string.settings_shaders_extras),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_sharpen),
                description = sharpenExtraLabel(settings.sharpen),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Sharpen },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_color),
                description = colorExtraLabel(settings.color),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Color },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_deband),
                description = debandLabel(settings.deband),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Deband },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_shaders_scaler),
                description = finalScalerLabel(settings.finalScaler),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = ShaderDialog.Scaler },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_shaders_remember_series),
                description = stringResource(Res.string.settings_shaders_remember_series_description),
                checked = settings.rememberPerSeries,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = ShaderSettingsRepository::setRememberPerSeries,
            )
        }
    }

    val close = { dialog = null }
    when (dialog) {
        ShaderDialog.Mode -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_mode),
            options = ShaderMode.entries,
            selected = settings.mode,
            label = { modeLabel(it) },
            description = { if (it == ShaderMode.Automatic) stringResource(Res.string.settings_shaders_mode_automatic_description) else null },
            onSelect = { ShaderSettingsRepository.setMode(it); close() },
            onDismiss = close,
        )
        ShaderDialog.AnimePreset -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_anime_preset),
            options = ShaderPresets.anime + ShaderPresets.general + ownChains(settings),
            selected = settings.animePreset,
            label = { shaderPresetLabel(it, settings.chains) },
            description = { shaderPresetDescription(it) },
            onSelect = { ShaderSettingsRepository.setAnimePreset(it); close() },
            onDismiss = close,
        )
        ShaderDialog.GeneralPreset -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_general_preset),
            options = ShaderPresets.general + ShaderPresets.anime + ownChains(settings),
            selected = settings.generalPreset,
            label = { shaderPresetLabel(it, settings.chains) },
            description = { shaderPresetDescription(it) },
            onSelect = { ShaderSettingsRepository.setGeneralPreset(it); close() },
            onDismiss = close,
        )
        ShaderDialog.ManualPreset -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_preset),
            options = ShaderPresets.anime + ShaderPresets.general + ownChains(settings),
            selected = settings.manualPreset,
            label = { shaderPresetLabel(it, settings.chains) },
            description = { shaderPresetDescription(it) },
            onSelect = { ShaderSettingsRepository.setManualPreset(it); close() },
            onDismiss = close,
        )
        ShaderDialog.Quality -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_quality),
            options = ShaderQuality.entries,
            selected = settings.quality,
            label = { shaderQualityLabel(it) },
            description = { shaderQualityDescription(it) },
            onSelect = { ShaderSettingsRepository.setQuality(it); close() },
            onDismiss = close,
        )
        ShaderDialog.Sharpen -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_sharpen),
            options = SharpenExtra.entries,
            selected = settings.sharpen,
            label = { sharpenExtraLabel(it) },
            onSelect = { ShaderSettingsRepository.setSharpen(it); close() },
            onDismiss = close,
        )
        ShaderDialog.Color -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_color),
            options = ColorExtra.entries,
            selected = settings.color,
            label = { colorExtraLabel(it) },
            description = { if (it == ColorExtra.None) null else stringResource(Res.string.settings_shaders_color_description) },
            onSelect = { ShaderSettingsRepository.setColor(it); close() },
            onDismiss = close,
        )
        ShaderDialog.Deband -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_deband),
            options = DebandLevel.entries,
            selected = settings.deband,
            label = { debandLabel(it) },
            description = { if (it == DebandLevel.Off) null else stringResource(Res.string.settings_shaders_deband_description) },
            onSelect = { ShaderSettingsRepository.setDeband(it); close() },
            onDismiss = close,
        )
        ShaderDialog.Scaler -> ChoiceDialog(
            title = stringResource(Res.string.settings_shaders_scaler),
            options = FinalScaler.entries,
            selected = settings.finalScaler,
            label = { finalScalerLabel(it) },
            description = { finalScalerDescription(it) },
            onSelect = { ShaderSettingsRepository.setFinalScaler(it); close() },
            onDismiss = close,
        )
        null -> Unit
    }
}

private fun ownChains(settings: ShaderSettings) = settings.chains.map { ShaderPresets.CHAIN_PREFIX + it.id }

@Composable
private fun modeLabel(mode: ShaderMode): String = when (mode) {
    ShaderMode.Automatic -> stringResource(Res.string.shader_preset_automatic)
    ShaderMode.Manual -> stringResource(Res.string.settings_shaders_mode_manual)
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                DialogOption(
                    text = label(option),
                    description = description(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_done), onClick = onDismiss)
        }
    }
}
