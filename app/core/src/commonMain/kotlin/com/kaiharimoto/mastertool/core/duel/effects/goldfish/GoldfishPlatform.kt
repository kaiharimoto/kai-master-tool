package com.kaiharimoto.mastertool.core.duel.effects.goldfish

/** How many cores this machine has (§5.7: one worker per core, less one). */
internal expect fun goldfishCores(): Int

/** [block] run to its end, blocking the caller: an instrument's thread (never the frame's). */
internal expect fun <T> goldfishBlocking(block: suspend () -> T): T
