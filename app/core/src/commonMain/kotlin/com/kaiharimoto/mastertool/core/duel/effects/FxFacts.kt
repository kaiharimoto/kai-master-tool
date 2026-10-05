package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import kotlin.concurrent.Volatile
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/** A Link Monster's arrows, as its controller faces the table. */
enum class LinkArrow(val dx: Int, val dy: Int) {
    TOP_LEFT(-1, 1), TOP(0, 1), TOP_RIGHT(1, 1),
    LEFT(-1, 0), RIGHT(1, 0),
    BOTTOM_LEFT(-1, -1), BOTTOM(0, -1), BOTTOM_RIGHT(1, -1);

    companion object {
        /** The pool's words ("Top", "Bottom-Left"…), any case; null for a word it does not know. */
        fun parse(word: String): LinkArrow? = when (word.trim().lowercase().replace('_', '-').replace(' ', '-')) {
            "top-left" -> TOP_LEFT
            "top" -> TOP
            "top-right" -> TOP_RIGHT
            "left" -> LEFT
            "right" -> RIGHT
            "bottom-left" -> BOTTOM_LEFT
            "bottom" -> BOTTOM
            "bottom-right" -> BOTTOM_RIGHT
            else -> null
        }
    }
}

/**
 * A card's printed facts as the engine reads them (D.md §2.2): from the pool, never from the script. [code] is the
 * canonical passcode. [level] is a monster's Level — never an Xyz Monster's Rank ([rank]) nor a Link Monster's rating
 * ([link]), which have none. [sub] is a Spell's or Trap's kind as printed ("Quick-Play", "Counter"…).
 */
data class FxCard(
    val code: Int,
    val name: String,
    val type: CardType,
    val frames: Set<CardFrame> = emptySet(),
    val sub: String? = null,
    val level: Int? = null,
    val rank: Int? = null,
    val link: Int? = null,
    val arrows: Set<LinkArrow> = emptySet(),
    val attribute: CardAttribute? = null,
    val race: String? = null,
    val atk: Int? = null,
    val def: Int? = null,
    val scale: Int? = null,
) {
    val monster: Boolean get() = type == CardType.MONSTER
    val tuner: Boolean get() = CardFrame.TUNER in frames
    val pendulum: Boolean get() = CardFrame.PENDULUM in frames

    /** A Fusion, Synchro, Xyz or Link Monster: dealt into the Extra Deck, never Normal Summoned. */
    val extraDeck: Boolean get() = frames.any { it == CardFrame.FUSION || it == CardFrame.SYNCHRO || it == CardFrame.XYZ || it == CardFrame.LINK }

    /** A Normal Monster: no effects, so known to the engine without a script (D.md §5.5's `NONE`). */
    val normal: Boolean get() = CardFrame.NORMAL in frames && CardFrame.EFFECT !in frames

    /** The procedure an Extra Deck monster's frame names, or a Ritual Monster's. */
    val frameProc: ProcKind? get() = when {
        CardFrame.LINK in frames -> ProcKind.LINK
        CardFrame.XYZ in frames -> ProcKind.XYZ
        CardFrame.SYNCHRO in frames -> ProcKind.SYNCHRO
        CardFrame.FUSION in frames -> ProcKind.FUSION
        CardFrame.RITUAL in frames -> ProcKind.RITUAL
        else -> null
    }

    fun isSpellSub(word: String): Boolean = sub.equals(word, ignoreCase = true)
}

/**
 * The pool's facts by passcode, any printing: what an `FxTable` reads a card by. Built over the pool ([over]), a list
 * ([of]) or the duel's catalog ([catalog]). A passcode the source does not know has no facts.
 */
class FxFacts(private val lookup: (Int) -> FxCard?, private val canon: (Int) -> Int = { it }) {
    operator fun get(code: Int): FxCard? = lookup(code)

    /** The canonical passcode of any printing: alternate artworks are the same card. */
    fun canonical(code: Int): Int = canon(code)

    /** A card instance's facts: a token's from itself, a card's from its passcode. */
    fun of(inst: CardInst): FxCard? = if (inst.token && (inst.code == 0 || get(inst.code) == null)) token(inst) else get(inst.code)

