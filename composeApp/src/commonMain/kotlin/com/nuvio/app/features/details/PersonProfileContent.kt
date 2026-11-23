package com.nuvio.app.features.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.details.components.DetailPosterRailSection
import com.nuvio.app.features.details.components.ExpandableDescription
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal enum class PersonTab { Overview, Filmography, Images }

internal enum class PersonMediaFilter { All, Movies, Series }

internal enum class PersonSort { Date, Rating, Popularity }

/** The state of the filmography controls, shared by the phone and the wide layout. */
private class PersonFilmographyControls(initialDepartment: String?) {
    var department by mutableStateOf(initialDepartment)
    var media by mutableStateOf(PersonMediaFilter.All)
    var sort by mutableStateOf(PersonSort.Date)
    var listMode by mutableStateOf(false)
}

/** Departments with credits, most credits first; acting first when it is among them. */
internal fun personDepartments(credits: List<PersonCredit>): List<Pair<String, Int>> =
    credits.groupingBy { it.department }.eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }

/** The role to show first: what the person is known for, else the role with most credits. */
internal fun defaultPersonDepartment(credits: List<PersonCredit>, knownFor: String?): String? {
    val departments = personDepartments(credits)
    if (departments.size <= 1) return null
    val known = knownFor?.trim()?.let { value ->
        departments.firstOrNull { it.first.equals(value, ignoreCase = true) }?.first
    }
    return known ?: departments.first().first
}

internal fun filterPersonFilmography(
    credits: List<PersonCredit>,
    department: String?,
    media: PersonMediaFilter,
    sort: PersonSort,
): List<PersonCredit> {
    val inRole = credits.filter { credit ->
        (department == null || credit.department == department) &&
            when (media) {
                PersonMediaFilter.All -> true
                PersonMediaFilter.Movies -> credit.item.type == "movie"
                PersonMediaFilter.Series -> credit.item.type != "movie"
            }
    }
    val sorted = when (sort) {
        PersonSort.Date -> inRole.sortedWith(
            compareBy<PersonCredit> { it.item.rawReleaseDate.isNullOrBlank() }
                .thenByDescending { it.item.rawReleaseDate.orEmpty() },
        )
        // Few votes say little: those go after the titles with a real rating.
        PersonSort.Rating -> inRole.sortedWith(
            compareBy<PersonCredit> { it.voteCount < MIN_VOTES_FOR_RATING || it.voteAverage == null }
                .thenByDescending { it.voteAverage ?: 0.0 }
                .thenByDescending { it.voteCount },
        )
        PersonSort.Popularity -> inRole.sortedByDescending { it.item.popularity ?: 0.0 }
    }
    // With every role shown, a title the person both directed and acted in appears once.
    return if (department == null) sorted.distinctBy { it.item.stableKey() } else sorted
}

private const val MIN_VOTES_FOR_RATING = 10

@Composable
private fun departmentLabel(department: String): String = when (department.lowercase()) {
    "acting" -> stringResource(Res.string.person_department_acting)
    "directing" -> stringResource(Res.string.person_department_directing)
    "writing" -> stringResource(Res.string.person_department_writing)
    "production" -> stringResource(Res.string.person_department_production)
    "camera" -> stringResource(Res.string.person_department_camera)
    "editing" -> stringResource(Res.string.person_department_editing)
    "sound" -> stringResource(Res.string.person_department_sound)
    "art" -> stringResource(Res.string.person_department_art)
    "costume & make-up" -> stringResource(Res.string.person_department_costume)
    "visual effects" -> stringResource(Res.string.person_department_visual_effects)
    "lighting" -> stringResource(Res.string.person_department_lighting)
    "creator" -> stringResource(Res.string.person_department_creator)
    "crew" -> stringResource(Res.string.person_department_crew)
    else -> department
}

private fun personGridColumns(width: Dp): Int =
    (((width - 32.dp + 12.dp) / (112.dp + 12.dp)).toInt()).coerceIn(3, 8)

