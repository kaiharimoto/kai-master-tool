package com.kaiharimoto.neue.kit

internal actual fun <T> guarded(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun twoPlaces(value: Float): String = "%.2f".format(value)
