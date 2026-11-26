package com.nuvio.app.features.subtitledownloader.engine

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * How many requests may be in flight at once, found as the transfer goes: one more while that
 * moves more data or the requests are too small to fill the line, half as many when the server
 * says it is overloaded (503, 504, 429) or a request times out.
 */
internal class AdaptiveLimiter(
    initial: Int,
    private val floor: Int = 1,
    private val ceiling: Int = 8,
    private val model: NetworkModel,
) {
    private val lock = Any()
    private val waiters = ArrayDeque<CancellableContinuation<Unit>>()
    private var inFlight = 0
    private var successes = 0
    private var cooldownUntilNs = 0L
    private val goodputAtLimit = HashMap<Int, Double>()

    @Volatile
    var limit: Int = initial.coerceIn(floor, ceiling)
        private set

    /** A lower ceiling while something more important (playback) is using the server. */
    @Volatile
    var externalCeiling: Int = ceiling

    val peak: Int get() = synchronized(lock) { maxSeen }
    private var maxSeen = limit

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        synchronized(lock) {
            if (inFlight < effectiveLimit()) {
                inFlight++
                return
            }
        }
        suspendCancellableCoroutine { continuation ->
            val granted = synchronized(lock) {
                if (inFlight < effectiveLimit()) {
                    inFlight++
                    true
                } else {
                    waiters.addLast(continuation)
                    false
                }
            }
            if (granted) continuation.resume(Unit)
            continuation.invokeOnCancellation {
                synchronized(lock) { waiters.remove(continuation) }
            }
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            inFlight--
            wake()
        }
        next.forEach { it.resume(Unit) { release() } }
    }

    /** Hands free permits to waiters; returns those to resume outside the lock. */
    private fun wake(): List<CancellableContinuation<Unit>> {
        val woken = mutableListOf<CancellableContinuation<Unit>>()
        while (inFlight < effectiveLimit() && waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            if (waiter.isActive) {
                inFlight++
                woken += waiter
            }
        }
        return woken
    }

    private fun effectiveLimit(): Int = minOf(limit, externalCeiling).coerceAtLeast(floor)

    /** A request finished; [latencyBound] when its transfer took less time than waiting for it. */
    fun onSuccess(latencyBound: Boolean) {
        val woken = synchronized(lock) {
            successes++
            val now = System.nanoTime()
            if (now < cooldownUntilNs || successes < limit * 2) return
            successes = 0
            val goodput = model.recentGoodput()
            val previous = goodputAtLimit[limit - 1]
            goodputAtLimit[limit] = goodput
            val improving = previous == null || goodput > previous * 1.05
            if (limit < ceiling && (latencyBound || improving)) {
                limit++
            } else if (previous != null && goodput < previous * 0.9 && limit > floor) {
                limit--
            }
            maxSeen = maxOf(maxSeen, limit)
            wake()
        }
        woken.forEach { it.resume(Unit) { release() } }
    }

    fun onOverload() {
        synchronized(lock) {
            limit = (limit / 2).coerceAtLeast(floor)
            successes = 0
            cooldownUntilNs = System.nanoTime() + COOLDOWN_NS
        }
    }

    private companion object {
        const val COOLDOWN_NS = 4_000_000_000L
    }
}
