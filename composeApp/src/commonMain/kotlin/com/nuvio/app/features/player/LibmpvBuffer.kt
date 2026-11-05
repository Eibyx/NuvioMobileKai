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
        // Always the network cache, and seeks inside what is already loaded (forwards or back)
        // are served from it instead of starting the download again.
        add("cache" to "yes")
        add("demuxer-seekable-cache" to "yes")
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

/** A stretch of the file mpv has loaded, in seconds. */
internal data class LibmpvCachedRange(val startSec: Double, val endSec: Double)

/**
 * One line for the log: where playback is, how far ahead it is loaded, how much that takes and
 * how fast it downloads. With [seekTargetSec], whether the seek lands inside what is loaded.
 */
internal fun describeLibmpvCache(
    reason: String,
    positionSec: Double?,
    cacheEndSec: Double?,
    forwardBytes: Long?,
    inputBytesPerSec: Long?,
    ranges: List<LibmpvCachedRange>,
    seekTargetSec: Double? = null,
): String = buildString {
    append("cache ").append(reason)
    positionSec?.let { append(" pos=").append(it.toInt()).append("s") }
    if (positionSec != null && cacheEndSec != null) {
        append(" ahead=").append((cacheEndSec - positionSec).coerceAtLeast(0.0).toInt()).append("s")
    }
    forwardBytes?.let { append(" fw=").append(it / MIB).append("MB") }
    inputBytesPerSec?.let { append(" rate=").append(oneDecimal(it.toDouble() / MIB)).append("MB/s") }
    append(" ranges=").append(ranges.size)
    ranges.forEach { append(" [").append(it.startSec.toInt()).append("-").append(it.endSec.toInt()).append("]") }
    seekTargetSec?.let { target ->
        val inside = ranges.any { target >= it.startSec && target <= it.endSec }
        append(" seek=").append(target.toInt()).append("s ").append(if (inside) "cached" else "not-cached")
    }
}

private const val MIB = 1024L * 1024L

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
