package com.kaiharimoto.neue.cards

/** A browser page has no files of its own: an own picture is never a `file:` address there. */
internal actual fun fileArt(address: String): Any? = null
