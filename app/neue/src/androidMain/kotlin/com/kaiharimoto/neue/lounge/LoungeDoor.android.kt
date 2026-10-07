package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs
import com.kaiharimoto.mastertool.core.model.Card
import java.io.File

/** A tablet or a phone never opens the Lounge: it is kai's computer's. */
actual object LoungeDoor {
    actual val available: Boolean = false
    actual fun open(host: LoungeHost, prefs: LoungePrefs, passcodeHash: () -> String?, pool: () -> List<Card>, original: (Int) -> File?, artCache: File): String? =
        "The Lounge opens on a computer"
    actual fun close() = Unit
    actual fun tunnel(token: String, line: (String) -> Unit, ended: (Int) -> Unit): String? = "The Lounge opens on a computer"
    actual fun stopTunnel() = Unit
}
