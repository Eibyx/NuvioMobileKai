package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview

data class PersonDetail(
    val tmdbId: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePhoto: String?,
    val knownFor: String?,
    val movieCredits: List<MetaPreview>,
    val tvCredits: List<MetaPreview>,
    /** A larger version of the profile photo for the full-width header. */
    val heroPhoto: String? = null,
    /** Every credit with its role, for the filmography tab. */
    val filmography: List<PersonCredit> = emptyList(),
)

/** One title in a person's filmography, for one role (acting or a crew department). */
data class PersonCredit(
    val item: MetaPreview,
    /** [ACTING] or the TMDB crew department ("Directing", "Camera", "Writing"...). */
    val department: String,
    /** The characters played, or the jobs done. */
    val roles: List<String>,
    val voteAverage: Double?,
    val voteCount: Int,
) {
    companion object {
        const val ACTING = "Acting"
    }
}

data class PersonImage(
    val thumbnail: String,
    val full: String,
)
