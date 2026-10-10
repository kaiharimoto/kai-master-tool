package com.kaiharimoto.mastertool.core.duel.search.ladder

import com.kaiharimoto.mastertool.core.duel.effects.FxEntry
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxReport
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.sync.Sha256

/** One of the ladder's decks: its name, its Main Deck and its Extra Deck, by passcode. */
data class BenchDeck(val id: String, val name: String, val main: List<Int>, val extra: List<Int>)

/**
 * The ladder's bench (Phase E, E.4; `docs/phases/E.md` §4.5): decks whose every card is written, so the engine plays all
 * of them and a rating measures the player, never the gaps in a library. They are made of the reference cards ([FxRef]:
 * fictional, in the reserved range, our own scripts), three copies at most, forty to a Main Deck — a searcher's engine, big
 * monsters with removal, and Rituals with a Fusion — each holding the two hand traps, so an answer is always possible.
 *
 * [print] names the bench as it is: a change to a deck or to any script is a new bench, and games on an older one are kept
 * but never counted with the new.
 */
object Bench {
    private fun deck(vararg parts: Pair<Int, Int>): List<Int> = parts.flatMap { (code, n) -> List(n) { code } }

    val decks: List<BenchDeck> = listOf(
        BenchDeck(
            "scouts", "Example Scouts",
            deck(
                FxRef.SCOUT to 3, FxRef.BEACON to 3, FxRef.PAWN to 3, FxRef.KNIGHT to 3, FxRef.LAMP to 3, FxRef.WARDEN to 3,
                FxRef.ECHO to 2, FxRef.WARD to 3, FxRef.VEIL to 2, FxRef.CALL to 3, FxRef.RALLY to 2, FxRef.OATH to 3,
                FxRef.OFFERING to 2, FxRef.FLASH to 3, FxRef.SNARE to 2,
            ),
            deck(FxRef.BRIDGE to 2, FxRef.ARCH to 2, FxRef.SPIDER to 2, FxRef.PALADIN to 2, FxRef.REGENT to 2, FxRef.LORD to 2, FxRef.CHIMERA to 1),
        ),
        BenchDeck(
            "giants", "Example Giants",
            deck(
                FxRef.KNIGHT to 3, FxRef.COLOSSUS to 3, FxRef.PAWN to 3, FxRef.WARDEN to 3, FxRef.MANDATE to 3, FxRef.EMBER to 3,
                FxRef.SPRITE to 3, FxRef.TINKER to 2, FxRef.WARD to 2, FxRef.VEIL to 3, FxRef.PURGE to 2, FxRef.FLASH to 3,
                FxRef.CROSSROADS to 3, FxRef.SNARE to 2, FxRef.DENIAL to 2,
            ),
            deck(FxRef.PALADIN to 3, FxRef.REGENT to 3, FxRef.SPIDER to 2, FxRef.BRIDGE to 2),
        ),
        BenchDeck(
            "rites", "Example Rites",
            deck(
                FxRef.ORACLE to 3, FxRef.RITE to 3, FxRef.SCOUT to 3, FxRef.LAMP to 3, FxRef.SWING to 3, FxRef.ECHO to 3,
                FxRef.PAWN to 3, FxRef.BEACON to 2, FxRef.WARD to 3, FxRef.VEIL to 2, FxRef.FUSION to 2, FxRef.MILL to 3,
                FxRef.SIEVE to 2, FxRef.FLASH to 2, FxRef.CROSSROADS to 3,
            ),
            deck(FxRef.CHIMERA to 3, FxRef.BRIDGE to 2, FxRef.ARCH to 2, FxRef.SPIDER to 2, FxRef.REGENT to 2, FxRef.PALADIN to 2),
        ),
    )

    fun deck(id: String): BenchDeck? = decks.firstOrNull { it.id == id }

    /** The written effects the bench plays by: every reference script, trusted as written (none has a warning). */
    val kit: GoldfishKit by lazy {
        val byCode = HashMap<Int, Card>()
        FxRef.cards.forEach { c -> c.passcodes.forEach { byCode[it.value] = c } }
        val scripts = FxRef.cards.mapNotNull { c -> runCatching { FxRef.script(c.id.value) }.getOrNull() }
        val trust = FxTrust(
            scripts.associate { s -> s.card to FxEntry(s.card, compiled = FxRead.Script(s), report = FxReport(s.card, emptyList())) },
            canonical = FxRef.facts::canonical,
        )
        GoldfishKit.of(trust, FxRef.cards)
    }

    /** The share of the bench's cards the engine plays (written, or a Normal Monster): 1 when every card is. */
    fun coverage(): Double {
        val all = decks.flatMap { it.main + it.extra }.distinct()
        return if (all.isEmpty()) 1.0 else all.count { kit.known(it) }.toDouble() / all.size
    }

    /** What a seat on the ladder guesses the other holds: the bench's decks, each as likely, never which one it faces. */
    val prior: Prior = Prior.Field(decks.map { Prior.Strategy(1.0 / decks.size, listOf(it.main)) })

    /** The bench as it is: its decks and the hash of every script they play. */
    val print: String by lazy {
        val codes = decks.flatMap { it.main + it.extra }.distinct().sorted()
        val text = decks.joinToString("\n") { d -> "${d.id}:${d.main.joinToString(",")}|${d.extra.joinToString(",")}" } + "\n" +
            codes.joinToString(",") { c -> "$c=${kit.trust.hash(c) ?: "-"}" }
        Sha256.hex(text).take(12)
    }
}
