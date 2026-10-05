package com.kaiharimoto.mastertool.core.duel.effects

import kotlinx.serialization.Serializable
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * What an engine-made entry knew beyond its action ([FxTag.memo]), so the log alone folds to the engine's `FxState`
 * (`FxFold`): the batch it happened in, a chain link's bindings and declarations (on its `ChainAdd`), a restriction (on
 * its `Lock`), a Level change, a "Normal Summon 1 more" or a declaration (on a note), a negation of the effect only (on
 * its `Negate`), a token's Level and kind (on its `Token`), and which summon a material went to. Every field is optional:
 * an older build skips the key, and a newer build's keys are skipped here.
 */
@Serializable
data class FxMemo(
    /** The batch of things that happened at the same time (D.md §2.3): what "last" and missing the timing read. */
    val batch: Int? = null,
    /** On a link's `ChainAdd`: what its costs and targets bound, by name ([Pick.TARGETS], `targets#i`, a pick's `bind`). */
    val bound: Map<String, List<Int>> = emptyMap(),
    /** On a link's `ChainAdd` or a declaration's note: what was declared, by the name it was bound under. */
    val declared: Map<String, Declared> = emptyMap(),
    /** On a note: a Level changed ([Op.ChangeLevel]). */
    val level: LevelChange? = null,
    /** On a note: "you can Normal Summon 1 more" monster this matches ([Op.NormalSummonAgain]). */
    val grant: ReadFilter? = null,
    /** On a `Lock`: the restriction it writes down ([Op.Restrict], [Effect.leaves]). */
    val restriction: Restriction? = null,
    /** On a `Negate`: the effect is negated, not the activation (the card stays, the link does nothing). */
    val effectOnly: Boolean = false,
    /** On a `Token`: what the table's token does not hold. */
    val token: FxToken? = null,
    /** On a material's move: the summon it is material for. */
    val summon: ProcKind? = null,
)

/** A token's facts beyond its name, ATK and DEF ([Op.Token]): what Synchro and Xyz Summons and filters read. */
@Serializable
data class FxToken(val level: Int? = null, val attribute: CardAttribute? = null, val race: String? = null)
