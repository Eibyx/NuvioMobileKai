package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.subtitlelibrary.LibraryFont
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonts of the user's subtitle folders: subtitle `file://` URL → its font files (only the ones the
 * script uses, from its own folder). Read by each engine when that subtitle is loaded:
 * - mpv: [MpvLibraryFonts] writes them into the active `sub-fonts-dir` before `sub-add`;
 * - ExoPlayer + libass: `CompatAssSubtitleParserFactory.create(format)` adds them before
 *   `AssHandler.createTrack`, matched by `format.id` (the sidecar's `addonSubtitleTrackId(url)`);
 * - ExoPlayer + libmpv's libass: passed with the directory fonts when the subtitle is shown.
 *
 * Ported from NuvioArcKai's LibraryFontRegistry (GPL-3.0), keyed by URL so all three can use it.
 */
internal object LibraryFontRegistry {
    private const val TAG = "Subtitles/Folders"
    private val fontsByUrl = ConcurrentHashMap<String, List<LibraryFont>>()

    fun register(url: String, fonts: List<LibraryFont>) {
        if (fonts.isEmpty()) fontsByUrl.remove(url) else fontsByUrl[url] = fonts
    }

    /** New episode or stream: nothing of the previous one may reach the next. */
    fun clear() = fontsByUrl.clear()

    fun isEmpty(): Boolean = fontsByUrl.isEmpty()

    fun fontsForUrl(url: String?): List<LibraryFont> = url?.let { fontsByUrl[it] }.orEmpty()

    /** By ExoPlayer's track id; later track groups may carry a `<n>:` prefix, so ids match by suffix too. */
    internal fun fontsForTrackId(formatId: String?): List<LibraryFont> {
        if (formatId.isNullOrEmpty() || fontsByUrl.isEmpty()) return emptyList()
        return fontsByUrl.entries.firstOrNull { (url, _) ->
            val trackId = addonSubtitleTrackId(url)
            formatId == trackId || formatId.endsWith(":$trackId") || formatId.endsWith(trackId)
        }?.value.orEmpty()
    }

    /**
     * Hands the fonts registered for [formatId] to [addFont] one at a time, each read just before
     * and dropped right after, so at most one font is held here. Returns how many were added.
     */
    fun addFontsFor(formatId: String?, addFont: (name: String, data: ByteArray) -> Unit): Int {
        val fonts = fontsForTrackId(formatId)
        if (fonts.isEmpty()) return 0
        var added = 0
        for (font in fonts) {
            val data = runCatching { File(font.path).readBytes() }.getOrNull() ?: continue
            runCatching { addFont(font.name, data) }.onSuccess { added++ }
        }
        InAppLogger.info(TAG, "libass: $added/${fonts.size} folder fonts added for an external subtitle")
        return added
    }
}
