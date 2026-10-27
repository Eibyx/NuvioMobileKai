package com.nuvio.app.screenshots

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.AppearanceMode
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioWindowSize
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerControlActions
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerProgressBar
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerToolbar
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.MobileStreamsLayout
import com.nuvio.app.features.streams.StreamBackgroundMode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.TabletStreamsLayout
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.DARK_BACKDROP
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.DARK_THUMB
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.LIGHT_BACKDROP
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.LIGHT_THUMB
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.LOGO
import com.nuvio.app.screenshots.AppearanceScreenshotTest.Companion.WHITE_LOGO
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/*
 * The main screens at the sizes the window size classes must hold: a 325 dp phone (high DPI),
 * 360 and 411 dp phones, a phone set to a smallest width of 600 held upright (600 × 1333) and on its
 * side (1333 × 600), and the smallest ones at font scale 1.3; light and dark. Into
 * composeApp/build/screenshots/sizes/<size>/<appearance>/. The player is drawn on its side.
 * Run with ./gradlew :composeApp:testAndroidHostTest --tests "*WindowSize*ScreenshotTest*"
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class WindowSizeScreenshotTest(
    private val appearance: AppearanceMode,
    private val sizeName: String,
    private val fontScale: Float = 1f,
) {
    @get:Rule
    val compose = createComposeRule()

    private val outputDir: File
        get() = File(
            "build/screenshots/sizes/$sizeName${if (fontScale != 1f) "_font${(fontScale * 100).toInt()}" else ""}",
            if (appearance == AppearanceMode.DARK) "dark" else "light",
        ).apply { mkdirs() }

    @Before
    fun storages() {
        val context = RuntimeEnvironment.getApplication()
        AddonStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchedStorage.initialize(context)
        LibraryStorage.initialize(context)
    }

    @Test
    fun home() {
        val heroItems = List(6) { index ->
            MetaPreview(
                id = "hero-$index",
                type = "series",
                name = "Sample Title With A Long Name $index",
                banner = DARK_BACKDROP,
                poster = DARK_BACKDROP,
                logo = WHITE_LOGO,
                description = "In a world where everything is decided by games, long before the events of today, " +
                    "a boy and a girl set out to claim the title of the one true god.",
                releaseInfo = "2017",
                imdbRating = "7.3",
                genres = listOf("Action", "Adventure", "Fantasy"),
            )
        }
        val continueWatching = listOf(
            continueWatching("cw-1", "So What's Wrong with Getting Reborn as a Goblin?", "A long episode title that should end with an ellipsis", LIGHT_THUMB, 0.42f),
            continueWatching("cw-2", "One Piece", "I'm Luffy! The man who will be king of the pirates!", DARK_THUMB, 0.18f),
            continueWatching("cw-3", "Kuroko no Basket", "I Am Kuroko", LIGHT_THUMB, 0.7f),
        )
        setScreen { windowSize ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HomeHeroSection(items = heroItems, viewportHeight = windowSize.height)
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
        settle(1_500L)
        capture("home")
    }

    @Test
    fun animeDetailWithEpisodes() {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.setEpisodeNavigatorEnabled(true)
        MetaScreenSettingsRepository.setEpisodeCardStyle(MetaEpisodeCardStyle.List)
        MetaScreenSettingsRepository.setBackgroundMode(MetaScreenBackgroundMode.Normal)
        seedMeta(anime)
        setScreen { MetaDetailsScreen(type = anime.type, id = anime.id, onBack = {}) }
        settle(2_000L)
        capture("detail")
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Seasons"))
        settle(1_000L)
        capture("detail_episodes")
    }

    /** On the glass SDK: the tablet layout's glass shader does not compile under Robolectric's 33+. */
    @Test
    @Config(sdk = [AppearanceScreenshotTest.GLASS_SDK])
    fun streams() {
        val groups = listOf("Torbox" to "torbox", "AIOStreams" to "aiostreams").map { (name, id) ->
            AddonStreamGroup(
                addonName = name,
                addonId = id,
                streams = List(3) { index ->
                    StreamItem(
                        name = "$name ${listOf("4K HDR DV", "1080p", "720p")[index]}",
                        description = "Kuroko.no.Basket.S01E04.${listOf("2160p", "1080p", "720p")[index]}.BluRay.10bit.x265.Dual-Audio\n" +
                            "${listOf("8.4", "2.1", "1.2")[index]} GB · Japanese · English subtitles",
                        url = "https://example.invalid/$id/$index.mkv",
                        addonName = name,
                        addonId = id,
                    )
                },
            )
        }
        val uiState = StreamsUiState(groups = groups, activeAddonIds = setOf("torbox", "aiostreams"))
        setScreen { windowSize ->
            if (windowSize.isExpanded) {
                TabletStreamsLayout(
                    isEpisode = true, showSearchField = false, searchQuery = "", onSearchQueryChange = {},
                    title = anime.name, logo = LOGO, poster = LIGHT_BACKDROP, background = LIGHT_BACKDROP,
                    episodeThumbnail = LIGHT_THUMB, seasonNumber = 1, episodeNumber = 4,
                    episodeTitle = "A Long Episode Title For The Streams Header", uiState = uiState,
                    debridEnabled = false, appendInstantServiceToDefaultName = false,
                    resumePositionMs = null, resumeProgressFraction = null,
                    onStreamSelected = { _, _, _ -> }, onStreamLongPress = {}, onRefresh = {},
                )
            } else {
                MobileStreamsLayout(
                    isEpisode = true, showSearchField = false, searchQuery = "", onSearchQueryChange = {},
                    backgroundMode = StreamBackgroundMode.Normal, title = anime.name, logo = LOGO,
                    heroArtwork = LIGHT_THUMB, seasonNumber = 1, episodeNumber = 4,
                    episodeTitle = "A Long Episode Title For The Streams Header", blurEpisodeThumbnail = false,
                    uiState = uiState, debridEnabled = false, appendInstantServiceToDefaultName = false,
                    resumePositionMs = null, resumeProgressFraction = null,
                    onStreamSelected = { _, _, _ -> }, onStreamLongPress = {}, onRefresh = {},
                )
            }
        }
        settle(1_500L)
        capture("streams")
    }

    @Test
    fun library() {
        LibraryRepository.clearLocalState()
        listOf(
            "So What's Wrong with Getting Reborn as a Goblin?", "Kuroko no Basket", "One Piece", "Frieren: Beyond Journey's End",
            "Sword Art Online", "Spy x Family", "The Apothecary Diaries", "Dandadan",
        ).forEachIndexed { index, name ->
            LibraryRepository.save(
                LibraryItem(
                    id = "tt-lib-$index",
                    type = "series",
                    name = name,
                    poster = if (index % 2 == 0) LIGHT_BACKDROP else DARK_BACKDROP,
                    releaseInfo = "20${10 + index}",
                    savedAtEpochMs = 1_000L * (10 - index),
                ),
            )
        }
        setScreen { LibraryScreen() }
        settle(1_500L)
        capture("library")
        LibraryRepository.clearLocalState()
    }

    @Test
    fun settings() {
        setScreen { SettingsScreen() }
        settle(1_500L)
        capture("settings")
    }

    /** The player is held sideways: the window's long side across. */
    @Test
    @Config(qualifiers = "+land")
    fun playerControls() {
        setScreen(landscape = true) { windowSize ->
            val metrics = PlayerLayoutMetrics.fromWindow(windowSize.width, windowSize.height)
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF6C7A89))) {
                PlayerToolbar(
                    isLocked = false,
                    onLockToggle = {},
                    onBack = {},
                    onCastClick = {},
                    onPictureInPictureClick = {},
                    showShaderCompare = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                )
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = metrics.horizontalPadding, vertical = 12.dp)) {
                    PlayerProgressBar(
                        positionMs = 754_000L,
                        durationMs = 1_420_000L,
                        bufferedPositionMs = 900_000L,
                        onScrubChange = {},
                        onScrubFinished = {},
                        chapters = listOf(PlayerChapter(0L, "Intro"), PlayerChapter(90_000L, "Part A"), PlayerChapter(1_300_000L, "Ending")),
                    )
                    PlayerControlActions(
                        playbackSnapshot = PlayerPlaybackSnapshot(positionMs = 754_000L, durationMs = 1_420_000L),
                        displayedPositionMs = 754_000L,
                        showRemainingTime = false,
                        onRuntimeClick = {},
                        metrics = metrics,
                        resizeMode = PlayerResizeMode.entries.first(),
                        onSubtitleClick = {},
                        onAudioClick = {},
                        onSourcesClick = {},
                        onEpisodesClick = {},
                        onNextEpisodeClick = {},
                        onSpeedClick = {},
                        onShadersClick = {},
                        shadersActive = true,
                        onResizeModeClick = {},
                        onVideoSettingsClick = null,
                        onOpenInExternalPlayer = null,
                        onStreamInfoClick = {},
                        onSubmitIntroClick = null,
                        onInteraction = {},
                    )
                }
            }
        }
        settle(800L)
        capture("player_controls")
    }

    private fun settle(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun setScreen(landscape: Boolean = false, content: @Composable (NuvioWindowSize) -> Unit) {
        compose.setContent {
            val configuration = LocalConfiguration.current
            val portrait = NuvioWindowSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
            val windowSize = if (landscape && portrait.height > portrait.width) {
                NuvioWindowSize(portrait.height, portrait.width)
            } else {
                portrait
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, fontScale),
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                    (AppearanceScreenshotTest.sampleImage(request.data.toString())
                        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
                },
            ) {
                NuvioTheme(appTheme = AppTheme.VIOLET, appearance = appearance) {
                    content(windowSize)
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

    private fun continueWatching(id: String, title: String, episodeTitle: String, image: String, progress: Float) =
        ContinueWatchingItem(
            parentMetaId = id,
            parentMetaType = "series",
            videoId = "$id:1:4",
            title = title,
            subtitle = "S1 E4",
            imageUrl = image,
            background = image,
            episodeThumbnail = image,
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = episodeTitle,
            resumePositionMs = (24 * 60_000L * progress).toLong(),
            durationMs = 24 * 60_000L,
            progressFraction = progress,
        )

    private val anime = MetaDetails(
        id = "tt-goblin",
        type = "series",
        name = "So What's Wrong with Getting Reborn as a Goblin?",
        background = LIGHT_BACKDROP,
        poster = LIGHT_BACKDROP,
        logo = LOGO,
        description = "Reborn as a goblin in a world of swords and sorcery, a man who knows how the story " +
            "goes sets out to survive the adventurers, the dungeon and his own village.",
        releaseInfo = "2026",
        imdbRating = "7.1",
        ageRating = "TV-14",
        runtime = "24m",
        genres = listOf("Animation", "Fantasy", "Comedy", "Action & Adventure"),
        cast = List(6) { MetaPerson(name = "Voice Actor Name $it", role = "Character $it") },
        director = listOf("Director Name"),
        writer = listOf("Writer Name"),
        videos = (1..2).flatMap { season ->
            (1..12).map { episode ->
                MetaVideo(
                    id = "tt-goblin:$season:$episode",
                    title = if (episode == 4) "A Very Long Episode Title That Has To Be Cut Off Somewhere Sensible" else "Episode $episode",
                    released = "2026-01-${episode.toString().padStart(2, '0')}T00:00:00.000Z",
                    thumbnail = if (episode % 2 == 0) DARK_THUMB else LIGHT_THUMB,
                    season = season,
                    episode = episode,
                    overview = "The goblins prepare for the adventurers' raid while the village argues about who leads.",
                    runtime = 24,
                )
            }
        },
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun appearances(): List<Array<Any>> = listOf(arrayOf(AppearanceMode.LIGHT), arrayOf(AppearanceMode.DARK))
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w325dp-h720dp-xxhdpi")
class WindowSize325x720ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "325x720")

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w325dp-h720dp-xxhdpi")
class WindowSize325x720Font130ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "325x720", 1.3f)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class WindowSize360x800ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "360x800")

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class WindowSize360x800Font130ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "360x800", 1.3f)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp-xhdpi")
class WindowSize411x914ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "411x914")

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h1333dp-mdpi")
class WindowSize600x1333ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "600x1333")

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h1333dp-mdpi")
class WindowSize600x1333Font130ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "600x1333", 1.3f)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1333dp-h600dp-mdpi")
class WindowSize1333x600ScreenshotTest(appearance: AppearanceMode) : WindowSizeScreenshotTest(appearance, "1333x600")
