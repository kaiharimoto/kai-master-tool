package com.kaiharimoto.mastertool.core.duel.lounge

/**
 * Where a request at the Lounge's door came from (the red team, 2026-10). Cloudflare's tunnel reaches the door from this
 * computer — `cloudflared` runs beside it and dials `localhost` — and names the visitor in [HEADER]; but anyone could
 * write that header, a device on the LAN when the door is open there among them, and step through a fresh address with
 * every guess. So it is believed only from a loopback socket, and only when it is an IP address; anything else is counted
 * by the socket's own address. A visitor through the tunnel on IPv6 is counted by its /64, the block one household is
 * given, so walking through its own addresses buys no fresh tries.
 */
object LoungeAddress {
    const val HEADER = "CF-Connecting-IP"

    /** The key a request is counted under: [socket] is the connection's own address, [forwarded] its [HEADER], if any. */
    fun of(socket: String, forwarded: String?): String {
        val own = parse(socket)
        if (own != null && loopback(own)) {
            val visitor = forwarded?.let(::parse)
            if (visitor != null) return key(visitor, block = true)
        }
        return own?.let { key(it, block = false) } ?: socket
    }

    /** [text] as an IP address — 4 bytes or 16 — or null when it is not one. Brackets and an IPv6 zone are let go. */
    fun parse(text: String): ByteArray? {
        var t = text.trim()
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length - 1)
        t = t.substringBefore('%')
        if (t.isEmpty() || t.length > MAX_LITERAL) return null
        return if (':' in t) v6(t) else v4(t)
    }

    /** Whether [ip] is this computer: 127.0.0.0/8, `::1`, or 127/8 written as IPv6. */
    fun loopback(ip: ByteArray): Boolean = when (ip.size) {
        4 -> ip[0] == 127.toByte()
        16 -> mapped(ip)?.let(::loopback) ?: ((0 until 15).all { ip[it] == 0.toByte() } && ip[15] == 1.toByte())
        else -> false
    }

    /** [ip] written one way only; with [block], an IPv6 address as its /64. */
    fun key(ip: ByteArray, block: Boolean): String {
        if (ip.size == 4) return ip.joinToString(".") { (it.toInt() and 0xff).toString() }
        mapped(ip)?.let { return key(it, block) }
        val words = List(8) { ((ip[it * 2].toInt() and 0xff) shl 8) or (ip[it * 2 + 1].toInt() and 0xff) }
        return if (block) words.take(4).joinToString(":") { it.toString(16) } + "::/64" else words.joinToString(":") { it.toString(16) }
    }

    /** The IPv4 address inside an IPv4-mapped IPv6 one (`::ffff:a.b.c.d`), else null. */
    private fun mapped(ip: ByteArray): ByteArray? {
        if (ip.size != 16 || (0 until 10).any { ip[it] != 0.toByte() } || ip[10] != 0xff.toByte() || ip[11] != 0xff.toByte()) return null
        return ip.copyOfRange(12, 16)
    }

    private fun v4(t: String): ByteArray? {
        val parts = t.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    private fun v6(t: String): ByteArray? {
        val halves = t.split("::")
        if (halves.size > 2) return null
        val compressed = halves.size == 2
        val head = words(halves[0], endsAddress = !compressed) ?: return null
        val tail = if (compressed) words(halves[1], endsAddress = true) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        if (if (compressed) missing < 1 else missing != 0) return null
        val all = head + List(if (compressed) missing else 0) { 0 } + tail
        return ByteArray(16) { i -> (all[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
    }

    /** The 16-bit words of one side of `::`; the address's last group may be an IPv4 address (two words). */
    private fun words(part: String, endsAddress: Boolean): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val groups = part.split(':')
        val out = ArrayList<Int>()
        for ((i, g) in groups.withIndex()) {
            if (endsAddress && i == groups.lastIndex && '.' in g) {
                val b = v4(g) ?: return null
                out += ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                out += ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
            } else {
                if (g.isEmpty() || g.length > 4 || g.any { !hex(it) }) return null
                out += g.toInt(16)
            }
        }
        return out
    }

    private fun hex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** The longest IPv6 literal, `ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255`. */
    private const val MAX_LITERAL = 45
}

/**
 * Who may try the Lounge's passcode now (the red team, 2026-10): each address its own [Lockout]s, and every address
 * together at most [FAILURES] wrong passcodes in [WINDOW_MS] — so neither forged nor many real addresses make kai's
 * computer work PBKDF2 without end. Past that everyone waits until the oldest wrong one in the window is a minute old;
 * a friend with the right passcode waits too, which is the price of a door with a computer behind it.
 * Not thread-safe: the door asks it under one lock.
 */
class PasscodeGate(
    private val addresses: Lockouts = Lockouts(),
    private val limit: Int = FAILURES,
    private val windowMs: Long = WINDOW_MS,
) {
    private val recent = ArrayDeque<Long>()

    /** How long [address] waits before its next try, 0 when it may try now: its own wait, or everyone's. */
    fun waitMs(address: String, now: Long): Long = maxOf(addresses.waitMs(address, now), everyoneWaitMs(now))

    /** How long every address waits, 0 unless [limit] wrong passcodes came in the last [windowMs]. */
    fun everyoneWaitMs(now: Long): Long {
        forget(now)
        return if (recent.size >= limit) (recent.first() + windowMs - now).coerceAtLeast(1L) else 0L
    }

    /** A wrong passcode from [address] at [now]. */
    fun failed(address: String, now: Long) {
        addresses.failed(address, now)
        forget(now)
        recent.addLast(now)
        while (recent.size > limit) recent.removeFirst()
    }

    /** The right passcode from [address]: its own count starts afresh (everyone's stays). */
    fun passed(address: String) = addresses.passed(address)

    /** The addresses kept, for the test that holds it bounded. */
    val addressesKept: Int get() = addresses.size

    private fun forget(now: Long) {
        while (recent.isNotEmpty() && recent.first() + windowMs <= now) recent.removeFirst()
    }

    companion object {
        /** Wrong passcodes from every address together in a window. */
        const val FAILURES = 30
        const val WINDOW_MS = 60_000L
    }
}

/**
 * The Lounge's sessions (the red team, 2026-10): each cookie's hash with the time it was given, kept [maxAgeMs] — the
 * cookie's own Max-Age — and at most [max] of them, the oldest let go first, so a passcode given a thousand times keeps
 * no thousand sessions. Not thread-safe: the door asks it under one lock.
 */
class LoungeSessions(private val max: Int = MAX, private val maxAgeMs: Long = MAX_AGE_MS) {
    private val given = LinkedHashMap<String, Long>()

    val size: Int get() = given.size

    /** [hash] given at [now]. */
    fun add(hash: String, now: Long) {
        given.remove(hash)
        given[hash] = now
        while (given.size > max) given.remove(given.keys.first())
    }

    /** Whether [hash] is a session at [now]; one past its age is let go as it is asked about. */
    fun has(hash: String, now: Long): Boolean {
        val at = given[hash] ?: return false
        if (now - at >= maxAgeMs) {
            given.remove(hash)
            return false
        }
        return true
    }

    fun clear() = given.clear()

    companion object {
        /** Sessions kept at once: a friend's browser holds one, and a whole Lounge is a few dozen friends. */
        const val MAX = 500
        const val MAX_AGE_MS = 30L * 24 * 60 * 60_000
    }
}
