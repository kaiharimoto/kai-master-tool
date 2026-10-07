package com.kaiharimoto.neue.cards

internal actual fun fileArt(address: String): Any? = runCatching { java.io.File(java.net.URI(address)) }.getOrNull()
