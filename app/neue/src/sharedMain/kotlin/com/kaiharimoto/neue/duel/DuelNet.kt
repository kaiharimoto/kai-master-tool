package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.net.PairCode
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.net.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * One connection between two apps (1.0.77): messages as length-prefixed UTF-8 JSON over a plain TCP
 * socket — `java.net`, which the desk and Android both have, so no server library comes with it (the
 * sign-in loopback, `Loopback.kt`, works the same way). [onMessage] and [onClosed] are called on the
 * main thread.
 */
internal class DuelLink(private val socket: Socket, private val onMessage: (Wire) -> Unit, private val onClosed: (String?) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val out = DataOutputStream(socket.getOutputStream().buffered())
    @Volatile private var closed = false

    fun start() {
        scope.launch {
            val input = DataInputStream(socket.getInputStream().buffered())
            val why = runCatching {
                while (!closed) {
                    val n = input.readInt()
                    if (n <= 0 || n > MAX) error("A message of $n bytes")
                    val bytes = ByteArray(n)
                    input.readFully(bytes)
                    val w = WireCodec.decode(bytes.decodeToString()) ?: continue
                    withContext(Dispatchers.Main) { onMessage(w) }
                }
            }.exceptionOrNull()
            if (!closed) {
                closed = true
                withContext(Dispatchers.Main) { onClosed(why?.message) }
            }
        }
    }

    fun send(w: Wire) {
        if (closed) return
        val bytes = WireCodec.encode(w).encodeToByteArray()
        scope.launch {
            runCatching {
                synchronized(out) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        scope.cancel()
    }

    companion object {
        const val MAX = 8 * 1024 * 1024
    }
}

/**
 * Hosting: a socket open on the local network for one guest, the code that finds it, and the guest's
 * link once it comes. [onGuest] hears the guest's link as it connects; its Hello comes through it.
 */
internal class DuelHosting(private val onGuest: (Socket) -> Unit, private val onProblem: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var job: Job? = null
    val secret: Int = java.security.SecureRandom().nextInt(65535) + 1
    var code: String? = null
        private set

    /** Opens the table: the code to share, or null when there is no network to share it on. */
    suspend fun open(): String? = withContext(Dispatchers.IO) {
        val address = lanAddress() ?: return@withContext null
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        code = PairCode.encode(PairCode.Table(address, s.localPort, secret))
        job = scope.launch {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                socket.tcpNoDelay = true
                withContext(Dispatchers.Main) { onGuest(socket) }
            }
        }
        code
    }

    fun close() {
        runCatching { server?.close() }
        scope.cancel()
    }

    companion object {
        /** This device's address on the local network: a site-local IPv4 on an interface that is up. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .sortedByDescending { it.isSiteLocalAddress }
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }
}

/** Joining: a socket to the host the code names. */
internal suspend fun dial(table: PairCode.Table): Socket = withContext(Dispatchers.IO) {
    val s = Socket()
    s.connect(InetSocketAddress(table.host, table.port), 6000)
    s.tcpNoDelay = true
    s
}