    companion object {
        val NONE = FxFacts({ null })

        /** One card's facts, as the pool has them. */
        fun of(card: Card): FxCard {
            val frame = card.frameType.lowercase()
            val type = when {
                frame == "spell" || card.type.contains("Spell", ignoreCase = true) -> CardType.SPELL
                frame == "trap" || card.type.contains("Trap", ignoreCase = true) -> CardType.TRAP
                else -> CardType.MONSTER
            }
            val frames = buildSet {
                if (type == CardType.MONSTER) {
                    if (frame.startsWith("normal")) add(CardFrame.NORMAL)
                    if (frame.startsWith("effect")) add(CardFrame.EFFECT)
                    if (frame.contains("ritual")) add(CardFrame.RITUAL)
                    if (frame.contains("fusion")) add(CardFrame.FUSION)
                    if (frame.contains("synchro")) add(CardFrame.SYNCHRO)
                    if (frame.contains("xyz")) add(CardFrame.XYZ)
                    if (frame.contains("link")) add(CardFrame.LINK)
                    if (frame.contains("pendulum")) add(CardFrame.PENDULUM)
                    if (frame.contains("token")) add(CardFrame.TOKEN)
                    if (card.type.contains("Tuner", ignoreCase = true)) add(CardFrame.TUNER)
                    // An Extra Deck or Ritual monster with text is an Effect Monster unless its type line says Normal.
                    val effect = card.type.contains("Effect", ignoreCase = true)
                    if (effect && CardFrame.NORMAL !in this) add(CardFrame.EFFECT)
                }
            }
            val monster = type == CardType.MONSTER
            val xyz = CardFrame.XYZ in frames
            val link = CardFrame.LINK in frames
            return FxCard(
                code = card.id.value,
                name = card.name,
                type = type,
                frames = frames,
                sub = if (!monster) card.race?.takeIf { it.isNotBlank() } else null,
                level = card.level?.takeIf { monster && !xyz && !link },
                rank = card.level?.takeIf { monster && xyz },
                link = card.linkValue?.takeIf { monster && link },
                arrows = if (link) card.linkMarkers.mapNotNull(LinkArrow::parse).toSet() else emptySet(),
                attribute = card.attribute.takeIf { monster && it != CardAttribute.UNKNOWN },
                race = card.race?.takeIf { monster && it.isNotBlank() },
                atk = card.atk?.takeIf { monster },
                def = card.def?.takeIf { monster && !link },
                scale = card.pendulumScale?.takeIf { CardFrame.PENDULUM in frames },
            )
        }

        /**
         * The duel's catalog's view of a card ([DuelCardInfo]): what a table without the pool knows — the puzzles'.
         * It has no Link arrows, so a Link Monster read this way points nowhere.
         */
        fun of(code: Int, info: DuelCardInfo): FxCard {
            val type = when (info.kind) {
                CardKind.SPELL, CardKind.FIELD_SPELL -> CardType.SPELL
                CardKind.TRAP -> CardType.TRAP
                else -> CardType.MONSTER
            }
            val line = info.typeLine?.lowercase() ?: ""
            val frames = buildSet {
                if (type == CardType.MONSTER) {
                    when {
                        info.kind == CardKind.TOKEN -> add(CardFrame.TOKEN)
                        info.link -> add(CardFrame.LINK)
                        info.xyz -> add(CardFrame.XYZ)
                        "synchro" in line -> add(CardFrame.SYNCHRO)
                        "fusion" in line -> add(CardFrame.FUSION)
                    }
                    if ("ritual" in line) add(CardFrame.RITUAL)
                    if (info.pendulum) add(CardFrame.PENDULUM)
                    if ("tuner" in line) add(CardFrame.TUNER)
                    if ("normal" in line && "effect" !in line) add(CardFrame.NORMAL)
                    else if (info.kind != CardKind.TOKEN) add(CardFrame.EFFECT)
                }
            }
            return FxCard(
                code = code,
                name = info.name,
                type = type,
                frames = frames,
                sub = info.sub.takeIf { type != CardType.MONSTER } ?: if (info.kind == CardKind.FIELD_SPELL) "Field" else null,
                level = info.level?.takeIf { type == CardType.MONSTER && !info.xyz && !info.link },
                rank = info.level?.takeIf { info.xyz },
                link = info.linkRating,
                attribute = info.attribute?.let { a -> CardAttribute.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } },
                race = info.race,
                atk = info.atk,
                def = info.def.takeIf { !info.link },
                scale = info.scale,
            )
        }

        /** A token's facts from the table: its name, ATK and DEF; no Level unless the engine made it (`FxState.tokens`). */
        fun token(inst: CardInst): FxCard = FxCard(
            code = inst.code,
            name = inst.name ?: "Token",
            type = CardType.MONSTER,
            frames = setOf(CardFrame.TOKEN, CardFrame.NORMAL),
            atk = inst.atk,
            def = inst.def,
        )

        /** Facts over the pool: any printing resolves to its card ([CardIdentity.canonical]). */
        fun over(cards: (CardId) -> Card?): FxFacts {
            val memo = FactMemo { code -> cards(CardId(code))?.let(::of) }
            return FxFacts(lookup = memo::get, canon = { code -> CardIdentity.canonical(CardId(code), cards).value })
        }

        /** Facts over [cards] and their alternate artworks: the tests' and the fixtures'. */
        fun of(cards: Iterable<Card>): FxFacts {
            val byId = HashMap<Int, Card>()
            cards.forEach { c -> c.passcodes.forEach { byId[it.value] = c } }
            return over { byId[it.value] }
        }

        /** Facts from the duel's catalog, when the pool is not at hand (a puzzle's table). */
        fun catalog(info: (Int) -> DuelCardInfo?): FxFacts = FxFacts(FactMemo { code -> info(code)?.let { of(code, it) } }::get)
    }
}

/**
 * Facts read once a passcode and remembered, a miss too: a map replaced whole on every miss, never changed in place, so
 * the goldfish's workers share it safely (`CachedCatalog`'s way). A deck names a few dozen passcodes.
 */
private class FactMemo(private val source: (Int) -> FxCard?) {
    @Volatile
    private var known: Map<Int, FxCard?> = emptyMap()

    fun get(code: Int): FxCard? {
        val now = known
        if (code in now) return now[code]
        val facts = source(code)
        known = HashMap<Int, FxCard?>(now.size * 2 + 4).apply { putAll(now); put(code, facts) }
        return facts
    }
}