// ─── Phone ───

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun PhonePersonProfile(
    person: PersonDetail,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    fallbackProfilePhoto: String?,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    var tab by remember(person.tmdbId) { mutableStateOf(PersonTab.Overview) }
    val controls = remember(person.tmdbId) {
        PersonFilmographyControls(defaultPersonDepartment(person.filmography, person.knownFor))
    }
    val gridState = rememberLazyGridState()
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val images = rememberPersonImages(person.tmdbId, load = tab == PersonTab.Images)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = personGridColumns(maxWidth)
        val heroHeight = min(maxWidth * 1.2f, maxHeight * 0.66f)
        val density = LocalDensity.current
        val topBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 56.dp

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PERSON_GRID_INSET,
                end = PERSON_GRID_INSET,
                bottom = nuvioSafeBottomPadding(32.dp),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fullWidth("hero") {
                PersonHero(
                    person = person,
                    height = heroHeight,
                    fallbackProfilePhoto = fallbackProfilePhoto,
                    avatarTransitionKey = avatarTransitionKey,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            fullWidth("summary") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PersonFavoriteChip(isFavorite = isFavorite, onClick = onToggleFavorite)
                    PersonFactsCard(person = person)
                }
            }
            fullWidth("tabs") {
                PersonTabRow(
                    tabs = PersonTab.entries,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            personTabContent(
                tab = tab,
                person = person,
                controls = controls,
                images = images,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                onOpenMeta = onOpenMeta,
                onOpenImage = { viewerIndex = it },
                horizontalPadding = 16.dp,
            )
        }

        PersonPhoneTopBar(
            gridState = gridState,
            title = person.name,
            heroHeight = heroHeight,
            topBarHeight = topBarHeight,
            tab = tab,
            onSelectTab = { tab = it },
        )
    }

    val shownImages = images
    viewerIndex?.let { index ->
        if (shownImages != null && index in shownImages.indices) {
            PersonImageViewer(images = shownImages, initialIndex = index, onDismiss = { viewerIndex = null })
        }
    }
}

/** The name bar that fades in once the header scrolls away, and the tabs pinned under it. */
@Composable
private fun PersonPhoneTopBar(
    gridState: LazyGridState,
    title: String,
    heroHeight: Dp,
    topBarHeight: Dp,
    tab: PersonTab,
    onSelectTab: (PersonTab) -> Unit,
) {
    val density = LocalDensity.current
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val topBarHeightPx = with(density) { topBarHeight.toPx() }
    val barVisible by remember(heroHeightPx, topBarHeightPx) {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > heroHeightPx - topBarHeightPx * 1.6f
        }
    }
    val tabsPinned by remember(topBarHeightPx) {
        derivedStateOf {
            val tabsItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "tabs" }
            if (tabsItem != null) tabsItem.offset.y < topBarHeightPx else gridState.firstVisibleItemIndex > 2
        }
    }
    val barAlpha by animateFloatAsState(if (barVisible) 1f else 0f, label = "personTopBar")

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topBarHeight)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f * barAlpha)),
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 60.dp, end = 60.dp, bottom = 16.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = barAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = tabsPinned, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            ) {
                PersonTabRow(tabs = PersonTab.entries, selected = tab, onSelect = onSelectTab)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PersonHero(
    person: PersonDetail,
    height: Dp,
    fallbackProfilePhoto: String?,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    val photoUrl = person.heroPhoto ?: person.profilePhoto ?: fallbackProfilePhoto
    val platformContext = LocalPlatformContext.current
    val request = remember(platformContext, photoUrl, avatarTransitionKey) {
        photoUrl?.let { url ->
            // The cast avatar already in memory shows at once while the larger photo loads.
            ImageRequest.Builder(platformContext)
                .data(url)
                .placeholderMemoryCacheKey(avatarTransitionKey)
                .build()
        }
    }
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = avatarTransitionKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }
    val background = MaterialTheme.colorScheme.background
    val departmentCount = remember(person.filmography) { person.filmography.distinctBy { it.item.stableKey() }.size }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(sharedModifier)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = person.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text(
                    text = person.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to background.copy(alpha = 0.35f),
                        0.18f to Color.Transparent,
                        0.52f to Color.Transparent,
                        0.82f to background.copy(alpha = 0.82f),
                        1.0f to background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 36.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildList {
                person.knownFor?.let { add(departmentLabel(it)) }
                if (departmentCount > 0) add(pluralStringResource(Res.plurals.person_titles_count, departmentCount, departmentCount))
            }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PersonFavoriteChip(isFavorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (isFavorite) accent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val content = if (isFavorite) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (isFavorite) Res.string.person_favorite_on else Res.string.person_favorite_off),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = content,
        )
    }
}

