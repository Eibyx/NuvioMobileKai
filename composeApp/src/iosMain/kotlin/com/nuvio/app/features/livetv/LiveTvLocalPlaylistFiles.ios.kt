package com.nuvio.app.features.livetv

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

// The iOS picker still hands over the text itself, so these only see copies made elsewhere.
@OptIn(ExperimentalForeignApi::class)
internal actual object LiveTvLocalPlaylistFiles {
    actual fun <T> useLines(path: String, block: (Sequence<String>) -> T): T {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null).orEmpty()
        return block(text.lineSequence())
    }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
