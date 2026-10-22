package com.nuvio.app.features.player

import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib

/*
 * mpvlibAndroid (the libmpv build used by mpvRx) exposes libmpv as the process-wide [MPVLib] singleton,
 * where mpv-android-lib gave every view its own `MPV` instance. These shims keep the call sites shaped
 * like the per-view API so upstream changes to the libmpv engine still merge cleanly.
 */

/** The libmpv instance this view drives; there is only ever one. */
internal val BaseMPVView.mpv: MPVLib
    get() = MPVLib