@Composable
internal fun PersonFactsCard(person: PersonDetail, modifier: Modifier = Modifier) {
    val credits = remember(person.filmography) { person.filmography.distinctBy { it.item.stableKey() } }
    val firstYear = remember(credits) {
        credits.mapNotNull { it.item.rawReleaseDate?.take(4)?.toIntOrNull() }.minOrNull()
    }
    val facts = buildList {
        person.birthday?.let { add(Triple(Icons.Rounded.Cake, stringResource(Res.string.person_detail_born), personBirthText(it, person.deathday))) }
        person.placeOfBirth?.takeIf { it.isNotBlank() }?.let {
            add(Triple(Icons.Rounded.Place, stringResource(Res.string.person_detail_place_of_birth), it))
        }
        if (credits.isNotEmpty()) {
            val count = pluralStringResource(Res.plurals.person_titles_count, credits.size, credits.size)
            val value = if (firstYear != null) stringResource(Res.string.person_titles_since, count, firstYear) else count
            add(Triple(Icons.Rounded.Movie, stringResource(Res.string.person_detail_credits), value))
        }
    }
    if (facts.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        facts.forEach { (icon, label, value) -> PersonFactRow(icon = icon, label = label, value = value) }
    }
}

@Composable
private fun personBirthText(birthday: String, deathday: String?): String {
    val birth = formatDateForDisplay(birthday) ?: birthday
    val age = calculateAge(birthday, deathday)
    val death = deathday?.let { formatDateForDisplay(it) ?: it }
    return buildList {
        add(birth)
        death?.let { add(stringResource(Res.string.person_died, it)) }
        age?.let { add(pluralStringResource(Res.plurals.person_years_old, it, it)) }
    }.joinToString(" · ")
}

@Composable
private fun PersonFactRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 10.dp).width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PersonTabRow(
    tabs: List<PersonTab>,
    selected: PersonTab,
    onSelect: (PersonTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(4.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (tab) {
                            PersonTab.Overview -> Res.string.person_tab_overview
                            PersonTab.Filmography -> Res.string.person_tab_filmography
                            PersonTab.Images -> Res.string.person_tab_images
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ─── Tab content (shared) ───

@Composable
private fun rememberPersonImages(personId: Int, load: Boolean): List<PersonImage>? {
    var images by remember(personId) { mutableStateOf<List<PersonImage>?>(null) }
    var requested by remember(personId) { mutableStateOf(false) }
    if (load && !requested) requested = true
    LaunchedEffect(personId, requested) {
        if (requested && images == null) {
            images = TmdbMetadataService.fetchPersonImages(personId)
        }
    }
    return images
}

/** The grid is inset by [PERSON_GRID_INSET]; full-width rows spread back to the screen edges. */
private fun LazyGridScope.fullWidth(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(modifier = Modifier.bleed(PERSON_GRID_INSET)) { content() }
    }
}

private val PERSON_GRID_INSET = 16.dp

private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-extra / 2, 0)
    }
}

private fun LazyGridScope.personTabContent(
    tab: PersonTab,
    person: PersonDetail,
    controls: PersonFilmographyControls,
    images: List<PersonImage>?,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    onOpenImage: (Int) -> Unit,
    horizontalPadding: Dp,
) {
    when (tab) {
        PersonTab.Overview -> personOverview(
            person = person,
            controls = controls,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onOpenMeta = onOpenMeta,
            horizontalPadding = horizontalPadding,
        )
        PersonTab.Filmography -> personFilmography(
            person = person,
            controls = controls,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onOpenMeta = onOpenMeta,
            horizontalPadding = horizontalPadding,
        )
        PersonTab.Images -> personImages(
            images = images,
            onOpenImage = onOpenImage,
            horizontalPadding = horizontalPadding,
        )
    }
}

