package com.kaiharimoto.mastertool.core.ai.web

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

/**
 * Which addresses `web_fetch` may read: public https pages, and nothing on the person's own
 * computer or network. A model told by a page to "fetch http://192.168.1.1/admin" would
 * otherwise read the router, and a CLI's `localhost` server, and say what it found.
 *
 * Pure string and number reading, no DNS, so it works the same everywhere and in a test:
 * - https only;
 * - no name or password before the host (`https://evil@127.0.0.1`), no backslash, no
 *   percent-escape and nothing but plain ASCII in the host — parsers disagree on those, and a
 *   full-width `１２７.０.０.１` becomes `127.0.0.1` on its way to the network;
 * - not `localhost` or a name only a home or office network answers (`.local`, `.lan`,
 *   `.internal`, `.home.arpa`, `.localdomain`);
 * - no private, loopback, link-local, shared, multicast or reserved IPv4 address, in any of
 *   the forms a resolver accepts (`2130706433`, `0x7f.1`, `0177.0.0.1`);
 * - no IPv6 loopback, unspecified, unique-local, link-local, site-local or multicast address,
 *   nor one that carries a refused IPv4 address inside it (`::ffff:127.0.0.1`).
 *
 * What a string cannot see: a public name whose DNS answers with a private address (DNS
 * rebinding). Covering that means checking the address the connection actually opens, which
 * the HTTP engine does not expose cheaply; the risk left is a page that runs its own DNS.
 */
