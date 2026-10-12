package com.kaiharimoto.mastertool.core.duel.net

import com.kaiharimoto.mastertool.core.duel.lounge.Lockouts

/**
 * Wrong codes at a table on the local network (the red team, 2026-10): a table's code carries a sixteen-bit secret,
 * which a device on the same Wi-Fi could otherwise try all of in seconds. Each address has [FREE] wrong codes; the next
 * makes it wait [FIRST_WAIT_MS], doubling with each further one up to an hour, and the right one clears it. While it
 * waits, a Hello from it is turned away unread — so the wait says nothing of whether its code was right.
 * Not thread-safe: the table asks it on one thread.
 */
class HelloGate(private val lockouts: Lockouts = Lockouts(free = FREE, firstWaitMs = FIRST_WAIT_MS)) {

    /** Why [hello] from [from] is turned away at a table whose secret is [secret], at [now]; null when it may sit. */
    fun judge(hello: Wire.Hello, secret: Int, from: String, now: Long): String? {
        val wait = lockouts.waitMs(from, now)
        if (wait > 0) return "Too many wrong codes from this device. Try again in ${(wait + 999) / 1000} seconds."
        val why = DuelHost.knows(hello, secret)
        // An app of another version is told so before its code is read: that is not a guess.
        if (hello.proto == Wire.PROTO) {
            if (hello.secret != secret) lockouts.failed(from, now) else lockouts.passed(from)
        }
        return why
    }

    companion object {
        /** Wrong codes an address makes for free: the fifth makes it wait. */
        const val FREE = 4
        const val FIRST_WAIT_MS = 60_000L
    }
}
