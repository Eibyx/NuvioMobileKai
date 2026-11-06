package com.nuvio.app.features.cloudstorage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

internal actual object CloudOAuthLoopback {
    actual val supported: Boolean = true

    actual suspend fun receive(onReady: (redirectUri: String) -> Unit): Map<String, String> = withContext(Dispatchers.IO) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5 * 60 * 1000
            withContext(Dispatchers.Main) { onReady("http://127.0.0.1:${server.localPort}") }
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    break
                }
                socket.use { connection ->
                    connection.soTimeout = 10_000
                    val requestLine = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1)).readLine().orEmpty()
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { pair ->
                        URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                    }
                    val done = "code" in query || "error" in query
                    val body = if (done) "<html><body><p>Nuvio: puedes volver a la app.</p></body></html>" else ""
                    connection.getOutputStream().write(
                        ("HTTP/1.1 ${if (done) 200 else 404} OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                    if (done) return@withContext query
                }
            }
            throw CloudStorageException(CloudFailure.AUTH)
        }
    }
}
