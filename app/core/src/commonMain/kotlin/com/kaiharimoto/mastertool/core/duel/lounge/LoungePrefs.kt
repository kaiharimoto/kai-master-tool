package com.kaiharimoto.mastertool.core.duel.lounge

import kotlinx.serialization.Serializable

/**
 * The Lounge on this computer (`docs/LOUNGE.md`): where its door listens and what kai is called there. This device's
 * own — a door is a computer's, not a deck's — and never Ai's to change: it opens the computer to the internet. The
 * passcode is not here; its hash is kept with the app's other secrets.
 */
@Serializable
data class LoungePrefs(
    /** The port the door listens on, on this computer. Cloudflare's tunnel points at `http://localhost:<port>`. */
    val port: Int = DEFAULT_PORT,
    /** Open to this computer's local network too (friends in the same house), not only to the tunnel. */
    val lan: Boolean = false,
    /** The address friends open, shown to copy and share: `https://duel.labrynth.info`. */
    val address: String = "",
    /** Run Cloudflare's tunnel (`cloudflared`) while the Lounge is open. */
    val tunnel: Boolean = true,
    /** kai's name in the Lounge. */
    val nick: String = "kai",
    /**
     * The most Ai may read and write at the Lounge's tables in a day, in tokens, all rooms together (L5): past it, Ai
     * stops where it is and says so. kai's money, so kai's call; 0 keeps Ai out of the Lounge.
     */
    val aiDailyTokens: Long = DEFAULT_AI_TOKENS,
) {
    companion object {
        const val DEFAULT_PORT = 47380
        /** About a dozen duels against a strong model in a day. */
        const val DEFAULT_AI_TOKENS = 2_000_000L
        val AI_BUDGETS = listOf(0L, 500_000L, 2_000_000L, 5_000_000L, 20_000_000L)
    }
}

/** What Ai has spent at the Lounge's tables today: kept in `<data>/lounge/ai-spend.json`, started afresh each day. */
@Serializable
data class AiSpend(
    /** The day it counts, as `2026-10-07` on kai's computer. */
    val day: String = "",
    val tokens: Long = 0,
) {
    /** This spend on [today]: a new day starts at nothing. */
    fun on(today: String): AiSpend = if (day == today) this else AiSpend(today, 0)

    fun add(today: String, n: Long): AiSpend = on(today).let { it.copy(tokens = it.tokens + n.coerceAtLeast(0)) }

    /** Whether [cap] is spent on [today]. */
    fun spent(today: String, cap: Long): Boolean = on(today).tokens >= cap
}
