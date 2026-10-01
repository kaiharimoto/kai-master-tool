package com.kaiharimoto.neue.sync

import com.kaiharimoto.mastertool.core.sync.CloudSignIn
import com.kaiharimoto.mastertool.core.sync.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Where a sign-in comes back to (1.0.68): the app listens on `localhost:53682` for the one request
 * the service sends the browser to, answers it with a page saying to go back to the app, and hands
 * on its query. Plain sockets, so it is the same on the desk and on Android, and nothing else is ever
 * served: the first request that is not the redirect is answered "not found".
 */
object Loopback {
    suspend fun await(timeoutMs: Long = 5 * 60_000L, page: String): String = withContext(Dispatchers.IO) {
        val servers = listOfNotNull(
            open(InetAddress.getByName("127.0.0.1")),
            open(InetAddress.getByName("::1")),
        )
        if (servers.isEmpty()) throw SyncException("Another app is using the sign-in port (${CloudSignIn.PORT}). Close it, or sign in again in a minute.")
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val threads = servers.map { server ->
                        Thread {
                            while (!server.isClosed) {
                                val socket = runCatching { server.accept() }.getOrNull() ?: break
                                val query = runCatching { answer(socket, page) }.getOrNull()
                                if (query != null && cont.isActive) cont.resume(query)
                            }
                        }.apply { isDaemon = true; start() }
                    }
                    cont.invokeOnCancellation { servers.forEach { runCatching { it.close() } }; threads.forEach { it.interrupt() } }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw SyncException("The sign-in was not finished. Try again, and finish signing in within five minutes.", e)
        } finally {
            servers.forEach { runCatching { it.close() } }
        }
    }

    private fun open(address: InetAddress): ServerSocket? = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, CloudSignIn.PORT))
        }
    }.getOrNull()

    /** Reads one request; the redirect's query, or null for anything else. */
    private fun answer(socket: Socket, page: String): String? = socket.use { s ->
        s.soTimeout = 10_000
        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
        val target = line.split(' ').getOrNull(1).orEmpty()
        val redirect = target.startsWith("/?") && "state=" in target
        val body = if (redirect) page else "Not found"
        val bytes = body.encodeToByteArray()
        val head = "HTTP/1.1 ${if (redirect) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.encodeToByteArray()); write(bytes); flush() }
        if (redirect) target.substringAfter('?') else null
    }

    /** The page the browser shows: plain, in the family's two colours, with the way back on a phone. */
    fun page(service: String, back: String?): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Signed in</title><style>body{font:16px/1.5 Inter,system-ui,sans-serif;margin:0;padding:48px 24px;background:#fff;color:#000}h1{font-size:28px;margin:0 0 8px}a{display:inline-block;margin-top:24px;padding:10px 16px;background:#000;color:#fff;text-decoration:none}</style></head>
<body><h1>Signed in to $service</h1><p>You can close this tab and go back to Neue Master Tool. It syncs now.</p>${back?.let { "<a href=\"$it\">Back to Neue Master Tool</a>" }.orEmpty()}</body></html>"""
}
