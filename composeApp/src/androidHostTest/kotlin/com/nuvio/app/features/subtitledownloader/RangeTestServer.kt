package com.nuvio.app.features.subtitledownloader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A CDN for tests: a link that redirects to a tokenised file URL, ranged GETs with a delay before
 * the headers and a shared bandwidth, a cap on simultaneous requests answered with 503, links
 * that expire, and a mode that ignores Range and sends the whole file.
 */
class RangeTestServer(
    private val file: VirtualFile,
    var latencyMs: Long = 0,
    var bytesPerSecond: Long = 0,
    var maxConcurrent: Int = 64,
    var ignoreRange: Boolean = false,
    /** Every this many file requests, a token stops working (403) until a fresh link is made. */
    var expireEvery: Int = 0,
    /** Every this many file requests answers 504 once. */
    var failEvery: Int = 0,
) {
    private val server = run { System.setProperty("sun.net.httpserver.nodelay", "true") }.let { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 128) }
    val requests = AtomicInteger()
    val bodyBytes = AtomicLong()
    val rejected = AtomicInteger()
    private val active = AtomicInteger()
    private val token = AtomicInteger(1)
    private val bucketLock = Object()
    private var nextFreeNs = 0L

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/link") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn/${token.get()}/video.mkv")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn") { exchange -> serve(exchange) }
        server.start()
    }

    val linkUrl: String get() = "http://127.0.0.1:${server.address.port}/link"

    /** A fresh link, as a provider would hand out after the last one expired. */
    fun renew() {
        token.incrementAndGet()
    }

    fun stop() = server.stop(0)

    private fun serve(exchange: HttpExchange) {
        val n = requests.incrementAndGet()
        try {
            if (latencyMs > 0) Thread.sleep(latencyMs)
            val pathToken = exchange.requestURI.path.split('/').getOrNull(2)?.toIntOrNull()
            if (expireEvery > 0 && n % expireEvery == 0) renew()
            if (pathToken != token.get()) return respond(exchange, 403)
            if (failEvery > 0 && n % failEvery == 0) return respond(exchange, 504)
            if (active.incrementAndGet() > maxConcurrent) {
                active.decrementAndGet()
                rejected.incrementAndGet()
                return respond(exchange, 503)
            }
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
                if (ignoreRange || match == null) {
                    exchange.responseHeaders.add("Content-Type", "video/x-matroska")
                    exchange.sendResponseHeaders(200, file.size)
                    send(exchange, 0, file.size)
                    return
                }
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(file.size - 1) ?: (file.size - 1)
                if (start >= file.size) return respond(exchange, 416)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${file.size}")
                exchange.responseHeaders.add("Content-Type", "video/x-matroska")
                exchange.sendResponseHeaders(206, end - start + 1)
                send(exchange, start, end - start + 1)
            } finally {
                active.decrementAndGet()
            }
        } catch (_: java.io.IOException) {
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, start: Long, length: Long) {
        val out = exchange.responseBody
        var sent = 0L
        while (sent < length) {
            val count = minOf(64L * 1024, length - sent).toInt()
            throttle(count)
            out.write(file.read(start + sent, count))
            sent += count
            bodyBytes.addAndGet(count.toLong())
        }
        out.flush()
    }

    /** One line shared by every connection, [bytesPerSecond] wide. */
    private fun throttle(bytes: Int) {
        if (bytesPerSecond <= 0) return
        val wait = synchronized(bucketLock) {
            val now = System.nanoTime()
            val start = maxOf(now, nextFreeNs)
            nextFreeNs = start + bytes * 1_000_000_000L / bytesPerSecond
            nextFreeNs - now
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    private fun respond(exchange: HttpExchange, code: Int) {
        exchange.sendResponseHeaders(code, -1)
    }
}
