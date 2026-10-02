package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.neue.duel.DuelLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two apps' links over a real socket (1.0.77): what one sends the other reads, whole and in order. */
class DuelLinkTest {
    @Test
    fun messagesCrossASocketWholeAndInOrder() = runBlocking {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        var accepted: Socket? = null
        val t = thread { accepted = server.accept() }
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        t.join(5000)
        val hostGot = CompletableDeferred<List<Wire>>()
        val got = mutableListOf<Wire>()
        val host = DuelLink(accepted!!, { w -> got += w; if (got.size == 3) hostGot.complete(got.toList()) }, {})
        val guestGot = CompletableDeferred<Wire>()
        val guest = DuelLink(client, { w -> guestGot.complete(w) }, {})
        host.start()
        guest.start()
        val hello = Wire.Hello(name = "Rival", main = List(40) { 1000 + it }, secret = 42)
        val intent = Wire.Intent(1, listOf(DuelAction.Draw(1), DuelAction.Chat(1, "é ✓ — long text ".repeat(200))))
        guest.send(hello)
        guest.send(intent)
        guest.send(Wire.Bye)
        assertEquals(listOf(hello, intent, Wire.Bye), withTimeout(10_000) { hostGot.await() })
        host.send(Wire.Refused(1, "No"))
        assertEquals(Wire.Refused(1, "No"), withTimeout(10_000) { guestGot.await() })
        host.close()
        guest.close()
        server.close()
    }
}
