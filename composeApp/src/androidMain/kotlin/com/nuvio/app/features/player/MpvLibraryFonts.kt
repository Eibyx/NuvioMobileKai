package com.nuvio.app.features.player

import android.content.Context
import com.nuvio.app.core.logging.InAppLogger
import java.io.File

/**
 * mpv's `sub-fonts-dir` with the fonts of the user's subtitle folders.
 *
 * In mpv 0.41 (the app's libmpv, `mpv v0.41.0-dev`) every subtitle decoder builds its own libass
 * library when it is created (`sd_ass.c` `assobjects_init` → `mp_ass_init` → `ass_set_fonts_dir`,
 * reading `sub-fonts-dir` at that moment) and libass reads every font of that directory. A decoder
 * is created by `sub-add` and whenever a track is selected again. So the active directory holds
 * the fonts mpv always had there ([SubtitleFontFiles], the "Font directory" setting) plus the fonts
 * of the folder subtitle about to be added, and it is rewritten before each `sub-add` and each
 * `loadfile`: no font of another episode stays.
 *
 * Files are hard links where the file system allows (no extra space), copies otherwise, and each
 * appears through a rename, so mpv never reads half a font. Ported from NuvioArcKai (GPL-3.0).
 */
internal object MpvLibraryFonts {
    private const val TAG = "Subtitles/Folders"
    private const val ACTIVE_DIR = "mpv-sub-fonts-active"
    private const val FOLDER_FONT_PREFIX = "kai-lib-"

    fun activeDir(context: Context): File = File(context.noBackupFilesDir, ACTIVE_DIR)

    /**
     * Makes the active directory hold the base fonts plus the fonts registered for [subtitleUrl]
     * (none for any other subtitle). Never throws: on failure mpv keeps what is there, and when even
     * the active directory cannot be made the base directory is returned, as before.
     */
    @Synchronized
    fun prepare(context: Context, subtitleUrl: String?): String? {
        val base = SubtitleFontFiles.directoryPath()?.let(::File)
        val dir = activeDir(context)
        val result = runCatching {
            check(dir.isDirectory || dir.mkdirs())
            val wanted = LinkedHashMap<String, File>()
            base?.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(".tmp") && hasFontFileExtension(it.name) }
                .forEach { wanted[it.name] = it }
            LibraryFontRegistry.fontsForUrl(subtitleUrl).forEachIndexed { index, font ->
                val safe = font.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)
                wanted["$FOLDER_FONT_PREFIX$index-$safe"] = File(font.path)
            }
            dir.listFiles().orEmpty().forEach { existing ->
                if (existing.name !in wanted || existing.name.endsWith(".tmp")) existing.delete()
            }
            wanted.forEach { (name, source) ->
                if (!source.isFile) return@forEach
                val target = File(dir, name)
                if (target.isFile && target.length() == source.length() && target.lastModified() >= source.lastModified()) return@forEach
                val temporary = File(dir, "$name.tmp")
                temporary.delete()
                runCatching { android.system.Os.link(source.absolutePath, temporary.absolutePath) }
                // A link the file system refused (or silently skipped) falls back to a copy.
                if (!temporary.isFile || temporary.length() != source.length()) source.copyTo(temporary, overwrite = true)
                check(temporary.renameTo(target)) { "rename failed" }
            }
            dir.absolutePath
        }
        return result.getOrElse { error ->
            InAppLogger.warn(TAG, "mpv font directory not updated: ${error::class.java.simpleName}")
            if (dir.isDirectory) dir.absolutePath else base?.absolutePath
        }
    }
}
