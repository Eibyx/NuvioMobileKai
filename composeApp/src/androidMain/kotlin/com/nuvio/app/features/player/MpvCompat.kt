package com.nuvio.app.features.player

import android.util.Log
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/*
 * mpvlibAndroid (the libmpv build used by mpvRx) exposes libmpv as the process-wide [MPVLib] singleton,
 * where mpv-android-lib gave every view its own `MPV` instance. These shims keep the call sites shaped
 * like the per-view API so upstream changes to the libmpv engine still merge cleanly.
 */

/** The libmpv instance this view drives; there is only ever one. */
internal val BaseMPVView.mpv: MPVLib
    get() = MPVLib

/**
 * Hands the single libmpv instance from one player view to the next.
 *
 * Every libmpv call runs on [dispatcher], so a view being released and a view taking over are
 * serialised: the old instance is always destroyed before the new one is created, and a late
 * release from an old view never destroys the instance a newer view owns.
 */
internal object MpvSession {
    fun interface Owner {
        /** Another view took libmpv over: stop issuing calls and drop the surface callbacks. */
        fun onPreempted()
    }

    private const val TAG = "MpvSession"
    private const val ACQUIRE_TIMEOUT_MS = 3_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val lock = Any()
    private var owner: Owner? = null

    /**
     * Makes [newOwner] the owner of libmpv, destroying the previous owner's instance first.
     * Call it on the main thread right before [BaseMPVView.initialize].
     */
    fun acquire(newOwner: Owner) {
        val previous = synchronized(lock) { owner.also { owner = newOwner } }
            ?.takeIf { it !== newOwner }
        previous?.onPreempted()
        // Also waits for queued libmpv work, including a release still destroying an older instance.
        val handOver = executor.submit {
            if (previous != null) {
                Log.i(TAG, "destroying the previous libmpv instance before creating a new one")
                destroyNow()
            }
        }
        try {
            handOver.get(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw IllegalStateException("previous libmpv instance is still shutting down", error)
        }
    }

    /** Destroys libmpv if [releasingOwner] still owns it. */
    fun release(releasingOwner: Owner) {
        executor.execute {
            val owned = synchronized(lock) {
                (owner === releasingOwner).also { if (it) owner = null }
            }
            if (owned) destroyNow()
        }
    }

    private fun destroyNow() {
        runCatching { MPVLib.destroy() }
            .onFailure { error -> Log.w(TAG, "libmpv destroy failed", error) }
    }
}
