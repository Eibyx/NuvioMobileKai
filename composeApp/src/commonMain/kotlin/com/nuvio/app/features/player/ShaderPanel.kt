package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.isDarkAppearance
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.shaders.DebandLevel
import com.nuvio.app.features.player.shaders.ImportedShader
import com.nuvio.app.features.player.shaders.ShaderCatalog
import com.nuvio.app.features.player.shaders.ShaderCategory
import com.nuvio.app.features.player.shaders.ShaderChainDefinition
import com.nuvio.app.features.player.shaders.ShaderController
import com.nuvio.app.features.player.shaders.ShaderInactiveReason
import com.nuvio.app.features.player.shaders.ShaderPassReport
import com.nuvio.app.features.player.shaders.ShaderPassStatus
import com.nuvio.app.features.player.shaders.ShaderPlaybackSession
import com.nuvio.app.features.player.shaders.ShaderPresets
import com.nuvio.app.features.player.shaders.ShaderQuality
import com.nuvio.app.features.player.shaders.ShaderSettingsRepository
import com.nuvio.app.features.player.shaders.ShaderSuspension
import com.nuvio.app.features.player.shaders.debandLabel
import com.nuvio.app.features.player.shaders.shaderPanelColors
import com.nuvio.app.features.player.shaders.shaderPanelTint
import com.nuvio.app.features.player.shaders.shaderPresetDescription
import com.nuvio.app.features.player.shaders.shaderPresetLabel
import com.nuvio.app.features.player.shaders.shaderQualityLabel
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.random.Random

private enum class ShaderPanelTab { Presets, Combine, Status }

/**
 * The player's shader panel: master switch and "Apply always", then presets, a free combination
 * of every shader in order, and what each shader of the chain does right now.
 */
@Composable
internal fun ShaderPanel(visible: Boolean, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val dark = MaterialTheme.isDarkAppearance
    val tint = remember(tokens.colors, dark) { shaderPanelTint(tokens.colors, dark) }
    val panelTokens = remember(tokens, dark) { tokens.copy(colors = shaderPanelColors(tokens.colors, dark)) }
    PlayerSidePanel(visible = visible, onDismiss = onDismiss, width = 460.dp, tint = tint) {
        CompositionLocalProvider(LocalNuvioThemeTokens provides panelTokens) {
            ShaderPanelContent()
        }
    }
}

