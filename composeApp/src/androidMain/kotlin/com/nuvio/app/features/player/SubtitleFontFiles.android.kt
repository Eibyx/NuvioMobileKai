package com.nuvio.app.features.player

import android.content.Context
import com.nuvio.app.core.logging.InAppLogger
import java.io.File

/**
 * The folder mpv reads with `sub-fonts-dir`: the fonts copied from the user's font directory, for
 * ASS subtitles that name fonts the file does not carry.
 */
internal object SubtitleFontFiles {
    private var directory: File? = null

    fun initialize(context: Context) {
        directory = File(context.applicationContext.filesDir, "subtitle_fonts")
        migrateImportedFonts()
    }

    private fun dir(): File? = directory?.apply { mkdirs() }

    fun directoryPath(): String? = dir()?.absolutePath

    /**
     * Fonts imported one by one before 1.0.6 lived here too; they move to the subtitle appearance
     * fonts (SubtitleFonts), which both players use now. Directory fonts (dir_…) stay.
     */
    private fun migrateImportedFonts() {
        val files = dir()?.listFiles()?.filter { file ->
            file.isFile && !file.name.startsWith(DIRECTORY_FONT_PREFIX) && hasFontFileExtension(file.name)
        }.orEmpty()
        files.forEach { file ->
            runCatching { SubtitleFonts.import(file.name, file.readBytes()) }
                .onSuccess { file.delete() }
                .onFailure { error ->
                    InAppLogger.warn("Player/SubtitleFonts", "Could not move imported font ${file.name}: ${error.message}")
                }
        }
    }
}
