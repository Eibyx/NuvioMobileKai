package com.nuvio.app.features.anilist

import kotlin.test.Test
import kotlin.test.assertEquals

class AniListLinkStatusTest {
    private val keys = listOf("tt2560140|s1", "tt2560140|s3")

    @Test
    fun notLinkedYetIsPending() {
        assertEquals(AniListLinkStatus.Pending, aniListLinkStatus(keys, AniListQueueState()))
    }

    @Test
    fun linkedSeasonsListTheirEntries() {
        val state = AniListQueueState(
            links = listOf(
                AniListLink(
                    "tt2560140|s3",
                    listOf(AniListLinkPart(99147, "AoT S3"), AniListLinkPart(104578, "AoT S3 Part 2", 13)),
                    manual = false,
                ),
            ),
        )
        assertEquals(AniListLinkStatus.Linked(listOf("AoT S3 + AoT S3 Part 2")), aniListLinkStatus(keys, state))
    }

    @Test
    fun aMissingMatchWinsUntilTheSeasonIsLinked() {
        val state = AniListQueueState(
            links = listOf(AniListLink("tt2560140|s1", listOf(AniListLinkPart(16498, "AoT")), manual = false)),
            unmatched = listOf("tt2560140|s3"),
        )
        assertEquals(AniListLinkStatus.NotFound, aniListLinkStatus(keys, state))
    }
}
