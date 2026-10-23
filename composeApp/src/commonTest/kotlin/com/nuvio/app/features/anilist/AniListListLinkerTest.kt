package com.nuvio.app.features.anilist

import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linking the AniList list in the background, so it imports without opening each detail page. */
class AniListListLinkerTest {
    private class MemoryStorage : AniListQueueStorage {
        val saved = mutableMapOf<Int, String>()
        var saves = 0
        override fun load(profileId: Int): String? = saved[profileId]
        override fun save(profileId: Int, payload: String) {
            saved[profileId] = payload
            saves++
        }
        override fun lastProfile(): Int? = null
        override fun saveLastProfile(profileId: Int) = Unit
    }

    private val mushoku = "tt13293588"
    private var clock = 1_000_000_000L
    private val storage = MemoryStorage()
    private val delivered = mutableListOf<AniListPendingSend>()
    private val armCalls = mutableListOf<String>()
    private val linkCalls = mutableListOf<TrackingMediaReference>()
    private val pauses = mutableListOf<Long>()
    private var armFailure: (String) -> Throwable? = { null }

    /** ARM's season table: Mushoku Tensei's four parts, and one show per other imdb id. */
    private fun arm(imdb: String): List<ArmImdbEntry> = when (imdb) {
        mushoku -> listOf(
            ArmImdbEntry(108465, tvdbSeason = 1, media = "TV"),
            ArmImdbEntry(127720, tvdbSeason = 1, media = "TV"),
            ArmImdbEntry(146065, tvdbSeason = 2, media = "TV"),
            ArmImdbEntry(166873, tvdbSeason = 2, media = "TV"),
        )
        "tt0388629" -> listOf(ArmImdbEntry(21, tvdbSeason = null, media = "TV"))
        else -> listOf(ArmImdbEntry(imdb.removePrefix("tt").toLong(), tvdbSeason = 1, media = "TV"))
    }

    /** The same plan the resolver makes from ARM's table. */
    private fun planFor(reference: TrackingMediaReference): AniListLink? {
        val imdb = reference.ids.imdb ?: return null
        val season = reference.episode?.season ?: 1
        val candidates = arm(imdb).map { AniListSeasonCandidate(it.anilist, "TV", it.tvdbSeason, if (it.anilist == 108465L) 11 else 12, it.anilist) }
        val plan = aniListLinkPlan(candidates, season, isMovie = false) ?: return null
        return AniListLink(aniListSendKey(imdb, "series", season), plan.parts, manual = false, absolute = plan.absolute)
    }

    private fun TestScope.queue() = AniListSendQueue(
        storage = storage,
        deliver = { _, item ->
            delivered += item
            AniListDelivery.Sent(item.title, item.episode ?: 1)
        },
        scope = backgroundScope,
        now = { clock },
    )

    private fun linker(queue: AniListSendQueue, snapshot: () -> AniListSyncSnapshot?, profile: Int? = 1) = AniListListLinker(
        snapshot = snapshot,
        profile = { profile },
        queue = queue,
        armRows = { imdb ->
            armCalls += imdb
            armFailure(imdb)?.let { throw it }
            arm(imdb)
        },
        link = { reference ->
            linkCalls += reference
            planFor(reference)
        },
        now = { clock },
        pause = { pauses += it },
    )

    private fun entry(mediaId: Long, status: String, progress: Int = 12, format: String = "TV") = AniListEntry(
        id = mediaId,
        mediaId = mediaId,
        status = status,
        progress = progress,
        media = AniListMedia(id = mediaId, format = format, episodes = 12, title = AniListTitle(english = "Anime $mediaId")),
    )

    private fun mapping(mediaId: Long, imdb: String?, mal: Long? = null, kitsu: Long? = null, resolved: Boolean = true) =
        AniListMapping(anilist = mediaId, mal = mal, kitsu = kitsu, imdb = imdb, resolvedAtEpochMs = if (resolved) 1L else 0L)

