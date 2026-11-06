package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class FileDefaultSubtitleTrackTest {
    private val japanese = AudioTrack(index = 0, id = "1", label = "Japanese", language = "jpn", isSelected = true)
    private val tracks = listOf(
        SubtitleTrack(index = 0, id = "2", label = "Signs", language = "eng", isForced = true, isDefault = true),
        SubtitleTrack(index = 1, id = "3", label = "Full Subtitles", language = null, isDefault = true),
        SubtitleTrack(index = 2, id = "4", label = "Other", language = "chi"),
    )

    @Test
    fun theFilesDefaultTrackShowsWhenNothingIsInThePreferredLanguage() {
        assertEquals(1, findFileDefaultSubtitleTrackIndex(tracks, japanese, listOf("es")))
    }

    @Test
    fun notWhenTheAudioIsAlreadyInThePreferredLanguage() {
        val spanish = AudioTrack(index = 0, id = "1", label = "Español", language = "spa", isSelected = true)
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks, spanish, listOf("es")))
    }

    @Test
    fun notWhenSubtitlesAreTurnedOffOrNoTrackIsDefault() {
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks, japanese, emptyList()))
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks.map { it.copy(isDefault = false) }, japanese, listOf("es")))
    }
}
