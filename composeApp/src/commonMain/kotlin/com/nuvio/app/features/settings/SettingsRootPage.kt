package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.discord.DiscordRpcPlatform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.AppLoadingProfileAvatar
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.profiles.ProfileRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
    onSwitchProfileClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)? = null,
    category: SettingsCategory? = null,
) {
    // Fork: profile card at the top replaces upstream's "Switch profile" row.
    if (onSwitchProfileClick != null && (category == null || category == SettingsCategory.You)) {
        item(key = "profile_card") {
            SettingsProfileCard(isTablet = isTablet, onClick = onSwitchProfileClick)
        }
    }
    SettingsCategory.entries.filter { category == null || it == category }.forEach { group ->
        item(key = group.name) {
            // On the phone every group is listed, under its title; the tablet's sidebar names it already.
            Column {
                if (category == null) {
                    SettingsGroupTitle(text = stringResource(group.labelRes), isTablet = isTablet)
                }
                SettingsGroup(isTablet = isTablet) {
                    when (group) {
                        SettingsCategory.You -> {
                            SettingsPageRow(SettingsPage.Account, Res.string.compose_settings_root_account_description, isTablet, onPageClick)
                        }
                        SettingsCategory.Look -> {
                            SettingsPageRow(SettingsPage.Appearance, Res.string.settings_appearance_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.Layout, Res.string.compose_settings_root_layout_description, isTablet, onPageClick)
                        }
                        SettingsCategory.Watch -> {
                            SettingsPageRow(SettingsPage.Playback, Res.string.settings_playback_subtitle, isTablet, onPageClick)
                        }
                        SettingsCategory.Sources -> {
                            SettingsPageRow(
                                page = SettingsPage.Addons,
                                description = if (AppFeaturePolicy.personalMediaAddonCopyEnabled) {
                                    Res.string.settings_content_discovery_addons_description_appstore
                                } else {
                                    Res.string.settings_content_discovery_addons_description
                                },
                                isTablet = isTablet,
                                onPageClick = onPageClick,
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.Debrid, Res.string.settings_integrations_debrid_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.CloudStorage, Res.string.cloud_storage_entry_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.MediaServers, Res.string.settings_content_discovery_media_servers_description, isTablet, onPageClick)
                            if (AppFeaturePolicy.pluginsEnabled) {
                                SettingsGroupDivider(isTablet = isTablet)
                                SettingsPageRow(SettingsPage.Plugins, Res.string.settings_content_discovery_plugins_description, isTablet, onPageClick)
                            }
                        }
                        SettingsCategory.Services -> {
                            SettingsPageRow(
                                page = SettingsPage.TmdbEnrichment,
                                description = Res.string.settings_integrations_tmdb_description,
                                isTablet = isTablet,
                                onPageClick = onPageClick,
                                iconPainter = integrationLogoPainter(IntegrationLogo.Tmdb),
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(
                                page = SettingsPage.MdbListRatings,
                                description = Res.string.settings_integrations_mdblist_description,
                                isTablet = isTablet,
                                onPageClick = onPageClick,
                                iconPainter = integrationLogoPainter(IntegrationLogo.MdbList),
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.Omdb, Res.string.settings_integrations_omdb_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.TraktAuthentication, Res.string.compose_settings_root_tracking_description, isTablet, onPageClick)
                        }
                        SettingsCategory.Extras -> {
                            SettingsPageRow(SettingsPage.LiveTv, Res.string.settings_integrations_live_tv_description, isTablet, onPageClick)
                            if (DiscordRpcPlatform.isSupported) {
                                SettingsGroupDivider(isTablet = isTablet)
                                SettingsPageRow(SettingsPage.DiscordRpc, Res.string.settings_integrations_discord_description, isTablet, onPageClick)
                            }
                            if (onWatchPartyClick != null) {
                                SettingsGroupDivider(isTablet = isTablet)
                                SettingsNavigationRow(
                                    title = stringResource(Res.string.party_title),
                                    description = stringResource(Res.string.party_settings_description),
                                    icon = Icons.Rounded.Groups,
                                    isTablet = isTablet,
                                    onClick = onWatchPartyClick,
                                )
                            }
                        }
                        SettingsCategory.System -> {
                            SettingsPageRow(SettingsPage.Notifications, Res.string.compose_settings_root_notifications_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.Advanced, Res.string.compose_settings_root_advanced_description, isTablet, onPageClick)
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsPageRow(SettingsPage.About, Res.string.compose_settings_root_about_description, isTablet, onPageClick)
                        }
                    }
                }
            }
        }
    }
    settingsFooterContent(isTablet = isTablet)
}

@Composable
private fun SettingsPageRow(
    page: SettingsPage,
    description: StringResource,
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
    iconPainter: Painter? = null,
) {
    if (iconPainter != null) {
        SettingsNavigationRow(
            title = stringResource(page.titleRes),
            description = stringResource(description),
            iconPainter = iconPainter,
            isTablet = isTablet,
            onClick = { onPageClick(page) },
        )
    } else {
        SettingsNavigationRow(
            title = stringResource(page.titleRes),
            description = stringResource(description),
            icon = page.icon,
            isTablet = isTablet,
            onClick = { onPageClick(page) },
        )
    }
}

/** The name of a group of settings, above it on the phone's settings page. */
@Composable
private fun SettingsGroupTitle(text: String, isTablet: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = if (isTablet) 20.dp else 16.dp,
            top = if (isTablet) 18.dp else 14.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun SettingsProfileCard(
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val avatarSize = if (isTablet) 84.dp else 72.dp
    SettingsGroup(
        isTablet = isTablet,
        modifier = Modifier.padding(top = if (isTablet) 12.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .border(2.5.dp, MaterialTheme.themePalette.accentBrush(), CircleShape)
                    .padding(4.dp),
            ) {
                if (profile != null) {
                    AppLoadingProfileAvatar(profile = profile, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        imageVector = Icons.Rounded.People,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.compose_settings_root_profile_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.compose_settings_root_profile_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
