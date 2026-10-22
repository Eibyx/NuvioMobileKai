package com.nuvio.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.nuvio.app.core.diagnostics.UnexpectedExitKind
import com.nuvio.app.core.diagnostics.UnexpectedExitNoticeCard
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.AnimeIdFallback
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import org.robolectric.RuntimeEnvironment
import com.nuvio.app.core.ui.AppearanceMode
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.anilist.AniListLink
import com.nuvio.app.features.anilist.AniListLinkPart
import com.nuvio.app.features.anilist.AniListLinkRowContent
import com.nuvio.app.features.anilist.AniListPendingSend
import com.nuvio.app.features.anilist.AniListQueueState
import com.nuvio.app.features.anilist.AniListSendFailure
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.settings.AniListSyncStatusContent
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.MobileStreamsLayout
import com.nuvio.app.features.streams.StreamBackgroundMode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/*
 * Renders Home, Continue Watching, the detail page and its episode list with sample artwork, in the
 * light and the dark appearance, into composeApp/build/screenshots/{light,dark}. Run with
 * ./gradlew :composeApp:testAndroidHostTest --tests "*AppearanceScreenshotTest*"
 * The dark images are there to check that the dark appearance does not change.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class AppearanceScreenshotTest(private val appearance: AppearanceMode) {
    @get:Rule
    val compose = createComposeRule()

    private val outputDir: File = File(
        "build/screenshots",
        if (appearance == AppearanceMode.DARK) "dark" else "light",
    ).apply { mkdirs() }

    @Test
    fun homeWithLightArtwork() = renderHome(heroUrl = LIGHT_BACKDROP, name = "home_light_artwork")

    @Test
    fun homeWithDarkArtwork() = renderHome(heroUrl = DARK_BACKDROP, name = "home_dark_artwork", logoUrl = WHITE_LOGO)

    @Test
    @Config(qualifiers = "w1100dp-h740dp-mdpi")
    fun homeOnTablet() = renderHome(heroUrl = DARK_BACKDROP, name = "home_tablet", viewportHeight = 740.dp)

    @Test
    fun detailPage() {
        showDetail(MetaEpisodeCardStyle.Horizontal)
        capture("detail")
    }

    @Test
    fun detailEpisodeList() {
        showDetail(MetaEpisodeCardStyle.List)
        scrollToEpisodes()
        capture("detail_episodes_list")
    }

    @Test
    @Config(qualifiers = "es-w412dp-h915dp-xhdpi")
    fun detailEpisodeListInSpanish() {
        showDetail(MetaEpisodeCardStyle.List)
        scrollToEpisodes(seasonsHeader = "Temporadas")
        capture("detail_episodes_list_es")
    }

    @Test
    fun detailEpisodeCards() {
        showDetail(MetaEpisodeCardStyle.Horizontal)
        scrollToEpisodes()
        capture("detail_episodes_cards")
    }

    @Test
    fun detailProductionChips() {
        showDetail(MetaEpisodeCardStyle.List)
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Sample Studio"))
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        capture("detail_production")
    }

    @Test
    fun signIn() {
        setScreen { AuthScreen() }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        capture("sign_in")
    }

    @Test
    fun profileBackdrop() {
        setScreen {
            Box(Modifier.fillMaxSize()) {
                ProfileBackgroundBackdrop(
                    profile = NuvioProfile(name = "Kai", avatarColorHex = "#E5338F"),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        capture("profile_backdrop")
    }

    @Test
    fun unexpectedExitNotice() {
        setScreen {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.BottomCenter,
            ) {
                UnexpectedExitNoticeCard(
                    kind = UnexpectedExitKind.NativeCrash,
                    exporting = false,
                    onExport = {},
                    onDismiss = {},
                    modifier = Modifier.padding(16.dp).padding(bottom = 96.dp),
                )
            }
        }
        compose.waitForIdle()
        capture("unexpected_exit_notice")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun playerSubtitlesWithAnExternalFile() {
        val local = com.nuvio.app.features.player.AddonSubtitle(
            id = "local:3f2a9c",
            url = "file:///cache/external_subtitles/3f2a9c-Movie.es.srt",
            language = "es",
            display = "Movie.2019.1080p.es.srt",
            addonName = "Local file",
        )
        val addon = com.nuvio.app.features.player.AddonSubtitle(
            id = "os-1",
            url = "https://example.com/sub.srt",
            language = "es",
            display = "Movie.2019.WEB.srt",
            addonName = "OpenSubtitles",
        )
        setScreen {
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101014))) {
                com.nuvio.app.features.player.SubtitleModal(
                    visible = true,
                    subtitleTracks = emptyList(),
                    selectedSubtitleIndex = -1,
                    addonSubtitles = listOf(local, addon),
                    selectedAddonSubtitleId = local.url,
                    isLoadingAddonSubtitles = false,
                    preferredSubtitleLanguage = "es",
                    secondaryPreferredSubtitleLanguage = null,
                    subtitleStyle = com.nuvio.app.features.player.SubtitleStyleState.DEFAULT,
                    subtitleDelayMs = 0,
                    selectedAddonSubtitle = local,
                    subtitleAutoSyncState = com.nuvio.app.features.player.SubtitleAutoSyncUiState(),
                    onBuiltInTrackSelected = {},
                    onAddonSubtitleSelected = {},
                    onFetchAddonSubtitles = {},
                    onStyleChanged = {},
                    onAddExternalSubtitle = {},
                    onSubtitleDelayChanged = {},
                    onSubtitleDelayReset = {},
                    onSyncByEarClick = {},
                    onAutoSyncCapture = {},
                    onAutoSyncCueSelected = {},
                    onAutoSyncReload = {},
                    onDismiss = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        capture("player_subtitles_external_file")
    }

    @Test
    fun aniListSyncStatusAndLinks() {
        val now = 1_761_150_000_000L
        val state = AniListQueueState(
            pending = listOf(
                AniListPendingSend(
                    key = "tt2560140|s3",
                    title = "Attack on Titan",
                    contentType = "series",
                    contentId = "tt2560140",
                    season = 3,
                    episode = 14,
                    attempts = 2,
                    nextAttemptAtMs = now + 60_000L,
                    lastError = "network error",
                ),
            ),
            lastSyncAtMs = now - 3_600_000L,
            lastSyncTitle = "SPY×FAMILY",
            lastSyncEpisode = 13,
            failures = listOf(
                AniListSendFailure("Obscure Anime", "no AniList entry matches", now - 7_200_000L, notFound = true),
                AniListSendFailure("Attack on Titan", "network error", now - 60_000L, notFound = false),
            ),
        )
        val links = listOf<Pair<Int?, AniListLink>>(
            1 to AniListLink("tt2560140|s1", listOf(AniListLinkPart(16498, "Attack on Titan")), manual = false),
            3 to AniListLink(
                "tt2560140|s3",
                listOf(
                    AniListLinkPart(99147, "Attack on Titan Season 3", 1, 12),
                    AniListLinkPart(104578, "Attack on Titan Season 3 Part 2", 13, 10),
                ),
                manual = false,
            ),
            4 to AniListLink("tt2560140|s4", listOf(AniListLinkPart(110277, "Attack on Titan Final Season")), manual = true),
        )
        setScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                AniListLinkRowContent(links = emptyList(), showSeasons = false, onClick = {})
                AniListLinkRowContent(links = links, showSeasons = true, onClick = {})
                AniListSyncStatusContent(state = state, onRetryNow = {}, onNotifyChange = {})
            }
        }
        compose.waitForIdle()
        capture("anilist_sync_status_and_links")
    }

    @Test
    @Config(sdk = [GLASS_SDK])
    fun detailCinematicStickyNavigator() {
        showDetail(MetaEpisodeCardStyle.List, backgroundMode = MetaScreenBackgroundMode.Cinematic, meta = singleSeasonSeries)
        scrollToText("Episode Title 9")
        capture("detail_cinematic_sticky_navigator")
    }

    @Test
    @Config(sdk = [GLASS_SDK])
    fun detailCinematicNavigatorPinning() {
        showDetail(MetaEpisodeCardStyle.List, backgroundMode = MetaScreenBackgroundMode.Cinematic, meta = singleSeasonSeries)
        val list = compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToKey("detail-episodes-navigator-bar")
        compose.mainClock.advanceTimeBy(500L)
        // Back off a little so the bar sits between its own place and the floating header.
        val backOff = with(compose.density) { 40.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, -backOff) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        capture("detail_cinematic_navigator_pinning")
    }

    @Test
    @Config(sdk = [GLASS_SDK])
    fun detailCinematicNavigatorReachingTheHeader() {
        showDetail(MetaEpisodeCardStyle.List, backgroundMode = MetaScreenBackgroundMode.Cinematic)
        scrollToText("Second Episode Title")
        capture("detail_cinematic_navigator_reaching_header")
    }

    @Test
    @Config(sdk = [GLASS_SDK])
    fun detailCinematicWithoutEpisodeFinder() {
        showDetail(
            MetaEpisodeCardStyle.List,
            backgroundMode = MetaScreenBackgroundMode.Cinematic,
            meta = singleSeasonSeries,
            episodeFinder = false,
        )
        scrollToText("Episode Title 4")
        capture("detail_cinematic_without_episode_finder")
    }

    @Test
    fun detailDirectorAndWriter() {
        showDetail(MetaEpisodeCardStyle.List)
        scrollToText("Writer", substring = true)
        capture("detail_director_writer")
    }

    @Test
    @Config(sdk = [GLASS_SDK])
    fun streamsCinematicEpisode() {
        val streams = listOf(
            "Torbox" to "torbox",
            "Comet" to "comet",
        ).map { (name, id) ->
            AddonStreamGroup(
                addonName = name,
                addonId = id,
                streams = List(3) { index ->
                    StreamItem(
                        name = "$name ${listOf("4K", "1080p", "720p")[index]}",
                        description = "Sample.Series.S01E03.${listOf("2160p", "1080p", "720p")[index]}.WEB-DL.x265\n" +
                            "${listOf("8.4", "2.1", "1.2")[index]} GB · English",
                        url = "https://example.invalid/$id/$index.mkv",
                        addonName = name,
                        addonId = id,
                    )
                },
            )
        }
        setScreen {
            MobileStreamsLayout(
                isEpisode = true,
                showSearchField = false,
                searchQuery = "",
                onSearchQueryChange = {},
                backgroundMode = StreamBackgroundMode.Cinematic,
                title = "Sample Series",
                logo = LOGO,
                heroArtwork = LIGHT_THUMB,
                seasonNumber = 1,
                episodeNumber = 3,
                episodeTitle = "Episode Title 3",
                blurEpisodeThumbnail = false,
                uiState = StreamsUiState(groups = streams, activeAddonIds = setOf("torbox", "comet")),
                debridEnabled = false,
                appendInstantServiceToDefaultName = false,
                resumePositionMs = null,
                resumeProgressFraction = null,
                onStreamSelected = { _, _, _ -> },
                onStreamLongPress = {},
                onRefresh = {},
            )
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        capture("streams_cinematic_episode")
    }

    @Test
    fun detailAnimeWithoutMetadata() {
        val context = RuntimeEnvironment.getApplication()
        AddonStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        AddonRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        MetaDetailsRepository.clear()
        val originalLookup = AnimeIdFallback.lookupExternalIds
        AnimeIdFallback.lookupExternalIds = { null }
        try {
            WatchProgressRepository.upsertPlaybackProgress(
                session = WatchProgressPlaybackSession(
                    profileId = ProfileRepository.activeProfileId,
                    contentType = "series",
                    parentMetaId = "mal:22",
                    parentMetaType = "series",
                    videoId = "mal:22:5",
                    title = "mal:22",
                    seasonNumber = 1,
                    episodeNumber = 5,
                ),
                snapshot = PlayerPlaybackSnapshot(isLoading = false, positionMs = 300_000L, durationMs = 1_380_000L),
                syncRemote = false,
            )
            setScreen { MetaDetailsScreen(type = "series", id = "mal:22", onBack = {}) }
            compose.waitUntil(timeoutMillis = 10_000) { MetaDetailsRepository.uiState.value.errorMessage != null }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            capture("detail_anime_without_metadata")
        } finally {
            AnimeIdFallback.lookupExternalIds = originalLookup
            WatchProgressRepository.clearLocalState()
            MetaDetailsRepository.clear()
        }
    }

    private fun scrollToText(text: String, substring: Boolean = false) {
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text, substring = substring))
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun showDetail(
        episodeCardStyle: MetaEpisodeCardStyle,
        backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Normal,
        meta: MetaDetails = sampleSeries,
        episodeFinder: Boolean = true,
    ) {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.setEpisodeNavigatorEnabled(episodeFinder)
        MetaScreenSettingsRepository.setEpisodeCardStyle(episodeCardStyle)
        MetaScreenSettingsRepository.setBackgroundMode(backgroundMode)
        seedMeta(meta)
        setScreen {
            MetaDetailsScreen(type = meta.type, id = meta.id, onBack = {})
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun scrollToEpisodes(seasonsHeader: String = "Seasons") {
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(seasonsHeader))
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun renderHome(heroUrl: String, name: String, viewportHeight: Dp = 915.dp, logoUrl: String = LOGO) {
        val heroItems = List(8) { index ->
            MetaPreview(
                id = "hero-$index",
                type = "movie",
                name = "Sample Title $index",
                banner = heroUrl,
                poster = heroUrl,
                logo = logoUrl,
                description = "In a world where everything is decided by games, long before the events of today, " +
                    "a boy and a girl set out to claim the title of the one true god.",
                releaseInfo = "2017",
                imdbRating = "7.3",
                genres = listOf("Action"),
            )
        }
        val continueWatching = listOf(
            sampleContinueWatching("cw-1", "Rascal Does Not Dream of Bunny Girl Senpai", "The senpai is a bunny girl", LIGHT_THUMB, 0.12f),
            sampleContinueWatching("cw-2", "One Piece", "I'm Luffy! The man who will be king of the pirates!", DARK_THUMB, 0.18f),
        )
        setScreen {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HomeHeroSection(items = heroItems, viewportHeight = viewportHeight)
                    HomeContinueWatchingSection(
                        items = continueWatching,
                        style = ContinueWatchingSectionStyle.Card,
                        dataSourceKey = WatchProgressSource.entries.first(),
                    )
                }
                FloatingNavigationBar(
                    items = listOf(
                        FloatingNavigationItem("Home", selected = true, onClick = {}, icon = Icons.Rounded.Home),
                        FloatingNavigationItem("Search", selected = false, onClick = {}, icon = Icons.Rounded.Search),
                        FloatingNavigationItem("Library", selected = false, onClick = {}, icon = Icons.Rounded.VideoLibrary),
                        FloatingNavigationItem("Profile", selected = false, onClick = {}, icon = Icons.Rounded.Person),
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        capture(name)
    }

    private fun setScreen(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                    (sampleImage(request.data.toString()) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
                },
            ) {
                NuvioTheme(appTheme = AppTheme.VIOLET, appearance = appearance) {
                    content()
                }
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedMeta(meta: MetaDetails) {
        val repository = MetaDetailsRepository
        val repositoryClass = repository::class.java
        val fingerprint = repositoryClass.declaredMethods
            .first { it.name.startsWith("buildMetaScreenSettingsFingerprint") }
            .apply { isAccessible = true }
            .invoke(repository, MdbListSettingsRepository.snapshot()) as String
        val entryClass = Class.forName("${repositoryClass.name}\$CachedMetaEntry")
        val entry = entryClass.declaredConstructors
            .first { it.parameterCount == 3 }
            .apply { isAccessible = true }
            .newInstance(meta, meta, fingerprint)
        val cache = repositoryClass.getDeclaredField("cachedMetaByRequestKey")
            .apply { isAccessible = true }
            .get(repository) as MutableMap<String, Any>
        cache["${meta.type}:${meta.id}"] = entry
        val state = repositoryClass.getDeclaredField("_uiState")
            .apply { isAccessible = true }
            .get(repository) as MutableStateFlow<MetaDetailsUiState>
        state.value = MetaDetailsUiState(meta = meta)
    }

    private fun sampleContinueWatching(
        id: String,
        title: String,
        episodeTitle: String,
        image: String,
        progress: Float,
    ) = ContinueWatchingItem(
        parentMetaId = id,
        parentMetaType = "series",
        videoId = "$id:1:1",
        title = title,
        subtitle = "S1 E1",
        imageUrl = image,
        background = image,
        episodeThumbnail = image,
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = episodeTitle,
        resumePositionMs = (24 * 60_000L * progress).toLong(),
        durationMs = 24 * 60_000L,
        progressFraction = progress,
    )

    private val sampleSeries = MetaDetails(
        id = "tt-sample",
        type = "series",
        name = "Sample Series",
        background = LIGHT_BACKDROP,
        poster = LIGHT_BACKDROP,
        logo = LOGO,
        description = "Escape is impossible until the game is cleared; a game over means a true death. " +
            "Without knowing the truth of the next generation of massively multiplayer online games, " +
            "ten thousand players log in together and open the curtains on a cruel fight.",
        releaseInfo = "2012-2020",
        imdbRating = "7.5",
        ageRating = "TV-MA",
        runtime = "25m",
        genres = listOf("Science Fiction", "Fantasy", "Animation"),
        externalRatings = listOf(
            MetaExternalRating(source = "imdb", value = 7.5),
            MetaExternalRating(source = "tmdb", value = 81.0),
        ),
        cast = List(6) { MetaPerson(name = "Actor Name $it", role = "Role $it") },
        productionCompanies = listOf(
            MetaCompany(name = "Sample Studio"),
            MetaCompany(name = "Second Pictures"),
            MetaCompany(name = "Third Animation"),
        ),
        director = listOf("Tomohiko Ito"),
        writer = listOf("Reki Kawahara", "Yukito Kizawa", "Munemasa Nakamoto", "Kazuho Hyodo"),
        videos = (1..4).flatMap { season ->
            (1..(if (season == 1) 24 else 6)).map { episode ->
                MetaVideo(
                    id = "tt-sample:$season:$episode",
                    title = when (episode) {
                        1 -> "First Episode Title"
                        2 -> "Second Episode Title"
                        else -> "Episode Title $episode"
                    },
                    released = "2012-07-${((episode - 1) % 28 + 1).toString().padStart(2, '0')}T00:00:00.000Z",
                    thumbnail = if (episode % 2 == 0) DARK_THUMB else LIGHT_THUMB,
                    season = season,
                    episode = episode,
                    overview = "Kirito and the others are in a safe zone, when a murder happens that looks like a " +
                        "loophole in the rules, and they set out to find the culprit.",
                    runtime = 24,
                    streams = listOf(StreamItem(name = "Sample", url = "https://example.invalid/video.mp4", addonName = "Sample", addonId = "sample")),
                )
            }
        },
    )

    private val singleSeasonSeries = sampleSeries.copy(
        id = "tt-sample-single",
        videos = sampleSeries.videos.filter { it.season == 1 }.map { it.copy(id = it.id.replace("tt-sample", "tt-sample-single")) },
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun appearances(): List<Array<Any>> = listOf(arrayOf(AppearanceMode.LIGHT), arrayOf(AppearanceMode.DARK))

        /**
         * Robolectric cannot compile the refraction AGSL shader of SDK 33+, so glass screens render on
         * SDK 32, which has RenderEffect blur but no RuntimeShader: the glass without refraction.
         */
        const val GLASS_SDK = 32

        const val LIGHT_BACKDROP = "sample://light-backdrop"
        const val DARK_BACKDROP = "sample://dark-backdrop"
        const val LIGHT_THUMB = "sample://light-thumb"
        const val DARK_THUMB = "sample://dark-thumb"
        const val LOGO = "sample://logo"
        const val WHITE_LOGO = "sample://white-logo"

        private val imageCache = mutableMapOf<String, Bitmap>()

        fun sampleImage(url: String): Bitmap? = imageCache.getOrPut(url) {
            when (url) {
                LIGHT_BACKDROP -> artwork(1280, 1920, light = true)
                DARK_BACKDROP -> artwork(1280, 1920, light = false)
                LIGHT_THUMB -> artwork(640, 360, light = true)
                DARK_THUMB -> artwork(640, 360, light = false)
                LOGO -> logo(0xFFE5338F.toInt())
                WHITE_LOGO -> logo(0xFFF4F4F4.toInt())
                else -> return null
            }
        }

        /** A sky with a sun and a figure: pale and bright, or a night scene. */
        private fun artwork(width: Int, height: Int, light: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val (top, bottom) = if (light) 0xFFFBE3C8.toInt() to 0xFFF4F1F8.toInt() else 0xFF2B0F3F.toInt() to 0xFF7A1630.toInt()
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = RadialGradient(
                width * 0.7f, height * 0.25f, width * 0.35f,
                if (light) 0xFFFFC27A.toInt() else 0xFF55E6FF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(width * 0.7f, height * 0.25f, width * 0.35f, paint)
            paint.shader = null
            paint.color = if (light) 0xFFE0A26A.toInt() else 0xFFB03A7A.toInt()
            canvas.drawCircle(width * 0.45f, height * 0.42f, width * 0.12f, paint)
            paint.color = if (light) 0xFFFFFFFF.toInt() else 0xFF1A0A24.toInt()
            canvas.drawRoundRect(width * 0.28f, height * 0.52f, width * 0.62f, height * 1.05f, width * 0.1f, width * 0.1f, paint)
            paint.color = if (light) 0xFFC23B3B.toInt() else 0xFF3FD3FF.toInt()
            canvas.drawRect(width * 0.28f, height * 0.62f, width * 0.62f, height * 0.66f, paint)
            return bitmap
        }

        private fun logo(color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(900, 340, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 150f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("SAMPLE", 450f, 160f, paint)
            canvas.drawText("TITLE", 450f, 310f, paint)
            return bitmap
        }
    }
}
