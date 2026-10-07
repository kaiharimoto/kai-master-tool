package com.kaiharimoto.neue.kit

/** [block] run holding [lock]'s monitor where there are threads (the desktop, Android); a browser page has one. */
internal expect fun <T> guarded(lock: Any, block: () -> T): T

/**
 * [value] to two decimal places, as a slider's caption shows it: the JVM's own formatting where there is one, so the
 * desk keeps its locale's decimal comma.
 */
internal expect fun twoPlaces(value: Float): String
