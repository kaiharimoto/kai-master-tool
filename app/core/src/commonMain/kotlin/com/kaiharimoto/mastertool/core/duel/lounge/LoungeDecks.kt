package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import kotlinx.serialization.Serializable

/**
 * A member's decks in the Lounge (`docs/LOUNGE.md`), kept on kai's computer so they follow the friend to any browser:
 * what was uploaded, pasted or edited, read once into a deck, and kept as `.ydk` text with its name.
 */
object LoungeDecks {
    /** The most decks one member keeps, and the longest text one deck may be. */
    const val MAX_DECKS = 40
    const val MAX_TEXT = 64 * 1024

    /** A deck kept: its name and its `.ydk`/`.ydkx` text. */
    @Serializable
    data class Kept(val name: String, val text: String)

    /** [text] read as a deck — a `.ydk`, a `.ydkx`, or a `ydke://` code — or null when it is none of them. */
    fun read(text: String): Deck? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) return null
        if (t.startsWith("ydke://")) return YdkeCodec.decode(t)
        val doc = runCatching { YdkCodec.parse(t).document }.getOrNull() ?: return null
        return doc.deck.takeIf { it.main.isNotEmpty() || it.extra.isNotEmpty() || it.side.isNotEmpty() }
    }

    /** [deck] as the text kept for it: a `.ydkx` keeps its payload as it came, anything else is written as `.ydk`. */
    fun text(original: String, deck: Deck): String =
        if (original.trim().startsWith("ydke://")) YdkCodec.write(YdkDocument(deck)) else original.trim() + "\n"

    fun info(id: String, name: String, deck: Deck): DeckInfo = DeckInfo(id, name, deck.main.size, deck.extra.size, deck.side.size)

    /** A deck's name: what was given, else the file's, never empty, never long. */
    fun name(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(60).ifEmpty { "Untitled deck" }
}
