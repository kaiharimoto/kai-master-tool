package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs
import com.kaiharimoto.mastertool.core.model.Card
import java.io.File

/**
 * The Lounge's door: a web server friends' browsers reach, and Cloudflare's tunnel that carries `duel.labrynth.info`
 * to it (`docs/LOUNGE.md`). A computer's alone — on the desk; a tablet or a phone never opens one.
 */
expect object LoungeDoor {
    val available: Boolean

    /** Cloudflare's `cloudflared` is installed where the tunnel can find it. */
    val cloudflaredFound: Boolean

    /** The open door's id (`LoungeProbe`), or null while it is closed. */
    val door: String?

    /** Opens the door for [host]; the problem in words, or null when it is open. */
    fun open(host: LoungeHost, prefs: LoungePrefs, passcodeHash: () -> String?, pool: () -> List<Card>, original: (Int) -> File?, artCache: File): String?

    fun close()

    /** Runs Cloudflare's tunnel with [token]; each line it logs to [line], its end to [ended]. The problem, or null. */
    fun tunnel(token: String, line: (String) -> Unit, ended: (Int) -> Unit): String?

    fun stopTunnel()
}
