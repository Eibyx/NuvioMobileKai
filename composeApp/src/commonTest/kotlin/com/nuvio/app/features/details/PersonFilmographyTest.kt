package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonFilmographyTest {

    private fun credit(
        id: Int,
        department: String = PersonCredit.ACTING,
        type: String = "movie",
        date: String? = null,
        rating: Double? = null,
        votes: Int = 0,
        popularity: Double = 0.0,
    ) = PersonCredit(
        item = MetaPreview(
            id = "tmdb:$id",
            type = type,
            name = "Title $id",
            rawReleaseDate = date,
            popularity = popularity,
        ),
        department = department,
        roles = emptyList(),
        voteAverage = rating,
        voteCount = votes,
    )

    @Test
    fun date_sort_puts_newest_first_and_undated_last() {
        val credits = listOf(
            credit(1, date = "2010-07-16"),
            credit(2, date = null),
            credit(3, date = "2023-10-20"),
        )
        val sorted = filterPersonFilmography(credits, null, PersonMediaFilter.All, PersonSort.Date)
        assertEquals(listOf("tmdb:3", "tmdb:1", "tmdb:2"), sorted.map { it.item.id })
    }

    @Test
    fun rating_sort_puts_titles_with_few_votes_after_rated_ones() {
        val credits = listOf(
            credit(1, rating = 9.8, votes = 2),
            credit(2, rating = 8.8, votes = 30000),
            credit(3, rating = 7.9, votes = 20000),
        )
        val sorted = filterPersonFilmography(credits, null, PersonMediaFilter.All, PersonSort.Rating)
        assertEquals(listOf("tmdb:2", "tmdb:3", "tmdb:1"), sorted.map { it.item.id })
    }

    @Test
    fun a_role_shows_only_its_credits_and_all_roles_show_each_title_once() {
        val credits = listOf(
            credit(1, department = "Directing"),
            credit(1, department = PersonCredit.ACTING),
            credit(2, department = PersonCredit.ACTING, type = "series"),
        )
        assertEquals(
            listOf("tmdb:1"),
            filterPersonFilmography(credits, "Directing", PersonMediaFilter.All, PersonSort.Date).map { it.item.id },
        )
        assertEquals(2, filterPersonFilmography(credits, null, PersonMediaFilter.All, PersonSort.Date).size)
        assertEquals(
            listOf("tmdb:2"),
            filterPersonFilmography(credits, null, PersonMediaFilter.Series, PersonSort.Date).map { it.item.id },
        )
    }

    @Test
    fun default_role_is_what_the_person_is_known_for() {
        val credits = listOf(
            credit(1, department = PersonCredit.ACTING),
            credit(2, department = PersonCredit.ACTING),
            credit(3, department = "Camera"),
        )
        assertEquals("Camera", defaultPersonDepartment(credits, knownFor = "Camera"))
        assertEquals(PersonCredit.ACTING, defaultPersonDepartment(credits, knownFor = null))
        assertNull(defaultPersonDepartment(credits.take(2), knownFor = "Acting"))
    }
}
