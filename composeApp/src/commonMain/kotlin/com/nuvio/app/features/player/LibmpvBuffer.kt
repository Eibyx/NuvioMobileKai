package com.nuvio.app.features.player

/**
 * Bounds for the mpv buffer the user types in: in RAM it stays small enough that Android does not
 * kill the app; on disk it is bounded by the free space, keeping some of it spare.
 */
internal object LibmpvBufferLimits {
    const val DEFAULT_FORWARD_SECONDS = 600
    const val DEFAULT_FORWARD_MB = 1_024
    const val DEFAULT_BACK_MB = 300

    const val MIN_FORWARD_SECONDS = 10
    const val MAX_FORWARD_SECONDS = 7_200
    const val MIN_FORWARD_MB = 16
    const val MAX_RAM_FORWARD_MB = 2_048
    const val MAX_RAM_BACK_MB = 1_024
    const val MAX_DISK_FORWARD_MB = 65_536
    const val MAX_DISK_BACK_MB = 32_768
    /** Left free on the storage when the buffer lives there. */
    const val DISK_SPARE_MB = 2_048

    fun forwardMbRange(onDisk: Boolean): IntRange =
        MIN_FORWARD_MB..(if (onDisk) MAX_DISK_FORWARD_MB else MAX_RAM_FORWARD_MB)

    fun backMbRange(onDisk: Boolean): IntRange =
        0..(if (onDisk) MAX_DISK_BACK_MB else MAX_RAM_BACK_MB)

    val forwardSecondsRange: IntRange = MIN_FORWARD_SECONDS..MAX_FORWARD_SECONDS
}

/** The mpv options for the buffer, and the sizes they come to (for the log). */
internal data class LibmpvBufferPlan(
    val options: List<Pair<String, String>>,
    val forwardSeconds: Int?,
    val forwardMb: Int,
    val backMb: Int,
    val onDisk: Boolean,
)

/**
 * mpv's buffer: without the custom buffer, [defaultMb] ahead and behind as before. With it, up to
 * [PlayerSettingsUiState.libmpvForwardBufferSeconds] ahead (`cache-secs`) but never more than the
 * forward size in MB, and the back size in MB (mpv only bounds the back buffer by size). On disk
 * (`cache-on-disk`) in [cacheDir] when there is room for it, otherwise in RAM.
 */
internal fun libmpvBufferPlan(
    settings: PlayerSettingsUiState,
    defaultMb: Int,
    cacheDir: String?,
    freeDiskMb: Long?,
): LibmpvBufferPlan {
    if (!settings.libmpvCustomBufferEnabled) {
        return LibmpvBufferPlan(
            options = listOf(
                "demuxer-max-bytes" to "${defaultMb}MiB",
                "demuxer-max-back-bytes" to "${defaultMb}MiB",
            ),
            forwardSeconds = null,
            forwardMb = defaultMb,
            backMb = defaultMb,
            onDisk = false,
        )
    }
    val seconds = settings.libmpvForwardBufferSeconds.coerceIn(LibmpvBufferLimits.forwardSecondsRange)
    val diskBudgetMb = freeDiskMb?.minus(LibmpvBufferLimits.DISK_SPARE_MB)
    var onDisk = settings.libmpvBufferOnDisk && cacheDir != null &&
        diskBudgetMb != null && diskBudgetMb >= LibmpvBufferLimits.MIN_FORWARD_MB
    var forward = settings.libmpvForwardBufferMaxMb.coerceIn(LibmpvBufferLimits.forwardMbRange(onDisk))
    var back = settings.libmpvBackBufferMb.coerceIn(LibmpvBufferLimits.backMbRange(onDisk))
    if (onDisk && diskBudgetMb != null && forward + back > diskBudgetMb) {
        // Not enough free space for both: both shrink in proportion.
        val factor = diskBudgetMb.toDouble() / (forward + back)
        forward = (forward * factor).toInt().coerceAtLeast(LibmpvBufferLimits.MIN_FORWARD_MB)
        back = (back * factor).toInt().coerceAtLeast(0)
    }
    if (!onDisk) {
        forward = forward.coerceIn(LibmpvBufferLimits.forwardMbRange(onDisk = false))
        back = back.coerceIn(LibmpvBufferLimits.backMbRange(onDisk = false))
    }
    val options = buildList {
        add("cache-secs" to "$seconds")
        add("demuxer-readahead-secs" to "$seconds")
        add("demuxer-max-bytes" to "${forward}MiB")
        add("demuxer-max-back-bytes" to "${back}MiB")
        if (onDisk) {
            add("cache-on-disk" to "yes")
            add("demuxer-cache-dir" to cacheDir!!)
        }
    }
    return LibmpvBufferPlan(options, seconds, forward, back, onDisk)
}
