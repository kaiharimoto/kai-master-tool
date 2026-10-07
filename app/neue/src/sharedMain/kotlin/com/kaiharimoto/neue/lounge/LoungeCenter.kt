package com.kaiharimoto.neue.lounge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAuth
import com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeProbe
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.SecretStore
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * The Lounge as the app holds it (`docs/LOUNGE.md`): open or closed, its passcode (only the hash is kept, with the
 * app's secrets), Cloudflare's tunnel, and kai's own place in it — kai joins as the host, in-process, and sits or
 * watches at a room's table on the Duel page like any friend in a browser.
 */
class LoungeCenter(private val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val dir: File get() = File(Platform.dataDir, "lounge")
    val prefs: LoungePrefs get() = h.neue.prefs.lounge

    /** Ai at the rooms' tables, on kai's connection (L5); none while Ai is off. */
    private val aiPlayers by lazy { NeueLoungeAi(h, dir) }

    val host: LoungeHost by lazy {
        LoungeHost(
            dir,
            catalog = { h.duel.catalog },
            keep = { name, game -> h.duel.replayer.keepReplay(name, game) },
            record = { r -> h.duel.keepResult(r) },
            ai = { aiPlayers.takeIf { h.neue.prefs.ai.enabled } },
        )
    }

    var open by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
    /** What the tunnel said last: connecting, connected, or why it stopped. */
    var tunnel by mutableStateOf<String?>(null)
        private set
    /** The Lounge's dialog on the Duel page: opening it, the lobby, kai's decks brought in. */
    var dialogOpen by mutableStateOf(false)
    /** kai's own side of the Lounge while it is open. */
    var client by mutableStateOf<LoungeClient?>(null)
        private set

    val available: Boolean get() = LoungeDoor.available
    /** Whether `cloudflared` is installed where the tunnel looks for it: Settings says how to add it when not. */
    val cloudflaredFound: Boolean get() = LoungeDoor.cloudflaredFound

    /** *Test the address*: running, or what the last check came to. */
    var checking by mutableStateOf(false)
        private set
    var addressCheck by mutableStateOf<LoungeProbe.Probe?>(null)
        private set
    /** Whether a passcode and a tunnel token are kept: state, so Settings shows a change as it is made. */
    var hasPasscode by mutableStateOf(SecretStore.get(PASSCODE) != null)
        private set
    var hasTunnelToken by mutableStateOf(SecretStore.get(TUNNEL) != null)
        private set

    fun update(change: (LoungePrefs) -> LoungePrefs) = h.neue.update { it.copy(lounge = change(it.lounge)) }

    /** The passcode friends type: kept as its hash; the problem in words, or null. */
    fun setPasscode(passcode: String): String? {
        if (passcode.length < LoungeAuth.MIN_LENGTH) return "At least ${LoungeAuth.MIN_LENGTH} characters: it stands between the internet and this computer"
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        scope.launch(Dispatchers.Default) {
            val hash = LoungeAuth.hash(passcode, salt)
            SecretStore.put(PASSCODE, hash)
            launch(Dispatchers.Main) { hasPasscode = true }
        }
        return null
    }

    /** Cloudflare's tunnel token (Zero Trust › Networks › Tunnels › your tunnel › install): kept with the app's secrets. */
    fun setTunnelToken(token: String) {
        val t = token.trim().removePrefix("cloudflared service install").removePrefix("cloudflared tunnel run --token").trim()
        if (t.isEmpty()) SecretStore.remove(TUNNEL) else SecretStore.put(TUNNEL, t)
        hasTunnelToken = t.isNotEmpty()
    }

    fun openLounge() {
        if (!hasPasscode) { problem = "Set a passcode first: it is what friends type to come in"; return }
        problem = LoungeDoor.open(
            host, prefs,
            passcodeHash = { SecretStore.get(PASSCODE) },
            pool = { h.builder.index.cards },
            original = { id -> h.art.fileFor(id) },
            artCache = File(dir, "art"),
        )
        if (problem != null) return
        open = true
        joinAsKai()
        if (prefs.tunnel) startTunnel()
    }

    fun closeLounge() {
        client?.lost()
        client = null
        host.shutdown()
        LoungeDoor.close()
        LoungeDoor.stopTunnel()
        tunnel = null
        open = false
    }

    fun startTunnel() {
        val token = SecretStore.get(TUNNEL) ?: run { tunnel = "No tunnel token yet: friends can only come in on this network"; return }
        tunnel = "Connecting…"
        val why = LoungeDoor.tunnel(
            token,
            line = { l -> scope.launch { tunnelSaid(l) } },
            ended = { code -> scope.launch { if (open) tunnel = "The tunnel stopped ($code). Close and open the Lounge to start it again." } },
        )
        if (why != null) tunnel = why
    }

    private fun tunnelSaid(line: String) {
        when {
            "Registered tunnel connection" in line -> tunnel = "Connected: friends can come in at ${prefs.address.ifBlank { "your address" }}"
            "Unauthorized" in line || "Invalid tunnel secret" in line -> tunnel = "Cloudflare refused the token: paste it again"
            " ERR " in line && tunnel?.startsWith("Connected") != true -> tunnel = "Connecting… (${line.substringAfter(" ERR ").take(80)})"
        }
    }

    /**
     * Asks the Lounge's address from this computer — out through the internet, back in through Cloudflare's tunnel —
     * and says what came of it (`LoungeProbe`): whether friends can reach this door, else what to do about it.
     */
    fun testAddress() {
        if (checking) return
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val url = LoungeProbe.url(prefs.address, nonce) ?: run {
            addressCheck = LoungeProbe.Probe(LoungeProbe.Verdict.FAIL, "Type the address friends open first, such as duel.labrynth.info.")
            return
        }
        if (!open) {
            addressCheck = LoungeProbe.Probe(LoungeProbe.Verdict.FAIL, "Open the Lounge first: the check knocks on its door.")
            return
        }
        checking = true
        addressCheck = null
        val door = LoungeDoor.door
        val address = prefs.address.trim()
        val port = prefs.port
        scope.launch {
            val probe = withContext(Dispatchers.Default) {
                val client = HttpClientFactory.create()
                try {
                    val r = client.get(url) { timeout { requestTimeoutMillis = CHECK_MS; connectTimeoutMillis = CHECK_MS } }
                    LoungeProbe.read(r.status.value, r.bodyAsText().take(4096), null, nonce, door, port, address)
                } catch (e: Exception) {
                    LoungeProbe.read(null, null, "${e::class.simpleName}: ${e.message}", nonce, door, port, address)
                } finally {
                    client.close()
                }
            }
            addressCheck = probe
            checking = false
        }
    }

    /** kai in the Lounge as the host: a client in-process, the table going back to the local network's after a room. */
    private fun joinAsKai() {
        val lan = h.duel.network
        lateinit var session: LoungeHost.Session
        val c = LoungeClient(h.duel, send = { w -> session.hear(w) }, away = { lan })
        session = host.open(out = { w -> c.hear(w) })
        client = c
        host.hostJoin(session, prefs.nick)
    }

    companion object {
        const val PASSCODE = "lounge:passcode"
        const val TUNNEL = "lounge:tunnel"
        private const val CHECK_MS = 10_000L
    }
}
