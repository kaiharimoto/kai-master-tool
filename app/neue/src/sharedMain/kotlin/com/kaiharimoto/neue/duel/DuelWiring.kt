package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page

private val VERBS = mapOf(
    DeskAction.DUEL_DEFAULT to DuelVerb.DEFAULT,
    DeskAction.DUEL_ACTIVATE to DuelVerb.ACTIVATE,
    DeskAction.DUEL_SUMMON to DuelVerb.SUMMON,
    DeskAction.DUEL_SPECIAL to DuelVerb.SPECIAL,
    DeskAction.DUEL_SET to DuelVerb.SET,
    DeskAction.DUEL_POSITION to DuelVerb.POSITION,
    DeskAction.DUEL_FLIP to DuelVerb.FLIP,
    DeskAction.DUEL_GRAVE to DuelVerb.GRAVE,
    DeskAction.DUEL_BANISH to DuelVerb.BANISH,
    DeskAction.DUEL_BANISH_DOWN to DuelVerb.BANISH_DOWN,
    DeskAction.DUEL_HAND to DuelVerb.HAND,
    DeskAction.DUEL_DECK_TOP to DuelVerb.DECK_TOP,
    DeskAction.DUEL_DECK_BOTTOM to DuelVerb.DECK_BOTTOM,
    DeskAction.DUEL_EXTRA to DuelVerb.EXTRA,
    DeskAction.DUEL_ATTACH to DuelVerb.ATTACH,
    DeskAction.DUEL_REVEAL to DuelVerb.REVEAL,
    DeskAction.DUEL_COUNTER_UP to DuelVerb.COUNTER_UP,
    DeskAction.DUEL_COUNTER_DOWN to DuelVerb.COUNTER_DOWN,
    DeskAction.DUEL_TARGET to DuelVerb.TARGET,
)

private val ZONES = mapOf(
    DeskAction.DUEL_ZONE_1 to (ZoneKind.MONSTER to 0),
    DeskAction.DUEL_ZONE_2 to (ZoneKind.MONSTER to 1),
    DeskAction.DUEL_ZONE_3 to (ZoneKind.MONSTER to 2),
    DeskAction.DUEL_ZONE_4 to (ZoneKind.MONSTER to 3),
    DeskAction.DUEL_ZONE_5 to (ZoneKind.MONSTER to 4),
    DeskAction.DUEL_ZONE_S1 to (ZoneKind.SPELL to 0),
    DeskAction.DUEL_ZONE_S2 to (ZoneKind.SPELL to 1),
    DeskAction.DUEL_ZONE_S3 to (ZoneKind.SPELL to 2),
    DeskAction.DUEL_ZONE_S4 to (ZoneKind.SPELL to 3),
    DeskAction.DUEL_ZONE_S5 to (ZoneKind.SPELL to 4),
    DeskAction.DUEL_ZONE_EMZ_LEFT to (ZoneKind.EMZ to 0),
    DeskAction.DUEL_ZONE_EMZ_RIGHT to (ZoneKind.EMZ to 1),
    DeskAction.DUEL_ZONE_FIELD to (ZoneKind.FIELD to 0),
)

