package com.nuvio.app.features.subtitledownloader.engine

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** A link to the remote file and the headers it needs. */
internal class RemoteEndpoint(val url: String, val headers: Map<String, String>)

/** Where a remote file can be fetched from, again with a fresh link when the last one stops working. */
internal interface RemoteSource {
    /** Names the file at its provider (an account and file id), the same across fresh links. */
    val stableId: String
    val sizeHint: Long?
    val canRefresh: Boolean
    suspend fun resolve(forceRefresh: Boolean): RemoteEndpoint
}

internal class RangeNotSupportedException(message: String) : IOException(message)
internal class RemoteFileChangedException(message: String) : IOException(message)
internal class RemoteHttpException(val code: Int) : IOException("HTTP $code")

/** One ranged GET's timings, filled in by OkHttp's events. */
internal class CallMetrics {
    @Volatile var startNs = 0L
    @Volatile var headersNs = 0L
    @Volatile var newConnection = false
}

private object MetricsListener : EventListener() {
    private fun metrics(call: Call) = call.request().tag(CallMetrics::class.java)
    override fun callStart(call: Call) {
        metrics(call)?.startNs = System.nanoTime()
    }
    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        metrics(call)?.newConnection = true
    }
    override fun responseHeadersStart(call: Call) {
        metrics(call)?.headersNs = System.nanoTime()
    }
}

/**
 * Closed byte ranges of one remote file, straight from its server with OkHttp: connections are
 * reused (HTTP/2 when the server offers it), the redirect a link answers with is followed once and
 * then asked directly, a refused link is renewed, overload is waited out, a server that ignores the
 * range is caught before it sends the whole file, and every byte it sends is counted.
 */
