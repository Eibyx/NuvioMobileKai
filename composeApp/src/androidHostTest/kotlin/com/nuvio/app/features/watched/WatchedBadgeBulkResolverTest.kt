package com.nuvio.app.features.watched

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchedBadgeBulkResolverTest {

    private val fetched = mutableListOf<String>()
    private var now = 1_000_000L

    private fun meta(id: String) = MetaDetails(
        id = id,
        type = "series",
        name = id,
        videos = listOf(
            MetaVideo(id = "$id:1:1", title = "Episode 1", season = 1, episode = 1, released = "2026-01-01"),
            MetaVideo(id = "$id:1:2", title = "Episode 2", season = 1, episode = 2, released = "2026-01-08"),
        ),
    )

    private fun watched(id: String, episode: Int) = WatchedItem(
        id = id,
        type = "series",
        name = id,
        season = 1,
        episode = episode,
        markedAtEpochMs = 1L,
    )

    private suspend fun resolve(
        items: List<WatchedItem>,
        failing: Set<String> = emptySet(),
        scopeKey: String = "profile-1",
    ) = resolveWatchedBadgesBulk(
        watchedItems = items,
        progressEntries = emptyList(),
        todayIsoDate = "2026-11-10",
        scopeKey = scopeKey,
        nowMs = { now },
        fetchMeta = { id ->
            fetched += id
            if (id in failing) null else meta(id)
        },
    )

    @Before
    fun setUp() = WatchedBadgeResolutionMemory.clear()

    @After
    fun tearDown() = WatchedBadgeResolutionMemory.clear()

    @Test
    fun anUnchangedSeriesIsNotFetchedAgain(): Unit = runBlocking {
        val items = listOf(watched("tt1", 1), watched("tt2", 1))
        assertTrue(resolve(items))
        assertEquals(listOf("tt1", "tt2"), fetched)

        // Same watched state (e.g. a sync or coming back to the app): nothing to fetch.
        fetched.clear()
        assertTrue(resolve(items))
        assertEquals(emptyList(), fetched)

        // One more episode of tt2: only tt2 is fetched again.
        assertTrue(resolve(items + watched("tt2", 2)))
        assertEquals(listOf("tt2"), fetched)
    }

    @Test
    fun aFailingSeriesWaitsBeforeItIsRetried(): Unit = runBlocking {
        val items = listOf(watched("tt1", 1), watched("mal:5", 1))
        assertTrue(resolve(items, failing = setOf("mal:5")))
        fetched.clear()

        // Another watched-state change shortly after: the failed series is not fetched again.
        assertTrue(resolve(items + watched("tt1", 2), failing = setOf("mal:5")))
        assertEquals(listOf("tt1"), fetched)

        // After the pause it is tried again.
        fetched.clear()
        now += 11 * 60 * 1000L
        assertTrue(resolve(items + watched("tt1", 2)))
        assertEquals(listOf("mal:5"), fetched)
    }

    @Test
    fun anotherProfileStartsFromScratch(): Unit = runBlocking {
        val items = listOf(watched("tt1", 1))
        resolve(items)
        fetched.clear()
        resolve(items, scopeKey = "profile-2")
        assertEquals(listOf("tt1"), fetched)
    }
}
