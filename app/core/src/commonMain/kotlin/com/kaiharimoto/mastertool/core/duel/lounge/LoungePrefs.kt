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
) {
    companion object {
        const val DEFAULT_PORT = 47380
    }
}
