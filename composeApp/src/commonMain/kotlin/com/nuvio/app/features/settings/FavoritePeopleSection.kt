package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.surfaceOverlay
import com.nuvio.app.features.details.FavoritePeopleRepository
import com.nuvio.app.features.details.FavoritePerson
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.favorite_people_pin
import nuvio.composeapp.generated.resources.favorite_people_title
import nuvio.composeapp.generated.resources.favorite_people_unpin
import nuvio.composeapp.generated.resources.home_view_all
import org.jetbrains.compose.resources.stringResource

/*
 * Favorite people, ported from yesnt10's Enhanced fork: a rail in Profile Insights and a full page
 * where people can be pinned to the front. Colours come from the theme so both appearances work.
 */

private const val FavoritePeopleInlineLimit = 3

/** The Profile Insights section; it is left out until someone is marked as a favorite. */
@Composable
internal fun FavoritePeopleSection(
    isTablet: Boolean,
    onPersonClick: (FavoritePerson) -> Unit,
    onViewAllClick: () -> Unit,
) {
    val state by remember {
        FavoritePeopleRepository.ensureLoaded()
        FavoritePeopleRepository.uiState
    }.collectAsStateWithLifecycle()
    val people = state.displayPeople
    if (people.isEmpty()) return

    SettingsSection(
        title = stringResource(Res.string.favorite_people_title),
        isTablet = isTablet,
        actions = {
            if (people.size > FavoritePeopleInlineLimit) {
                FavoritePeopleViewAllButton(isTablet = isTablet, onClick = onViewAllClick)
            }
        },
    ) {
        FavoritePeopleRail(
            people = people,
            isTablet = isTablet,
            onPersonClick = onPersonClick,
        )
    }
}

/** The full page: every favorite in a grid, with pin toggles. */
internal fun LazyListScope.favoritePeopleContent(
    isTablet: Boolean,
    onPersonClick: (FavoritePerson) -> Unit,
) {
    item {
        FavoritePeopleGrid(isTablet = isTablet, onPersonClick = onPersonClick)
    }
}

@Composable
private fun FavoritePeopleGrid(
    isTablet: Boolean,
    onPersonClick: (FavoritePerson) -> Unit,
) {
    val state by remember {
        FavoritePeopleRepository.ensureLoaded()
        FavoritePeopleRepository.uiState
    }.collectAsStateWithLifecycle()
    val columns = if (isTablet) 5 else 3
    val rows = remember(state.people, state.pinnedPersonIds, columns) {
        state.displayPeople.chunked(columns)
    }
    val pinnedIds = remember(state.pinnedPersonIds) { state.pinnedPersonIds.toSet() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 24.dp else 20.dp),
    ) {
        rows.forEach { rowPeople ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowPeople.forEach { person ->
                    FavoritePersonItem(
                        person = person,
                        isTablet = isTablet,
                        onClick = { onPersonClick(person) },
                        isPinned = person.tmdbId in pinnedIds,
                        onTogglePinned = { FavoritePeopleRepository.togglePinned(person.tmdbId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowPeople.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FavoritePeopleRail(
    people: List<FavoritePerson>,
    isTablet: Boolean,
    onPersonClick: (FavoritePerson) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(if (isTablet) 28.dp else 24.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surfaceElevated.copy(alpha = 0.62f),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.surfaceOverlay(0.07f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.surfaceOverlay(0.035f),
                            tokens.colors.accent.copy(alpha = 0.045f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(horizontal = if (isTablet) 20.dp else 14.dp, vertical = if (isTablet) 18.dp else 15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                people.take(FavoritePeopleInlineLimit).forEach { person ->
                    FavoritePersonItem(
                        person = person,
                        isTablet = isTablet,
                        onClick = { onPersonClick(person) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(FavoritePeopleInlineLimit - people.size.coerceAtMost(FavoritePeopleInlineLimit)) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FavoritePeopleViewAllButton(
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .size(if (isTablet) 42.dp else 38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = tokens.colors.surfaceElevated.copy(alpha = 0.88f),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.surfaceOverlay(0.08f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.home_view_all),
                modifier = Modifier.size(if (isTablet) 24.dp else 22.dp),
                tint = tokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun FavoritePersonItem(
    person: FavoritePerson,
    isTablet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onTogglePinned: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val avatarSize = if (isTablet) 82.dp else 70.dp
    val badgeSize = if (isTablet) 27.dp else 24.dp
    val badgeIconSize = if (isTablet) 15.dp else 13.dp

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 9.dp else 8.dp),
    ) {
        Box(
            modifier = Modifier.size(avatarSize + 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(tokens.colors.surfaceElevated)
                    .border(1.dp, MaterialTheme.surfaceOverlay(0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (!person.photo.isNullOrBlank()) {
                    AsyncImage(
                        model = person.photo,
                        contentDescription = person.name,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = person.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize),
                color = tokens.colors.surface.copy(alpha = 0.90f),
                shape = CircleShape,
                border = BorderStroke(1.dp, tokens.colors.accent.copy(alpha = 0.48f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(badgeIconSize),
                        tint = tokens.colors.accent,
                    )
                }
            }
            if (onTogglePinned != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePinned),
                    color = tokens.colors.surface.copy(alpha = 0.92f),
                    shape = CircleShape,
                    border = BorderStroke(
                        1.dp,
                        if (isPinned) tokens.colors.accent.copy(alpha = 0.62f) else MaterialTheme.surfaceOverlay(0.16f),
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = stringResource(
                                if (isPinned) Res.string.favorite_people_unpin else Res.string.favorite_people_pin,
                            ),
                            modifier = Modifier.size(badgeIconSize),
                            tint = if (isPinned) tokens.colors.accent else tokens.colors.textSecondary,
                        )
                    }
                }
            }
        }
        Text(
            text = person.name,
            style = if (isTablet) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        person.knownFor?.takeIf { it.isNotBlank() }?.let { knownFor ->
            Text(
                text = knownFor,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
