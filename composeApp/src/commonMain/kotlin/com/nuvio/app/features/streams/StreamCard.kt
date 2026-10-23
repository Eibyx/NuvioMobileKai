package com.nuvio.app.features.streams

import com.nuvio.app.core.ui.contentOnSurface
import com.nuvio.app.core.ui.surfaceOverlay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.nuvio.app.core.ui.isDarkAppearance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.features.debrid.DebridProviders
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.glass.LocalGlassOverArtwork
import com.nuvio.app.core.ui.glass.ReadableGlass
import com.nuvio.app.core.ui.glass.ambientLiquidGlass
import com.nuvio.app.core.ui.glass.readableGlass
import com.nuvio.app.core.ui.glass.streamGlassTexts
import com.nuvio.app.core.ui.glass.isAmbientLiquidGlassActive
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow

internal val LocalStreamHighlight = staticCompositionLocalOf<(StreamItem) -> String?> { { null } }

internal data class SharedStream(
    val label: String,
    val stream: StreamItem,
)

internal val LocalSharedStream = staticCompositionLocalOf<SharedStream?> { null }

@Composable
internal fun StreamCard(
    stream: StreamItem,
    enabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    currentLabel: String? = null,
    outlined: Boolean = false,
) {
    val partyLabel = if (isCurrent) null else LocalStreamHighlight.current(stream)
    val cardShape = RoundedCornerShape(12.dp)
    val badgeImages = stream.badges.filter { it.imageURL.isNotBlank() }
    val hasBadges = badgeImages.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints.videoSize != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.04f),
            )
            .clip(cardShape)
            .ambientLiquidGlass(
                shape = cardShape,
                tint = streamGlassTint(
                    highlight = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else null,
                ),
                fallback = streamGlassFallback(
                    plain = if (isCurrent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.surfaceOverlay(0.05f)
                    },
                    highlight = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else null,
                ),
                blurRadius = 18.dp,
            )
            .then(
                if (isCurrent || outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            StreamNameWithInstantService(
                stream = stream,
                appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
            ) {
                if (isCurrent && !currentLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CurrentStreamBadge(label = currentLabel)
                }
                if (partyLabel != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = partyLabel,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val subtitle = stream.streamSubtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        shadow = streamGlassTextShadow(),
                    ),
                    color = MaterialTheme.contentOnSurface,
                )
            }

            if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                Spacer(modifier = Modifier.height(5.dp))
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
            }
        }

        if (showAddonLogo) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!stream.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.addonLogo,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, shadow = streamGlassTextShadow()),
                    color = MaterialTheme.contentOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StreamCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.surfaceOverlay(0.05f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f), height = 14.dp, cornerRadius = 4.dp)
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f), height = 10.dp, cornerRadius = 4.dp)
    }
}

@Composable
private fun StreamCardBadgeRow(
    badgeImages: List<StreamBadge>,
    stream: StreamItem,
    showFileSizeBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeImages.forEach { badge ->
            StreamBadgeImage(badge = badge)
        }
        if (showFileSizeBadges) {
            StreamFileSizeBadge(stream = stream)
        }
    }
}

@Composable
private fun StreamNameWithInstantService(
    stream: StreamItem,
    appendInstantServiceToDefaultName: Boolean,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        shadow = streamGlassTextShadow(),
    )
    val instantLabel = if (appendInstantServiceToDefaultName) {
        stream.instantServiceLabel()
    } else {
        null
    }
    val showInstantLabel = instantLabel != null
    val visibleState = remember(stream.streamLabel) {
        MutableTransitionState(showInstantLabel)
    }
    visibleState.targetState = showInstantLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stream.streamLabel,
            modifier = Modifier.weight(1f, fill = false),
            style = nameStyle,
            color = MaterialTheme.contentOnSurface,
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 120),
                    shrinkTowards = Alignment.Start,
                ),
            label = "streamNameInstantService",
        ) {
            Text(
                text = " ${instantLabel.orEmpty()}",
                style = nameStyle,
                color = MaterialTheme.contentOnSurface,
            )
        }
        trailingContent()
    }
}

@Composable
private fun CurrentStreamBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal const val StreamGlassTintAlpha = 0.55f

private val StreamGlassTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.6f),
    offset = Offset(0f, 1f),
    blurRadius = 4f,
)

/**
 * The stream cards' and filters' glass over the Cinematic artwork, with the alpha that keeps their
 * text readable on any picture (see [readableGlass]). [highlight] marks the current stream.
 */
@Composable
internal fun streamGlassTint(highlight: Color? = null): Color {
    val glass = streamCardGlass()
    return highlight?.compositeOver(glass.tint) ?: glass.tint
}

/** The same glass without blur; [plain] where no artwork is behind (the Normal background). */
@Composable
internal fun streamGlassFallback(plain: Color, highlight: Color? = null): Color {
    if (!LocalGlassOverArtwork.current) return plain
    val glass = streamCardGlass()
    return highlight?.compositeOver(glass.fallback) ?: glass.fallback
}

@Composable
internal fun streamCardGlass(): ReadableGlass {
    val colors = MaterialTheme.colorScheme
    val content = MaterialTheme.contentOnSurface
    val texts = remember(colors.onSurface, content) { streamGlassTexts(colors, content) }
    val plain = if (MaterialTheme.isDarkAppearance) {
        Color.White.copy(alpha = 0.05f).compositeOver(Color.Black.copy(alpha = StreamGlassTintAlpha))
    } else {
        colors.surfaceVariant.copy(alpha = 0.45f)
    }
    return readableGlass(texts, plainFill = plain)
}

/** A shadow under light text on dark glass; dark text on light glass needs none. */
@Composable
internal fun streamGlassTextShadow(): Shadow? =
    if (isAmbientLiquidGlassActive() && MaterialTheme.isDarkAppearance) StreamGlassTextShadow else null

private fun StreamItem.instantServiceLabel(): String? {
    val status = debridCacheStatus ?: return null
    if (status.state != StreamDebridCacheState.CACHED) return null
    val providerLabel = DebridProviders.shortName(status.providerId)
        .ifBlank { status.providerName.trim() }
        .ifBlank { DebridProviders.displayName(status.providerId) }
    return "- $providerLabel Instant"
}
