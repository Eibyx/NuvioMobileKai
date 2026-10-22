package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger

private const val LOCAL_SUBTITLE_ID_PREFIX = "local:"

/** A subtitle file the user added from the device (see [ImportedSubtitle]). */
internal val AddonSubtitle.isImportedFile: Boolean
    get() = id.startsWith(LOCAL_SUBTITLE_ID_PREFIX)

/** The files added for the video that is playing now; another episode starts without them. */
internal val PlayerScreenRuntime.currentLocalSubtitles: List<AddonSubtitle>
    get() = if (localSubtitlesVideoId == activeVideoId) localSubtitles else emptyList()

/**
 * Adds a picked subtitle file to the list under its own name and selects it. mpv gets the file as
 * picked (it detects the encoding), ExoPlayer the UTF-8 copy. The same file twice is one entry.
 */
internal fun PlayerScreenRuntime.addImportedSubtitle(imported: ImportedSubtitle, sourceName: String) {
    val useRawFile = playerController?.playbackEngine == AndroidPlaybackEngine.Libmpv
    val entry = imported.toAddonSubtitle(useRawFile = useRawFile, sourceName = sourceName)
    val current = currentLocalSubtitles
    val alreadyAdded = current.any { it.id == entry.id }
    localSubtitlesVideoId = activeVideoId
    localSubtitles = listOf(entry) + current.filterNot { it.id == entry.id }
    InAppLogger.info(
        "Player/Subtitles",
        if (alreadyAdded) {
            "External subtitle ${entry.display} was already added; selecting it"
        } else {
            "Added external subtitle ${entry.display} for ${if (useRawFile) "libmpv" else "ExoPlayer"}"
        },
    )
    selectAddonSubtitle(entry)
}

/** The user picked an addon subtitle, a stream subtitle or a file they added. */
internal fun PlayerScreenRuntime.selectAddonSubtitle(addon: AddonSubtitle) {
    clearAutoSubtitleState()
    isUserExplicitSubtitleSelection = true
    selectedAddonSubtitleId = addon.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    preferredSubtitleSelectionApplied = true
    if (addon.isImportedFile) {
        // Not remembered for later (the copy lives in the cache) and not sent to AutoSync, which
        // fetches subtitles over HTTP. The current delay and style still apply.
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
        playerController?.setSubtitleUri(addon.url)
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    } else {
        persistAddonSubtitlePreference(addon)
        attachSelectedAddonSubtitleWithAutoSync(addon.url) // AutoSync hook
    }
    if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
}