object UrlGuard {
    /** Why [url] may not be read, in plain words, or null when it may. */
    fun refusal(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return "Only https pages: “$u”."
        val rest = u.substring("https://".length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('\\' in authority) return "A backslash in the address's host is refused: “$u”."
        if ('@' in authority) return "An address with a name or password before its host is refused: “$u”."
        if (authority.any { it.isWhitespace() || it.isISOControl() }) return "That address has spaces in its host: “$u”."
        val host: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return "That address's host is not readable: “$u”."
            host = authority.substring(1, end)
            val after = authority.substring(end + 1)
            if (after.isNotEmpty() && !(after.startsWith(":") && after.drop(1).all { it in '0'..'9' })) return "That address's port is not readable: “$u”."
            return ipv6Refusal(host, u)
        }
        host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "")
        if (':' in authority && (port.isEmpty() || !port.all { it in '0'..'9' })) return "That address's port is not readable: “$u”."
        if (host.isEmpty()) return "That address has no host: “$u”."
        if (host.any { it.code > 0x7E }) return "Write the address's host in plain letters (its xn-- form): “$u”."
        if ('%' in host) return "A percent-escape in the address's host is refused: “$u”."
        val name = host.lowercase().trimEnd('.')
        if (name.isEmpty()) return "That address has no host: “$u”."
        if (name == "localhost" || LOCAL_SUFFIXES.any { name.endsWith(it) }) return local(u)
        // A host whose last label is a number is an IPv4 address to every resolver, in whatever spelling.
        val last = name.substringAfterLast('.')
        if (last.isNotEmpty() && (last.all { it in '0'..'9' } || (last.startsWith("0x") && last.drop(2).all { it in HEX }))) {
            val v4 = ipv4(name) ?: return "That address's host is not a readable number: “$u”."
            return ipv4Refusal(v4, u)
        }
        return null
    }

    /**
     * A GET of [url] that follows at most [maxHops] redirects by hand, checking each place it is
     * sent with [refusal] first — so a public page cannot bounce the request onto the network.
     * [client] must not follow redirects itself (`followRedirects = false`).
     */
    suspend fun get(
        client: HttpClient,
        url: String,
        maxHops: Int = 5,
        block: HttpRequestBuilder.() -> Unit = {},
    ): Result<HttpResponse> = runCatching {
        var at = url
        repeat(maxHops + 1) {
            refusal(at)?.let { error(it) }
            val r = client.get(at, block)
            val location = r.headers[HttpHeaders.Location]
            if (r.status.value !in 300..399 || location.isNullOrBlank()) return@runCatching r
            at = URLBuilder(at).takeFrom(location).buildString()
        }
        error("Too many redirects from “$url”.")
    }

    private fun local(u: String) = "That address is on this computer or its own network, which Ai may not read: “$u”."

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa", ".localdomain")
    private const val HEX = "0123456789abcdef"

    /**
     * [host] as an IPv4 address the way `inet_aton` reads it — one to four parts, each decimal,
     * `0x` hex or `0`-led octal, the last filling the bytes left — or null when it is not one.
     */
    internal fun ipv4(host: String): Long? {
        val parts = host.split('.')
        if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
        val values = parts.map { part(it) ?: return null }
        val head = values.dropLast(1)
        if (head.any { it > 255 }) return null
        val tail = values.last()
        if (tail >= 1L shl (8 * (5 - values.size))) return null
        return head.foldIndexed(tail) { i, acc, v -> acc or (v shl (8 * (3 - i))) }
    }

    private fun part(p: String): Long? {
        val (digits, radix) = when {
            p.startsWith("0x") -> p.drop(2) to 16
            p.length > 1 && p.startsWith("0") -> p.drop(1) to 8
            else -> p to 10
        }
        if (digits.isEmpty()) return if (radix == 16) 0 else null
        if (digits.length > 12) return null
        return digits.toLongOrNull(radix)?.takeIf { it <= 0xFFFFFFFFL }
    }

    /** The IPv4 blocks no web page lives in, as (first address, prefix length). */
    private val REFUSED_V4 = listOf(
        0x00000000L to 8, // "this network"
        0x0A000000L to 8, // private
        0x64400000L to 10, // shared (carrier-grade NAT)
        0x7F000000L to 8, // loopback
        0xA9FE0000L to 16, // link-local, and the cloud's metadata service
        0xAC100000L to 12, // private
        0xC0000000L to 24, // IETF protocol assignments
        0xC0A80000L to 16, // private
        0xC6120000L to 15, // benchmarking
        0xE0000000L to 3, // multicast, reserved and broadcast: 224.0.0.0 and up
    )

    private fun v4Refused(a: Long): Boolean = REFUSED_V4.any { (base, bits) -> (a ushr (32 - bits)) == (base ushr (32 - bits)) }

    private fun ipv4Refusal(a: Long, u: String): String? = if (v4Refused(a)) local(u) else null

    private fun ipv6Refusal(host: String, u: String): String? {
        if ('%' in host) return "An IPv6 address with a zone is refused: “$u”."
        val g = ipv6(host.lowercase()) ?: return "That address's IPv6 host is not readable: “$u”."
        val embedded = (g[6].toLong() shl 16) or g[7].toLong()
        return when {
            g.all { it == 0 } -> local(u) // ::
            g.take(7).all { it == 0 } && g[7] == 1 -> local(u) // ::1
            (g[0] and 0xFE00) == 0xFC00 -> local(u) // fc00::/7, unique-local
            (g[0] and 0xFFC0) == 0xFE80 -> local(u) // fe80::/10, link-local
            (g[0] and 0xFFC0) == 0xFEC0 -> local(u) // fec0::/10, the old site-local
            (g[0] and 0xFF00) == 0xFF00 -> local(u) // ff00::/8, multicast
            // An IPv4 address inside: mapped (::ffff:a.b.c.d), compatible (::a.b.c.d), NAT64 (64:ff9b::a.b.c.d).
            g.take(5).all { it == 0 } && (g[5] == 0xFFFF || g[5] == 0) -> ipv4Refusal(embedded, u)
            g[0] == 0x64 && g[1] == 0xFF9B && g.subList(2, 6).all { it == 0 } -> ipv4Refusal(embedded, u)
            // 6to4 (2002:a.b.c.d::/48) carries one in its second and third groups.
            g[0] == 0x2002 -> ipv4Refusal((g[1].toLong() shl 16) or g[2].toLong(), u)
            else -> null
        }
    }

    /** [host] as eight 16-bit groups, with `::` and a dotted IPv4 tail, or null when it is not an IPv6 address. */
    internal fun ipv6(host: String): List<Int>? {
        if (host.isEmpty() || host.count { it == ':' } < 2) return null
        if (host.indexOf("::") != host.lastIndexOf("::") || ":::" in host) return null
        fun groups(s: String): List<Int>? {
            if (s.isEmpty()) return emptyList()
            val pieces = s.split(':')
            val out = mutableListOf<Int>()
            pieces.forEachIndexed { i, p ->
                if (i == pieces.lastIndex && '.' in p) {
                    // A dotted IPv4 tail, strictly four decimal parts: two groups.
                    val bytes = p.split('.')
                    if (bytes.size != 4 || bytes.any { b -> b.isEmpty() || b.length > 3 || !b.all { it in '0'..'9' } || b.toInt() > 255 }) return null
                    val v = bytes.map { it.toInt() }
                    out += (v[0] shl 8) or v[1]
                    out += (v[2] shl 8) or v[3]
                } else {
                    if (p.isEmpty() || p.length > 4 || !p.all { it in HEX }) return null
                    out += p.toInt(16)
                }
            }
            return out
        }
        val double = host.indexOf("::")
        val all = if (double >= 0) {
            val head = groups(host.substring(0, double)) ?: return null
            val tail = groups(host.substring(double + 2)) ?: return null
            if (head.size + tail.size > 7) return null
            head + List(8 - head.size - tail.size) { 0 } + tail
        } else {
            groups(host) ?: return null
        }
        return all.takeIf { it.size == 8 }
    }
}
