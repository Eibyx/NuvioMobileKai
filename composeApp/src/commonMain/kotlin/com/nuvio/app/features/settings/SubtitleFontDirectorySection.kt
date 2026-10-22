package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.FontDirectoryProgress
import com.nuvio.app.features.player.FontDirectoryState
import com.nuvio.app.features.player.SubtitleFontDirectoryRepository
import com.nuvio.app.features.player.formatByteSize
import com.nuvio.app.features.player.rememberFontDirectoryPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_font_directory
import nuvio.composeapp.generated.resources.settings_font_directory_clear
import nuvio.composeapp.generated.resources.settings_font_directory_clear_description
import nuvio.composeapp.generated.resources.settings_font_directory_copying
import nuvio.composeapp.generated.resources.settings_font_directory_none
import nuvio.composeapp.generated.resources.settings_font_directory_result
import nuvio.composeapp.generated.resources.settings_font_directory_scanning
import nuvio.composeapp.generated.resources.settings_font_directory_summary
import nuvio.composeapp.generated.resources.settings_font_directory_reload
import nuvio.composeapp.generated.resources.settings_font_directory_reload_description
import nuvio.composeapp.generated.resources.settings_playback_section_fonts
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Subtitles > Fonts: a folder of fonts (searched at any depth) that styled
 * subtitles may use. Embedded fonts come first, then these, then the default subtitle font.
 */
@Composable
internal fun SubtitleFontDirectorySection(isTablet: Boolean) {
    if (!SubtitleFontDirectoryRepository.isSupported) return
    val state by SubtitleFontDirectoryRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { SubtitleFontDirectoryRepository.refresh() } }
    val pickFolder = rememberFontDirectoryPicker {
        scope.launch { SubtitleFontDirectoryRepository.reload() }
    }
    SubtitleFontDirectoryContent(
        state = state,
        isTablet = isTablet,
        onPickFolder = pickFolder,
        onReload = { scope.launch { SubtitleFontDirectoryRepository.reload() } },
        onClear = { scope.launch { withContext(Dispatchers.Default) { SubtitleFontDirectoryRepository.clear() } } },
    )
}

@Composable
internal fun SubtitleFontDirectoryContent(
    state: FontDirectoryState,
    isTablet: Boolean,
    onPickFolder: () -> Unit,
    onReload: () -> Unit,
    onClear: () -> Unit,
) {
    val busy = state.progress != null
    SettingsSection(
        title = stringResource(Res.string.settings_playback_section_fonts),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_font_directory),
                description = fontDirectoryDescription(state),
                enabled = !busy,
                isTablet = isTablet,
                onClick = onPickFolder,
            )
            state.progress?.let { progress ->
                FontDirectoryProgressRow(progress)
            }
            if (state.folder != null) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_font_directory_reload),
                    description = stringResource(Res.string.settings_font_directory_reload_description),
                    enabled = !busy,
                    isTablet = isTablet,
                    onClick = onReload,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_font_directory_clear),
                    description = stringResource(Res.string.settings_font_directory_clear_description),
                    enabled = !busy,
                    isTablet = isTablet,
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun fontDirectoryDescription(state: FontDirectoryState): String {
    val folder = state.folder ?: return stringResource(Res.string.settings_font_directory_none)
    val summary = pluralStringResource(
        Res.plurals.settings_font_directory_summary,
        state.fontCount,
        state.fontCount,
        formatByteSize(state.totalBytes),
    )
    val result = state.lastResult?.let { last ->
        "\n" + stringResource(Res.string.settings_font_directory_result, last.copied, last.skipped)
    }.orEmpty()
    return "$folder\n$summary$result"
}

@Composable
internal fun FontDirectoryProgressRow(progress: FontDirectoryProgress) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val copying = progress.processed > 0 || (progress.found > 0 && progress.processed == progress.found)
        Text(
            text = if (copying) {
                stringResource(
                    Res.string.settings_font_directory_copying,
                    progress.processed,
                    progress.found,
                    formatByteSize(progress.copiedBytes),
                )
            } else {
                stringResource(Res.string.settings_font_directory_scanning, progress.found)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        if (copying && progress.found > 0) {
            LinearProgressIndicator(
                progress = { progress.processed.toFloat() / progress.found },
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        }
    }
}