    private val mushokuList = AniListSyncSnapshot(
        viewerId = 7,
        entries = listOf(
            entry(108465, AniListListStatus.COMPLETED, 11),
            entry(127720, AniListListStatus.COMPLETED),
            entry(146065, AniListListStatus.CURRENT, 5),
            entry(1, AniListListStatus.PLANNING, 0),
            entry(2, AniListListStatus.DROPPED, 3),
            entry(3, AniListListStatus.COMPLETED, 1, format = "MOVIE"),
        ),
        mappings = mapOf(
            108465L to mapping(108465, mushoku, mal = 39535, kitsu = 42422),
            127720L to mapping(127720, mushoku),
            146065L to mapping(146065, mushoku),
            1L to mapping(1, "tt0000001"),
            2L to mapping(2, "tt0000002"),
            3L to mapping(3, "tt0000003"),
        ),
    )

    @Test
    fun onlySeenEntriesThatAreNotMoviesAreLinked() {
        val ids = aniListListEntries(mushokuList).map { it.mediaId }
        assertEquals(listOf(108465L, 127720L, 146065L), ids)
        // Waiting for the list sync to resolve its ids: not yet.
        val unresolved = mushokuList.copy(mappings = mushokuList.mappings + (146065L to mapping(146065, mushoku, resolved = false)))
        assertEquals(listOf(108465L, 127720L), aniListEntriesToLink(unresolved, emptySet(), emptyMap(), clock).map { it.mediaId })
        // Linked already, or looked up this week: not again; after a week, again.
        assertEquals(listOf(146065L), aniListEntriesToLink(mushokuList, setOf(108465L), mapOf(127720L to clock - 1_000), clock).map { it.mediaId })
        assertEquals(3, aniListEntriesToLink(mushokuList, emptySet(), mapOf(127720L to clock - AniListListLinker.RECHECK_MS), clock).size)
        assertEquals(listOf(1, 2), armSeasonsOf(listOf(ArmImdbEntry(5, 2, null), ArmImdbEntry(4, 1, null), ArmImdbEntry(6, 3, null), ArmImdbEntry(7, null, null)), setOf(4, 5, 7)))
    }

    @Test
    fun mushokuTenseiIsLinkedAndImportedWithoutOpeningIt() = runTest {
        val queue = queue()
        queue.open(1, process = false)
        val linker = linker(queue, { mushokuList })
        linker.run()

        // One ARM lookup for the show, one link per season with entries on the list.
        assertEquals(listOf(mushoku), armCalls)
        assertEquals(listOf(1, 2), linkCalls.map { it.episode?.season })
        val seasonOne = assertNotNull(queue.linkFor("$mushoku|s1"))
        assertEquals(listOf(108465L to 1, 127720L to 12), seasonOne.parts.map { it.mediaId to it.firstEpisode })
        assertNotNull(queue.linkFor("$mushoku|s2"))
        // Catalogs with anime ids get their link too, without a lookup.
        assertEquals(listOf(108465L), queue.linkFor("mal:39535|s1")?.parts?.map { it.mediaId })
        assertEquals(listOf(108465L), queue.linkFor("kitsu:42422|s1")?.parts?.map { it.mediaId })
        // Nothing for the planned, dropped or movie entries.
        assertNull(queue.linkFor("tt0000001|s1"))
        assertNull(queue.linkFor("tt0000003|movie"))

        // What the importer makes of it: all of season one watched.
        val entries = mushokuList.entries.associateBy(AniListEntry::mediaId)
        val watched = importLinkedSeason(seasonOne, 1, seasonOne.parts.associate { it.mediaId to entries[it.mediaId] })
        assertEquals((1..23).map { SeasonEpisode(1, it) }, watched.flatMap { it.episodes })

        // Read only: nothing queued for or sent to AniList.
        assertTrue(queue.state.value.pending.isEmpty())
        assertTrue(delivered.isEmpty())

        val progress = linker.progress.value
        assertFalse(progress.running)
        assertEquals(Triple(3, 3, 3), Triple(progress.done, progress.total, progress.linked))

        // Cached: the next sync looks nothing up.
        armCalls.clear()
        linkCalls.clear()
        linker.run()
        assertTrue(armCalls.isEmpty() && linkCalls.isEmpty())
    }

