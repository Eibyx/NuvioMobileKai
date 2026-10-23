package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

object StreamAutoPlayPolicy {
    /**
     * From Continue Watching the auto-play screen only appears when there is something specific to
     * try: the last link saved for this episode, or the binge group saved for the show. Otherwise
     * the stream list opens straight away.
     */
    fun continueWatchingHasAutoPlayTarget(
        settings: PlayerSettingsUiState,
        hasSavedLink: Boolean,
        hasBingeGroup: Boolean,
    ): Boolean =
        (settings.streamReuseLastLinkEnabled && hasSavedLink) ||
            (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup && hasBingeGroup)

    fun isEffectivelyEnabled(settings: PlayerSettingsUiState): Boolean {
        if (settings.streamReuseLastLinkEnabled) return true
        if (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup) return true

        return when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(settings.streamAutoPlayRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