/** The duel's keys (`DeskShortcuts`' Duelling rows), run on the window's holders. */
internal fun runDuel(h: NeueHolders, action: DeskAction) {
    val duels = h.duel
    if (action == DeskAction.DUEL_NEW) { duels.setupOpen = true; return }
    if (duels.replay != null) {
        when (action) {
            DeskAction.REPLAY_BACK -> duels.step(ReplayUnit.GROUP, -1)
            DeskAction.REPLAY_FORWARD -> duels.step(ReplayUnit.GROUP, 1)
            DeskAction.REPLAY_BACK_PHASE -> duels.step(ReplayUnit.PHASE, -1)
            DeskAction.REPLAY_FORWARD_PHASE -> duels.step(ReplayUnit.PHASE, 1)
            DeskAction.REPLAY_BACK_TURN -> duels.step(ReplayUnit.TURN, -1)
            DeskAction.REPLAY_FORWARD_TURN -> duels.step(ReplayUnit.TURN, 1)
            DeskAction.REPLAY_START -> duels.seek(0)
            DeskAction.REPLAY_END -> duels.seek(Int.MAX_VALUE)
            DeskAction.REPLAY_PLAY -> duels.play(1)
            DeskAction.REPLAY_DELETE -> duels.deleteStep()
            DeskAction.REPLAY_BRANCH -> duels.branch()
            else -> Unit
        }
        return
    }
    val game = duels.shown ?: return
    val s = game.state
    VERBS[action]?.let { verb ->
        // The card under the pointer, else the one selected, else the one being read.
        val uid = duels.hovered ?: duels.selection.singleOrNull() ?: duels.inspected ?: return
        if (uid !in s.cards) return
        val mine = s.solo || duels.seatFor(uid) == duels.bottom
        when {
            verb == DuelVerb.TARGET || (verb == DuelVerb.DEFAULT && !mine) -> duels.verb(uid, DuelVerb.TARGET, seat = duels.bottom)
            else -> duels.verb(uid, verb)
        }
        return
    }
    ZONES[action]?.let { (kind, index) ->
        val p = duels.placed
        // Shift and a number is a Spell & Trap Zone; a plain number is the zone of the kind just placed in.
        val k = if (kind == ZoneKind.MONSTER && p?.kind == ZoneKind.SPELL) ZoneKind.SPELL else kind
        duels.replace(k, index)
        return
    }
    when (action) {
        DeskAction.DUEL_DRAW -> duels.act(DuelAction.Draw(duels.bottom), duels.bottom)
        DeskAction.DUEL_SHUFFLE -> duels.act(DuelAction.Shuffle(duels.bottom, PileKind.DECK), duels.bottom)
        DeskAction.DUEL_NEXT_PHASE -> if (s.phase == DuelPhase.END) duels.act(DuelAction.EndTurn, s.active) else duels.act(DuelAction.Phase(s.phase.next()), s.active)
        DeskAction.DUEL_END_TURN -> duels.act(DuelAction.EndTurn, s.active)
        DeskAction.DUEL_LP -> duels.lpPad = if (duels.lpPad == null) duels.bottom else null
        DeskAction.DUEL_THINK -> duels.act(DuelAction.Thinking(duels.bottom, duels.bottom !in s.thinking), duels.bottom)
        DeskAction.DUEL_COMMAND -> duels.commandFocus++
        DeskAction.DUEL_CHAT -> duels.chatFocus++
        DeskAction.DUEL_SIDES -> h.neue.update { it.copy(duel = it.duel.copy(twoSided = !it.duel.twoSided)) }
        DeskAction.DUEL_SWAP -> duels.swap()
        DeskAction.DUEL_RESOLVE -> if (s.chain.isNotEmpty()) duels.act(DuelAction.ChainResolve)
        else -> Unit
    }
}

/** Esc and Back on the Duel page: one layer at a time, from the top. */
internal fun dismissDuel(h: NeueHolders): Boolean {
    if (h.neue.page != Page.DUEL || h.neue.hasTop || h.overlays.isOpen) return false
    val d = h.duel
    when {
        d.playing -> d.stopRequested = true
        d.combosOpen -> d.combosOpen = false
        d.setupOpen -> d.setupOpen = false
        d.libraryOpen -> d.libraryOpen = false
        d.lpPad != null -> d.lpPad = null
        d.attaching != null -> d.attaching = null
        d.drawer != null -> d.drawer = null
        d.strip != null -> d.strip = null
        d.verbsOpen -> d.verbsOpen = false
        d.selection.isNotEmpty() -> d.selection = emptySet()
        d.replay != null -> d.closeReplay()
        else -> return false
    }
    return true
}