    @Test
    fun aLongListGoesInBatchesWithAPauseBetweenShows() = runTest {
        val queue = queue()
        queue.open(1, process = false)
        val shows = (101L..125L)
        val list = AniListSyncSnapshot(
            viewerId = 7,
            entries = shows.map { entry(it, AniListListStatus.COMPLETED) },
            mappings = shows.associateWith { mapping(it, "tt$it") },
        )
        val savesBefore = storage.saves
        linker(queue, { list }).run()
        assertEquals(25, armCalls.size)
        assertEquals(List(25) { AniListListLinker.REQUEST_PAUSE_MS }, pauses)
        // One write per batch of 10 shows, not one per show.
        assertEquals(3, storage.saves - savesBefore)
        assertEquals(25, queue.state.value.links.size)
    }

    @Test
    fun aRefusalStopsTheRunAndTheNextSyncCarriesOn() = runTest {
        val queue = queue()
        queue.open(1, process = false)
        val shows = (201L..205L)
        val list = AniListSyncSnapshot(
            viewerId = 7,
            entries = shows.map { entry(it, AniListListStatus.CURRENT, 3) },
            mappings = shows.associateWith { mapping(it, "tt$it") },
        )
        armFailure = { imdb -> if (imdb == "tt203") AniListTransientException("ARM answered 429") else null }
        val linker = linker(queue, { list })
        linker.run()
        assertEquals(listOf("tt201", "tt202", "tt203"), armCalls)
        assertEquals(setOf("tt201|s1", "tt202|s1"), queue.state.value.links.map { it.key }.toSet())
        assertFalse(linker.progress.value.running)
        assertEquals(2, linker.progress.value.done)

        armFailure = { null }
        armCalls.clear()
        linker.run()
        assertEquals(listOf("tt203", "tt204", "tt205"), armCalls)
        assertEquals(5, linker.progress.value.linked)
    }

    @Test
    fun aShowWithoutAMatchIsTriedAgainAfterAWeek() = runTest {
        val queue = queue()
        queue.open(1, process = false)
        // One Piece: one entry for the whole show, left to the detail page (it needs the episode list).
        val list = AniListSyncSnapshot(7, listOf(entry(21, AniListListStatus.CURRENT, 1100)), mapOf(21L to mapping(21, "tt0388629")))
        val linker = linker(queue, { list })
        linker.run()
        assertTrue(queue.state.value.links.isEmpty())
        assertEquals(Triple(1, 1, 0), linker.progress.value.let { Triple(it.done, it.total, it.linked) })
        armCalls.clear()
        linker.run()
        assertTrue(armCalls.isEmpty())
        clock += AniListListLinker.RECHECK_MS
        linker.run()
        assertEquals(listOf("tt0388629"), armCalls)
    }

    @Test
    fun aLinkAlreadyThereIsKeptAndAnotherProfileIsLeftAlone() = runTest {
        val queue = queue()
        queue.open(1, process = false)
        val manual = AniListLink("$mushoku|s2", listOf(AniListLinkPart(146065, "By hand")), manual = true)
        queue.setLink(manual)
        linker(queue, { mushokuList }).run()
        assertEquals(manual.parts, queue.linkFor("$mushoku|s2")?.parts)
        assertTrue(queue.linkFor("$mushoku|s2")!!.manual)

        // The open queue belongs to profile 1: a run for profile 2 does nothing.
        val other = queue()
        other.open(1, process = false)
        armCalls.clear()
        linker(other, { mushokuList }, profile = 2).run()
        assertTrue(armCalls.isEmpty() && linkCalls.size == 1)
    }
}