@Composable
internal fun ShaderPanelContent() {
    val tokens = MaterialTheme.nuvio
    val settings by ShaderSettingsRepository.settings.collectAsState()
    val inputs by ShaderController.inputs.collectAsState()
    val plan by ShaderController.plan.collectAsState()
    val state by ShaderPlaybackSession.state.collectAsState()
    var tab by remember { mutableStateOf(ShaderPanelTab.Presets) }
    val enabled = inputs.enabledOverride ?: settings.enabled
    val applyAlways = inputs.applyAlwaysOverride ?: settings.applyAlways

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PlayerPanelHeader(
                title = stringResource(Res.string.shader_panel_title),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                HoldToCompareButton(enabled = plan?.active == true || inputs.comparing)
            }
        }
        if (!state.mpvActive) {
            item { PanelNotice(stringResource(Res.string.shader_panel_mpv_only), tokens.colors.warning) }
        }
        item {
            PanelSwitch(
                title = stringResource(Res.string.settings_shaders_enabled),
                checked = enabled,
                onChange = { on ->
                    ShaderSettingsRepository.setEnabled(on)
                    ShaderController.updateInputs { it.copy(enabledOverride = null, suspension = it.suspension.takeIf { s -> s == ShaderSuspension.BatterySaver }) }
                },
            )
        }
        item {
            PanelSwitch(
                title = stringResource(Res.string.shader_panel_apply_always),
                description = stringResource(Res.string.settings_shaders_apply_always_description),
                checked = applyAlways && !inputs.applyAlwaysSuspended,
                enabled = enabled,
                onChange = { on ->
                    ShaderSettingsRepository.setApplyAlways(on)
                    ShaderController.updateInputs { it.copy(applyAlwaysOverride = null, applyAlwaysSuspended = false) }
                },
            )
        }
        when (inputs.suspension) {
            ShaderSuspension.BatterySaver -> item { PanelNotice(stringResource(Res.string.shader_warning_battery_saver), tokens.colors.warning) }
            ShaderSuspension.Performance -> item { PanelNotice(stringResource(Res.string.shader_warning_disabled), tokens.colors.warning) }
            null -> Unit
        }
        if (inputs.qualityCap != null && plan != null) {
            item {
                PanelNotice(
                    stringResource(Res.string.shader_panel_quality_lowered, shaderQualityLabel(plan!!.quality)),
                    tokens.colors.textSecondary,
                )
            }
        }
        item { ShaderSummary(state.statuses, state.frameCostMs, state.backend?.let { "${it.gpuApi} · ${it.vo} · hwdec ${it.hwdecCurrent}" }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShaderPanelTab.entries.forEach { entry ->
                    PanelChip(
                        label = stringResource(
                            when (entry) {
                                ShaderPanelTab.Presets -> Res.string.shader_panel_tab_presets
                                ShaderPanelTab.Combine -> Res.string.shader_panel_tab_combine
                                ShaderPanelTab.Status -> Res.string.shader_panel_tab_status
                            },
                        ),
                        selected = tab == entry,
                        onClick = { tab = entry },
                    )
                }
            }
        }
        when (tab) {
            ShaderPanelTab.Presets -> presetsTab(
                selected = inputs.presetOverride ?: ShaderController.AUTOMATIC,
                live = inputs.liveChain != null,
                automaticResolved = plan?.presetId,
                chains = settings.chains,
                quality = settings.quality,
                enabled = enabled,
            )
            ShaderPanelTab.Combine -> combineTab(
                ids = inputs.liveChain ?: plan?.entries?.map { it.shaderId }.orEmpty(),
                deband = settings.deband,
                enabled = enabled,
                imported = settings.imported,
            )
            ShaderPanelTab.Status -> statusTab(state.statuses)
        }
        item { Box(Modifier.padding(bottom = 24.dp)) }
    }
}