internal class HttpRangeFetcher(
    baseClient: OkHttpClient,
    private val source: RemoteSource,
    private val stats: NetStats,
    private val budget: TrafficBudget,
    val limiter: AdaptiveLimiter,
    private val model: NetworkModel,
    private val allowPrivateHosts: Boolean = false,
) {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .eventListener(MetricsListener)
        .build()

    @Volatile private var endpoint: RemoteEndpoint? = null
    /** Where the link redirected to last time, asked directly until it stops answering. */
    @Volatile private var direct: HttpUrl? = null
    /** The file's first bytes as first fetched: a fresh link must serve the same ones. */
    @Volatile var identityPrefix: ByteArray? = null
    @Volatile var fileSize: Long? = null
        private set
    @Volatile var lastProtocol: Protocol? = null
        private set
    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    private var refreshGeneration = 0

    /** The bytes from [start] to [endInclusive], exactly. */
    suspend fun fetch(start: Long, endInclusive: Long): ByteArray {
        require(endInclusive >= start)
        val length = endInclusive - start + 1
        val reservation = budget.reserve(length + model.requestOverhead().toLong())
        try {
            return limiter.withPermit { fetchWithRetries(start, endInclusive) }
        } finally {
            budget.release(reservation)
        }
    }

    private suspend fun fetchWithRetries(start: Long, endInclusive: Long): ByteArray {
        var attempt = 0
        var refreshed = false
        while (true) {
            val generation = refreshGeneration
            try {
                return fetchOnce(start, endInclusive)
            } catch (e: NeedsFreshLink) {
                if (refreshed || !source.canRefresh) throw RemoteHttpException(e.code)
                refreshed = true
                refresh(generation)
            } catch (e: Overloaded) {
                stats.overloads.incrementAndGet()
                limiter.onOverload()
                if (++attempt > MAX_ATTEMPTS) throw RemoteHttpException(e.code)
                stats.retries.incrementAndGet()
                delay(e.retryAfterMs ?: backoff(attempt))
            } catch (e: RangeNotSupportedException) {
                throw e
            } catch (e: RemoteFileChangedException) {
                throw e
            } catch (e: IOException) {
                if (e is InterruptedIOException && e !is SocketTimeoutException) throw e
                currentCoroutineContext().job.ensureActive()
                stats.errors.incrementAndGet()
                if (e is SocketTimeoutException) limiter.onOverload()
                if (++attempt > MAX_ATTEMPTS) throw e
                stats.retries.incrementAndGet()
                delay(backoff(attempt))
            }
        }
    }

    private fun backoff(attempt: Int): Long {
        val base = (400L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(15_000L)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    private suspend fun refresh(seenGeneration: Int) {
        refreshLock.lock()
        try {
            // Another request already renewed it while this one waited.
            if (refreshGeneration != seenGeneration) return
            endpoint = source.resolve(forceRefresh = true)
            direct = null
            refreshGeneration++
            stats.refreshes.incrementAndGet()
        } finally {
            refreshLock.unlock()
        }
        val prefix = identityPrefix ?: return
        val now = fetchOnce(0, prefix.size - 1L)
        if (!now.contentEquals(prefix)) throw RemoteFileChangedException("a fresh link serves another file")
    }

    private suspend fun currentEndpoint(): RemoteEndpoint =
        endpoint ?: source.resolve(forceRefresh = false).also { endpoint = it }

    private suspend fun fetchOnce(start: Long, endInclusive: Long): ByteArray = withContext(Dispatchers.IO) {
        val origin = currentEndpoint()
        val originUrl = origin.url.toHttpUrlOrNull() ?: throw IOException("not an http link")
        var url = direct ?: originUrl
        var viaDirect = direct != null
        var hops = 0
        while (true) {
            if (!url.isSafe()) throw IOException("refusing a private address")
            val metrics = CallMetrics()
            val request = Request.Builder().url(url).get()
                .apply {
                    val trusted = url.host == originUrl.host || url.host.endsWith(".${originUrl.host}")
                    origin.headers.forEach { (name, value) ->
                        val sensitive = name.equals("Cookie", true) || name.equals("Authorization", true)
                        if (!sensitive || trusted) header(name, value)
                    }
                    header("Accept-Encoding", "identity")
                    header("Range", "bytes=$start-$endInclusive")
                }
                .tag(CallMetrics::class.java, metrics)
                .build()
            stats.requests.incrementAndGet()
            val call = client.newCall(request)
            val job = currentCoroutineContext().job
            val handle = job.invokeOnCompletion { if (job.isCancelled) call.cancel() }
            var next: HttpUrl? = null
            val body: ByteArray? = try {
                call.execute().use { response ->
                    handle(response, request, metrics, start, endInclusive, origin, viaDirect).also {
                        if (it == null) next = response.header("Location")?.let(url::resolve)
                            ?: throw IOException("redirect without a location")
                    }
                }
            } catch (e: DirectLinkStale) {
                direct = null
                next = originUrl
                null
            } finally {
                handle.dispose()
                if (call.isCanceled()) stats.cancelled.incrementAndGet()
            }
            if (body != null) return@withContext body
            if (++hops > MAX_REDIRECTS) throw IOException("too many redirects")
            if (next != originUrl) stats.redirects.incrementAndGet()
            url = next!!
            viaDirect = false
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    /** The body of a good answer; null for a redirect to follow. */
    private fun handle(
        response: Response,
        request: Request,
        metrics: CallMetrics,
        start: Long,
        endInclusive: Long,
        origin: RemoteEndpoint,
        viaDirect: Boolean,
    ): ByteArray? {
        val headerBytes = response.headers.byteCount() + request.headers.byteCount() / 4
        stats.headerBytes.addAndGet(headerBytes)
        stats.status(response.code)
        if (metrics.newConnection) stats.newConnections.incrementAndGet() else stats.reusedConnections.incrementAndGet()
        if (response.protocol == Protocol.HTTP_2) stats.http2Requests.incrementAndGet()
        lastProtocol = response.protocol
        val code = response.code
        if (code in REDIRECTS) return null
        if (code in setOf(401, 403, 404, 410) || (code in 200..299 && response.isHtml())) {
            if (viaDirect) throw DirectLinkStale()
            throw NeedsFreshLink(code)
        }
        if (code == 429 || code == 503 || code == 504 || code == 502 || code == 500) {
            throw Overloaded(code, response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)?.coerceAtMost(30_000))
        }
        if (code == 416) throw RangeNotSupportedException("the server refused the range ($code)")
        val wanted = endInclusive - start + 1
        val total: Long?
        if (code == 200) {
            // The whole file instead of the range: acceptable only when that is exactly what was asked.
            val length = response.body?.contentLength() ?: -1L
            if (!(start == 0L && length in 1..wanted)) {
                throw RangeNotSupportedException("the server sends the whole file instead of a range")
            }
            total = length
        } else if (code == 206) {
            val contentRange = response.header("Content-Range") ?: throw RangeNotSupportedException("no Content-Range")
            val match = CONTENT_RANGE.find(contentRange) ?: throw RangeNotSupportedException("bad Content-Range")
            val from = match.groupValues[1].toLong()
            val to = match.groupValues[2].toLong()
            total = match.groupValues[3].toLongOrNull()
            if (from != start || (to != endInclusive && (total == null || to != total - 1))) {
                throw RangeNotSupportedException("the server answered another range")
            }
        } else {
            throw RemoteHttpException(code)
        }
        if (total != null) {
            val known = fileSize
            if (known != null && known > 0 && known != total) throw RemoteFileChangedException("the file size changed")
            fileSize = total
        }
        val expected = minOf(wanted, (total ?: Long.MAX_VALUE) - start).toInt()
        val body = response.body ?: throw IOException("no body")
        val out = ByteArray(expected)
        val stream = body.byteStream()
        var read = 0
        val bodyStart = System.nanoTime()
        try {
            while (read < expected) {
                val n = stream.read(out, read, expected - read)
                if (n < 0) break
                read += n
            }
        } finally {
            stats.bodyBytes.addAndGet(read.toLong())
        }
        if (read < expected) throw IOException("the server stopped after $read of $expected bytes")
        val ttfb = if (metrics.headersNs > metrics.startNs && metrics.startNs > 0) (metrics.headersNs - metrics.startNs) / 1e9 else model.latency()
        val transfer = (System.nanoTime() - bodyStart) / 1e9
        model.record(ttfb, metrics.newConnection, read.toLong(), transfer, headerBytes)
        limiter.onSuccess(latencyBound = transfer < ttfb)
        if (!viaDirect) {
            val final = response.request.url
            val originUrl = origin.url.toHttpUrlOrNull()
            if (final != originUrl) direct = final
        }
        return out
    }

    private fun Response.isHtml(): Boolean = header("Content-Type")?.contains("text/html", ignoreCase = true) == true

    private fun HttpUrl.isSafe(): Boolean {
        if (scheme != "https" && scheme != "http") return false
        if (allowPrivateHosts) return true
        val host = host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return false
        val literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || ':' in host
        if (!literal) return true
        return runCatching {
            val address = InetAddress.getByName(host)
            !(address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress)
        }.getOrDefault(false)
    }

    private class NeedsFreshLink(val code: Int) : IOException("HTTP $code")
    private class DirectLinkStale : IOException("the redirected link stopped answering")
    private class Overloaded(val code: Int, val retryAfterMs: Long?) : IOException("HTTP $code")

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        private const val MAX_REDIRECTS = 6
        private const val MAX_ATTEMPTS = 6
    }
}
