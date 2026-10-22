package com.nuvio.app.features.tracking

import android.app.Application
import android.os.Looper
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "mal:22" (The Prince of Tennis on MyAnimeList) with only a Cinemeta-like addon installed, which
 * serves IMDb ids alone: the detail page and the streams must load through tt0466352.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnimeIdFallbackHostTest {
    private val originalLookup = AnimeIdFallback.lookupExternalIds
    private val originalEpisodes = AnimeIdFallback.lookupEpisodeMapping
    private val requestedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        PlayerSettingsStorage.initialize(context)
        AddonStorage.initialize(context)
        BingeGroupCacheStorage.initialize(context)
        AndroidAppUpdaterPlatform.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        AddonRepository.clearLocalState()
        MetaDetailsRepository.clear()
        StreamsRepository.clear()
        runBlocking { AnimeIdFallback.clearCache() }
        AnimeIdFallback.lookupExternalIds = { ref ->
            if (ref.contentId == "mal:22") AnimeExternalIds(imdb = "tt0466352") else null
        }
        AnimeIdFallback.lookupEpisodeMapping = { null }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                requestedPaths += path
                return when (path) {
                    "/manifest.json" -> MockResponse().setBody(
                        """
                        {"id":"imdb-only","name":"IMDb only","description":"","version":"1",
                         "resources":[{"name":"meta","types":["series"],"idPrefixes":["tt"]},
                                      {"name":"stream","types":["series"],"idPrefixes":["tt"]}],
                         "types":["series"],"catalogs":[],"idPrefixes":["tt"]}
                        """.trimIndent(),
                    )
                    "/meta/series/tt0466352.json" -> MockResponse().setBody(
                        """
                        {"meta":{"id":"tt0466352","type":"series","name":"The Prince of Tennis",
                         "videos":[
                           {"id":"tt0466352:0:1","title":"Special","season":0,"episode":1},
                           {"id":"tt0466352:1:1","title":"S1E1","season":1,"episode":1},
                           {"id":"tt0466352:1:2","title":"S1E2","season":1,"episode":2},
                           {"id":"tt0466352:1:3","title":"S1E3","season":1,"episode":3},
                           {"id":"tt0466352:2:1","title":"S2E1","season":2,"episode":1},
                           {"id":"tt0466352:2:2","title":"S2E2","season":2,"episode":2}]}}
                        """.trimIndent(),
                    )
                    "/stream/series/tt0466352:2:2.json" -> MockResponse().setBody(
                        """{"streams":[{"name":"Episode 5","url":"https://example.com/tennis-5.mkv"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        StreamsRepository.clear()
        MetaDetailsRepository.clear()
        AddonRepository.clearLocalState()
        PlayerSettingsRepository.clearLocalState()
        AnimeIdFallback.lookupExternalIds = originalLookup
        AnimeIdFallback.lookupEpisodeMapping = originalEpisodes
        runBlocking { AnimeIdFallback.clearCache() }
        server.shutdown()
    }

    @Test
    fun malIdWithNoAddonForItLoadsDetailsAndStreamsThroughImdb() = runBlocking {
        installAddon()

        val meta = assertNotNull(MetaDetailsRepository.fetch(type = "series", id = "mal:22", cacheResult = false))
        assertEquals("The Prince of Tennis", meta.name)
        assertTrue(requestedPaths.none { it.contains("mal:22") }, "the addon was asked for mal:22: $requestedPaths")

        // Resuming episode 5 from Continue Watching: absolute episode 5 is S2E2 in the IMDb show.
        StreamsRepository.load(type = "series", videoId = "mal:22:5", parentMetaId = "mal:22", season = 1, episode = 5)
        val state = awaitStreams()
        assertEquals("Episode 5", state.groups.flatMap { it.streams }.single().name)
        assertEquals(
            StreamsRepository.requestToken(type = "series", videoId = "mal:22:5", season = 1, episode = 5),
            state.requestToken,
            "the streams page asked for mal:22:5 and must receive the result",
        )
        assertTrue("/stream/series/tt0466352:2:2.json" in requestedPaths, requestedPaths.toString())
    }

    @Test
    fun malIdWithoutAnyEquivalentStaysUnavailable() = runBlocking {
        installAddon()

        assertNull(MetaDetailsRepository.fetch(type = "series", id = "mal:999999", cacheResult = false))
        assertTrue(requestedPaths.none { it.startsWith("/meta/") }, requestedPaths.toString())
    }

    private suspend fun installAddon() {
        AddonStorage.saveInstalledAddonUrls(ProfileRepository.activeProfileId, listOf(server.url("/manifest.json").toString()))
        AddonRepository.initialize()
        withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
    }

    private suspend fun awaitStreams() = withTimeout(10_000) {
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = StreamsRepository.uiState.value
            if (state.groups.any { it.streams.isNotEmpty() } && !state.isAnyLoading) return@withTimeout state
            if (state.emptyStateReason != null) error("No streams: ${state.emptyStateReason} after $requestedPaths")
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