private fun LazyListScope.presetsTab(
    selected: String,
    live: Boolean,
    automaticResolved: String?,
    chains: List<ShaderChainDefinition>,
    quality: ShaderQuality,
    enabled: Boolean,
) {
    item { SectionLabel(Res.string.settings_shaders_quality) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShaderQuality.entries.forEach { level ->
                PanelChip(
                    label = shaderQualityLabel(level),
                    selected = level == quality,
                    enabled = enabled,
                    onClick = { ShaderSettingsRepository.setQuality(level) },
                )
            }
        }
    }
    item {
        val resolved = automaticResolved?.takeIf { selected == ShaderController.AUTOMATIC && !live }
        PresetRow(
            id = ShaderController.AUTOMATIC,
            label = shaderPresetLabel(ShaderController.AUTOMATIC, chains),
            description = resolved?.let { shaderPresetLabel(it, chains) },
            selected = selected == ShaderController.AUTOMATIC && !live,
            enabled = enabled,
        )
    }
    item { SectionLabel(Res.string.shader_panel_group_anime) }
    items(ShaderPresets.anime, key = { "anime-$it" }) { id ->
        PresetRow(id, shaderPresetLabel(id, chains), shaderPresetDescription(id), selected == id && !live, enabled)
    }
    item { SectionLabel(Res.string.shader_panel_group_general) }
    items(ShaderPresets.general, key = { "general-$it" }) { id ->
        PresetRow(id, shaderPresetLabel(id, chains), shaderPresetDescription(id), selected == id && !live, enabled)
    }
    if (chains.isNotEmpty()) {
        item { SectionLabel(Res.string.shader_panel_group_own) }
        items(chains, key = { "chain-${it.id}" }) { chain ->
            val id = ShaderPresets.CHAIN_PREFIX + chain.id
            PresetRow(id, chain.name, chain.shaderIds.size.toString() + " shaders", selected == id && !live, enabled)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.combineTab(ids: List<String>, deband: DebandLevel, enabled: Boolean, imported: List<ImportedShader>) {
    val categories = listOf(
        ShaderCategory.Anime to Res.string.shader_category_anime,
        ShaderCategory.GeneralScaling to Res.string.shader_category_general,
        ShaderCategory.Sharpening to Res.string.shader_category_sharpening,
        ShaderCategory.Color to Res.string.shader_category_color,
        ShaderCategory.Downscaling to Res.string.shader_category_downscaling,
    )
    val toggle = { id: String, checked: Boolean ->
        val next = if (checked) {
            // Keep the user's order; the new shader goes in at its stage.
            val sorted = ShaderPresets.defaultOrder(ids + id, ShaderController.traitsOf)
            val before = sorted.take(sorted.indexOf(id)).lastOrNull { it in ids }
            ids.toMutableList().apply { add(if (before == null) 0 else indexOf(before) + 1, id) }
        } else {
            ids - id
        }
        ShaderController.setLiveChain(next)
    }
    categories.forEach { (category, label) ->
        item(key = "category-$category") { SectionLabel(label) }
        items(ShaderCatalog.shaders.filter { it.category == category }, key = { "shader-${it.id}" }) { shader ->
            CheckRow(title = shader.title, checked = shader.id in ids, enabled = enabled, onChange = { toggle(shader.id, it) })
        }
    }
    if (imported.isNotEmpty()) {
        item(key = "category-imported") { SectionLabel(Res.string.shader_category_imported) }
        items(imported, key = { "shader-${it.id}" }) { shader ->
            CheckRow(title = shader.title, checked = shader.id in ids, enabled = enabled, onChange = { toggle(shader.id, it) })
        }
    }
    item(key = "category-deband") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(Res.string.shader_category_deband)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DebandLevel.entries.forEach { level ->
                    PanelChip(debandLabel(level), level == deband, enabled = enabled, onClick = { ShaderSettingsRepository.setDeband(level) })
                }
            }
        }
    }
    item(key = "order") { ChainOrder(ids, enabled) }
}

@Composable
private fun ChainOrder(ids: List<String>, enabled: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(Res.string.shader_panel_order)
        if (ids.isEmpty()) {
            Text(stringResource(Res.string.shader_panel_order_empty), color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (ShaderPresets.doublesTwice(ids, ShaderController.traitsOf)) {
            PanelNotice(stringResource(Res.string.shader_panel_doubles_twice), tokens.colors.warning)
        }
        // The reorderable list keeps its own small LazyColumn so drag positions map to the chain.
        val listState = rememberLazyListState()
        val reorder = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
            val next = ids.toMutableList()
            next.add(to.index, next.removeAt(from.index))
            ShaderController.setLiveChain(next)
        }
        LazyColumn(
            state = listState,
            // Fixed height: every row shows, the outer list does the scrolling.
            modifier = Modifier.fillMaxWidth().height((ids.size * 50).dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            userScrollEnabled = false,
        ) {
            items(ids, key = { it }) { id ->
                ReorderableItem(reorder, key = id) { dragging ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dragging) tokens.colors.overlaySelected else tokens.colors.surfaceCard)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${ids.indexOf(id) + 1}. ${ShaderController.titleOf(id)}",
                            modifier = Modifier.weight(1f),
                            color = tokens.colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val handleLabel = stringResource(Res.string.shader_panel_drag)
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = handleLabel,
                            tint = tokens.colors.textSecondary,
                            modifier = (if (enabled) Modifier.draggableHandle() else Modifier).size(24.dp),
                        )
                    }
                }
            }
        }
        SaveChainRow(ids)
    }
}