private fun LazyGridScope.personOverview(
    person: PersonDetail,
    controls: PersonFilmographyControls,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    horizontalPadding: Dp,
) {
    person.biography?.takeIf { it.isNotBlank() }?.let { biography ->
        fullWidth("overview-bio") {
            Column(
                modifier = Modifier.padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.person_detail_biography),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ExpandableDescription(
                    text = biography,
                    collapsedMaxLines = 5,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val knownFor = filterPersonFilmography(person.filmography, controls.department, PersonMediaFilter.All, PersonSort.Popularity)
        .distinctBy { it.item.stableKey() }
        .take(16)
        .map { it.item }
    if (knownFor.isNotEmpty()) {
        fullWidth("overview-known") {
            DetailPosterRailSection(
                title = stringResource(Res.string.person_known_for_title),
                items = knownFor,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                headerHorizontalPadding = horizontalPadding,
                onPosterClick = onOpenMeta,
            )
        }
    }
    val today = CurrentDateProvider.todayIsoDate()
    val upcoming = person.filmography
        .filter { (it.item.rawReleaseDate ?: "") > today }
        .sortedBy { it.item.rawReleaseDate }
        .distinctBy { it.item.stableKey() }
        .map { it.item }
    if (upcoming.isNotEmpty()) {
        fullWidth("overview-upcoming") {
            DetailPosterRailSection(
                title = stringResource(Res.string.person_upcoming),
                items = upcoming,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                headerHorizontalPadding = horizontalPadding,
                onPosterClick = onOpenMeta,
            )
        }
    }
}

private fun LazyGridScope.personFilmography(
    person: PersonDetail,
    controls: PersonFilmographyControls,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    horizontalPadding: Dp,
) {
    val credits = filterPersonFilmography(person.filmography, controls.department, controls.media, controls.sort)
    fullWidth("filmography-controls") {
        PersonFilmographyControlsBar(
            person = person,
            controls = controls,
            count = credits.size,
            horizontalPadding = horizontalPadding,
        )
    }
    if (credits.isEmpty()) {
        fullWidth("filmography-empty") {
            Text(
                text = stringResource(Res.string.person_filmography_empty),
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (controls.listMode) {
        credits.forEach { credit ->
            fullWidth("row-${credit.department}-${credit.item.stableKey()}") {
                PersonCreditRow(
                    credit = credit,
                    isWatched = WatchingState.isPosterWatched(
                        watchedKeys = watchedKeys,
                        item = credit.item,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    ),
                    onClick = { onOpenMeta(credit.item) },
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            }
        }
    } else {
        itemsIndexed(
            items = credits,
            key = { _, credit -> "tile-${credit.department}-${credit.item.stableKey()}" },
        ) { _, credit ->
            PersonCreditTile(
                credit = credit,
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = credit.item,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                ),
                onClick = { onOpenMeta(credit.item) },
            )
        }
    }
}

private fun LazyGridScope.personImages(
    images: List<PersonImage>?,
    onOpenImage: (Int) -> Unit,
    horizontalPadding: Dp,
) {
    when {
        images == null -> fullWidth("images-loading") {
            Text(
                text = stringResource(Res.string.person_images_loading),
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        images.isEmpty() -> fullWidth("images-empty") {
            Text(
                text = stringResource(Res.string.person_images_empty),
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> itemsIndexed(images, key = { _, image -> "image-${image.full}" }) { index, image ->
            AsyncImage(
                model = image.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenImage(index) },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PersonFilmographyControlsBar(
    person: PersonDetail,
    controls: PersonFilmographyControls,
    count: Int,
    horizontalPadding: Dp,
) {
    val departments = remember(person.filmography) { personDepartments(person.filmography) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (departments.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    PersonChip(
                        label = stringResource(Res.string.person_role_all),
                        selected = controls.department == null,
                        onClick = { controls.department = null },
                    )
                }
                items(departments, key = { it.first }) { (department, total) ->
                    PersonChip(
                        label = "${departmentLabel(department)} · $total",
                        selected = controls.department == department,
                        onClick = { controls.department = department },
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(PersonMediaFilter.entries, key = { it.name }) { filter ->
                PersonChip(
                    label = stringResource(
                        when (filter) {
                            PersonMediaFilter.All -> Res.string.person_filter_all
                            PersonMediaFilter.Movies -> Res.string.person_filter_movies
                            PersonMediaFilter.Series -> Res.string.person_filter_series
                        },
                    ),
                    selected = controls.media == filter,
                    onClick = { controls.media = filter },
                )
            }
            item(key = "sort") { PersonSortChip(controls = controls) }
        }
        Row(
            modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(Res.plurals.person_titles_count, count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { controls.listMode = !controls.listMode }) {
                Icon(
                    imageVector = if (controls.listMode) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                    contentDescription = stringResource(
                        if (controls.listMode) Res.string.person_view_grid else Res.string.person_view_list,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PersonChip(label: String, selected: Boolean, onClick: () -> Unit, leadingIcon: ImageVector? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, maxLines = 1) },
        leadingIcon = leadingIcon?.let { icon ->
            { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
        },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun PersonSortChip(controls: PersonFilmographyControls) {
    var expanded by remember { mutableStateOf(false) }
    @Composable
    fun label(sort: PersonSort): String = stringResource(
        when (sort) {
            PersonSort.Date -> Res.string.person_sort_date
            PersonSort.Rating -> Res.string.person_sort_rating
            PersonSort.Popularity -> Res.string.person_sort_popularity
        },
    )
    Box {
        PersonChip(
            label = label(controls.sort),
            selected = false,
            onClick = { expanded = true },
            leadingIcon = Icons.AutoMirrored.Rounded.Sort,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PersonSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(sort),
                            fontWeight = if (sort == controls.sort) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        controls.sort = sort
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PersonCreditPoster(
    credit: PersonCredit,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val shape = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)
    val item = credit.item
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .nuvioCardDepth(shape = shape, surface = NuvioCardDepthSurface.Posters)
            .posterCardClickable(
                onClick = onClick,
                onLongClick = null,
                zoomImageUrl = item.poster,
                zoomCornerRadius = posterCardStyle.cornerRadiusDp.dp,
            ),
    ) {
        val platformContext = LocalPlatformContext.current
        val hasFallback = !item.rawPosterUrl.isNullOrBlank() && item.rawPosterUrl != item.poster
        val model = remember(item.poster, item.rawPosterUrl, platformContext) {
            if (hasFallback) {
                ImageRequest.Builder(platformContext)
                    .data(item.poster)
                    .memoryCacheKeyExtras(
                        mapOf(com.nuvio.app.core.poster.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to item.rawPosterUrl),
                    )
                    .build()
            } else {
                item.poster
            }
        }
        AsyncImage(
            model = model,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        NuvioPosterWatchedOverlay(isWatched = isWatched)
    }
}

@Composable
private fun PersonCreditTile(credit: PersonCredit, isWatched: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PersonCreditPoster(credit = credit, isWatched = isWatched, onClick = onClick, modifier = Modifier.fillMaxWidth())
        Text(
            text = credit.item.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = creditYearAndType(credit),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CreditRating(credit)
        }
    }
}

@Composable
private fun PersonCreditRow(credit: PersonCredit, isWatched: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PersonCreditPoster(credit = credit, isWatched = isWatched, onClick = onClick, modifier = Modifier.width(68.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = credit.item.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = creditYearAndType(credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (credit.roles.isNotEmpty()) {
                Text(
                    text = credit.roles.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CreditRating(credit)
    }
}

@Composable
private fun creditYearAndType(credit: PersonCredit): String {
    val type = stringResource(if (credit.item.type == "movie") Res.string.person_type_movie else Res.string.person_type_series)
    return listOfNotNull(credit.item.releaseInfo, type).joinToString(" · ")
}

@Composable
private fun CreditRating(credit: PersonCredit) {
    val rating = credit.voteAverage?.takeIf { credit.voteCount >= MIN_VOTES_FOR_RATING } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = Color(0xFFF5C518),
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = ((rating * 10).roundToInt() / 10.0).toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PersonImageViewer(images: List<PersonImage>, initialIndex: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = images[page].full,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${images.size}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = Color.White,
                )
            }
        }
    }
}

// ─── Wide (tablet, landscape) ───

/** The right side of the wide layout: the identity stays in the sidebar, the rest goes in tabs. */
@Composable
internal fun WidePersonTabs(
    person: PersonDetail,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(PersonTab.Filmography, PersonTab.Images)
    var tab by remember(person.tmdbId) { mutableStateOf(PersonTab.Filmography) }
    val controls = remember(person.tmdbId) {
        PersonFilmographyControls(defaultPersonDepartment(person.filmography, person.knownFor))
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val images = rememberPersonImages(person.tmdbId, load = tab == PersonTab.Images)

    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val columns = personGridColumns(maxWidth)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PERSON_GRID_INSET,
                top = 32.dp,
                end = PERSON_GRID_INSET,
                bottom = nuvioSafeBottomPadding(32.dp),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fullWidth("wide-tabs") {
                PersonTabRow(
                    tabs = tabs,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = 16.dp).width(360.dp),
                )
            }
            personTabContent(
                tab = tab,
                person = person,
                controls = controls,
                images = images,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                onOpenMeta = onOpenMeta,
                onOpenImage = { viewerIndex = it },
                horizontalPadding = 16.dp,
            )
        }
    }

    viewerIndex?.let { index ->
        val shown = images
        if (shown != null && index in shown.indices) {
            PersonImageViewer(images = shown, initialIndex = index, onDismiss = { viewerIndex = null })
        }
    }
}
