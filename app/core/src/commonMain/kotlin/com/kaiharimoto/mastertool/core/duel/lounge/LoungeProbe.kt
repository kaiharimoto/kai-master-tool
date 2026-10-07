package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.update.DesktopOs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether the address friends open reaches this computer's Lounge (`docs/LOUNGE.md`): kai's *Test the address* asks
 * `<address>/api/ping?n=<nonce>` from this computer, out through the internet and back in through Cloudflare's tunnel,
 * and this reads what came back — or what went wrong — into what to do about it. Pure, so every case is tested.
 */
object LoungeProbe {
    /** The door's answer to a check: no passcode asked, nothing about the Lounge told. */
    const val PATH = "/api/ping"

    enum class Verdict { OK, WARN, FAIL }

    data class Probe(val verdict: Verdict, val words: String)

    /**
     * The check's address for what kai typed as the Lounge's [address] (`duel.labrynth.info`, with or without its
     * `https://`, a slash or a path after it), or null when there is none to check.
     */
    fun url(address: String, nonce: String): String? {
        val a = address.trim().ifEmpty { return null }
        val withScheme = if ("://" in a) a else "https://$a"
        val scheme = withScheme.substringBefore("://")
        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { return null }
        return "$scheme://$host$PATH?n=$nonce"
    }

    /** The door's body for [nonce]: the nonce back, and which door it is ([door], made each time it opens). */
    fun answer(nonce: String, door: String): String =
        """{"lounge":1,"n":${quote(nonce.take(64))},"door":${quote(door)}}"""

    /**
     * What the check came to: [status] and [body] of the answer, or [error] when there was none; [nonce] the one sent,
     * [door] this computer's open door ([port] its port), [address] what friends open.
     */
    fun read(status: Int?, body: String?, error: String?, nonce: String, door: String?, port: Int, address: String): Probe {
        if (error != null) {
            val e = error.lowercase()
            return when {
                listOf("unknownhost", "unresolved", "name or service not known", "nodename", "no such host", "could not resolve").any { it in e } ->
                    fail("$address does not resolve yet. Check that labrynth.info's nameservers are Cloudflare's (it can take a few hours) and that the tunnel has a public hostname for it.")
                listOf("certificate", "ssl", "tls", "handshake").any { it in e } ->
                    fail("$address has no certificate yet. Cloudflare issues one a few minutes after the hostname is added: try again shortly.")
                listOf("timed out", "timeout").any { it in e } ->
                    fail("Nothing answered at $address in time. Is the tunnel connected (Settings › The Lounge)?")
                listOf("refused", "connect").any { it in e } ->
                    fail("$address refused the connection. Check the address is the one the tunnel's public hostname names.")
                else -> fail("$address could not be reached: $error")
            }
        }
        val text = body.orEmpty()
        return when {
            status == null -> fail("$address gave no answer.")
            status == 530 || "1033" in text -> fail("Cloudflare knows $address, but the tunnel is not connected. Open the Lounge with the tunnel on, and check its token.")
            status == 502 || status == 504 -> fail("The tunnel is up but finds nothing at its service. In Cloudflare, the public hostname's service should be http://localhost:$port.")
            status == 403 && ("cloudflare access" in text.lowercase() || "cf-access" in text.lowercase()) ->
                Probe(Verdict.WARN, "Cloudflare Access stands in front of $address: this check cannot sign in, but friends on its list can.")
            status in 300..399 -> Probe(Verdict.WARN, "$address sends visitors elsewhere ($status). If it is Cloudflare Access's sign-in, that is expected; otherwise check the public hostname.")
            status == 200 -> {
                val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                when {
                    o == null || o.str("lounge") != "1" -> fail("Something answers at $address, but not a Lounge. Check the public hostname's service is http://localhost:$port.")
                    o.str("n") != nonce -> fail("A Lounge answers at $address, but not to this check (a cache in between?). Try again.")
                    door != null && o.str("door") != door -> fail("Another computer's Lounge answers at $address. The tunnel's token may be that computer's.")
                    else -> Probe(Verdict.OK, "Friends can reach this Lounge at $address.")
                }
            }
            status == 404 -> fail("Something answers at $address, but not the Lounge (404). Check the public hostname's service is http://localhost:$port.")
            status == 429 -> Probe(Verdict.WARN, "The Lounge answered, but asks this check to wait: it has been asked many times. Try again in a minute.")
            else -> fail("$address answered $status.")
        }
    }

    /** How to install `cloudflared` on [os], the line to paste in a terminal (Cloudflare's own packages); null off a computer. */
    fun installLine(os: DesktopOs): String? = when (os) {
        DesktopOs.WINDOWS -> "winget install --id Cloudflare.cloudflared"
        DesktopOs.MAC -> "brew install cloudflared"
        DesktopOs.LINUX -> "curl -L -o cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb && sudo dpkg -i cloudflared.deb"
        else -> null
    }

    private fun fail(words: String) = Probe(Verdict.FAIL, words)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun quote(s: String): String = "\"" + s.filter { it.isLetterOrDigit() || it == '-' } + "\""
}
