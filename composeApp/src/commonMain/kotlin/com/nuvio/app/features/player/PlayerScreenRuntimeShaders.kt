package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.anilist.AniListTracker
import com.nuvio.app.features.player.shaders.ShaderContent
import com.nuvio.app.features.player.shaders.ShaderContentKind
import com.nuvio.app.features.player.shaders.ShaderController
import com.nuvio.app.features.player.shaders.ShaderSettingsRepository
import com.nuvio.app.features.tracking.parseAnimeIdRef
import kotlinx.coroutines.flow.drop

/** Picks the shader chain for what is playing and follows settings changes live. */
@Composable
internal fun PlayerScreenRuntime.BindShaderEffects() {
    val genres = playerMeta?.genres.orEmpty()
    LaunchedEffect(parentMetaId, activeVideoId, genres) {
        ShaderSettingsRepository.ensureLoaded()
        val hasAniListLink = runCatching { AniListTracker.knownMediaTitle(parentMetaId) != null }.getOrDefault(false)
        ShaderController.startPlayback(
            ShaderContent(
                contentId = parentMetaId,
                seriesId = parentMetaId,
                isAnime = ShaderContentKind.isAnime(
                    hasAnimeId = parseAnimeIdRef(parentMetaId) != null,
                    genres = genres,
                    hasAniListLink = hasAniListLink,
                ),
            ),
        )
    }
    LaunchedEffect(Unit) {
        // The first value is already in the plan; later ones are changes made while watching.
        ShaderSettingsRepository.settings.drop(1).collect { ShaderController.refresh() }
    }
    DisposableEffect(Unit) {
        onDispose { ShaderController.stopPlayback() }
    }
}