@Composable
private fun SaveChainRow(ids: List<String>) {
    val tokens = MaterialTheme.nuvio
    var name by remember { mutableStateOf("") }
    val save = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && ids.isNotEmpty()) {
            val chain = ShaderChainDefinition(id = "c" + Random.nextLong(1L shl 40).toString(36), name = trimmed, shaderIds = ids)
            ShaderSettingsRepository.update { it.copy(chains = it.chains + chain) }
            ShaderController.pickPreset(ShaderPresets.CHAIN_PREFIX + chain.id)
            name = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(Res.string.shader_panel_chain_name)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = tokens.colors.textPrimary,
                unfocusedTextColor = tokens.colors.textPrimary,
                focusedLabelColor = tokens.colors.textSecondary,
                unfocusedLabelColor = tokens.colors.textSecondary,
                focusedBorderColor = tokens.colors.accent,
                unfocusedBorderColor = tokens.colors.borderStrong,
                cursorColor = tokens.colors.accent,
            ),
        )
        PlayerDialogButton(label = stringResource(Res.string.shader_panel_save_preset), onClick = save, enabled = name.isNotBlank() && ids.isNotEmpty())
    }
}

private fun LazyListScope.statusTab(statuses: List<ShaderPassStatus>) {
    if (statuses.isEmpty()) {
        item {
            Text(
                text = stringResource(Res.string.shader_status_none),
                color = MaterialTheme.nuvio.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    items(statuses, key = { "status-${it.shaderId}" }) { status -> StatusRow(status) }
}

@Composable
private fun StatusRow(status: ShaderPassStatus) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status.title, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = if (status.active) stringResource(Res.string.shader_status_active) else inactiveReasonText(status.inactiveReason),
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.active) {
            Text(ShaderPassReport.formatMs(status.costMs), color = tokens.colors.textPrimary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun inactiveReasonText(reason: ShaderInactiveReason?): String = stringResource(
    when (reason) {
        ShaderInactiveReason.VideoMatchesScreen -> Res.string.shader_status_matches_screen
        ShaderInactiveReason.VideoLargerThanScreen -> Res.string.shader_status_larger_than_screen
        ShaderInactiveReason.NoSubsampledChroma -> Res.string.shader_status_no_chroma
        ShaderInactiveReason.Failed -> Res.string.shader_status_failed
        ShaderInactiveReason.ConditionNotMet, null -> Res.string.shader_status_condition
    },
)

@Composable
private fun ShaderSummary(statuses: List<ShaderPassStatus>, frameCostMs: Double, backend: String?) {
    val tokens = MaterialTheme.nuvio
    if (statuses.isEmpty() && backend == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                Res.string.shader_panel_summary,
                statuses.count { it.active },
                statuses.size,
                ShaderPassReport.formatMs(frameCostMs),
            ),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (backend != null) {
            Text(backend, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Shows the picture without shaders while pressed; works paused too (mpv redraws the frame). */
@Composable
internal fun HoldToCompareButton(enabled: Boolean, modifier: Modifier = Modifier, tint: Color = MaterialTheme.nuvio.colors.textPrimary) {
    val label = stringResource(Res.string.shader_compare_hold)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        ShaderController.setComparing(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            ShaderController.setComparing(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Compare, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
private fun PresetRow(id: String, label: String, description: String?, selected: Boolean, enabled: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.22f) else tokens.colors.surfaceCard)
            .border(1.dp, if (selected) tokens.colors.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.RadioButton) { ShaderController.pickPreset(id) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (description != null) Text(description, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = tokens.colors.accent,
                checkmarkColor = tokens.colors.onAccent,
                uncheckedColor = tokens.colors.textSecondary,
            ),
        )
        Text(title, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PanelSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (enabled) tokens.colors.textPrimary else tokens.colors.textSecondary, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textSecondary,
                uncheckedTrackColor = tokens.colors.borderDefault,
            ),
        )
    }
}

@Composable
private fun PanelChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surfaceCard)
            .border(1.dp, if (selected) tokens.colors.accent else tokens.colors.borderDefault, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PanelNotice(text: String, iconColor: Color) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Text(text, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionLabel(label: StringResource) {
    Text(
        text = stringResource(label),
        color = MaterialTheme.nuvio.colors.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
}
