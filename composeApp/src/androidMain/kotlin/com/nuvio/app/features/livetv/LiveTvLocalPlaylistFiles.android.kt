package com.nuvio.app.features.livetv

import java.io.File

internal actual object LiveTvLocalPlaylistFiles {
    actual fun <T> useLines(path: String, block: (Sequence<String>) -> T): T =
        File(path).bufferedReader().useLines(block)

    actual fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
