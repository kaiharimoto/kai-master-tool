package com.kaiharimoto.neue.duel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.releasesTyping
import com.kaiharimoto.neue.theme.Mu

/** The key each verb answers to, for the verb strip. */
internal val VERB_KEYS = mapOf(
    DuelVerb.ACTIVATE to DeskAction.DUEL_ACTIVATE, DuelVerb.SUMMON to DeskAction.DUEL_SUMMON, DuelVerb.SPECIAL to DeskAction.DUEL_SPECIAL,
    DuelVerb.SET to DeskAction.DUEL_SET, DuelVerb.POSITION to DeskAction.DUEL_POSITION, DuelVerb.FLIP to DeskAction.DUEL_FLIP,
    DuelVerb.GRAVE to DeskAction.DUEL_GRAVE, DuelVerb.BANISH to DeskAction.DUEL_BANISH, DuelVerb.BANISH_DOWN to DeskAction.DUEL_BANISH_DOWN,
    DuelVerb.HAND to DeskAction.DUEL_HAND, DuelVerb.DECK_TOP to DeskAction.DUEL_DECK_TOP, DuelVerb.DECK_BOTTOM to DeskAction.DUEL_DECK_BOTTOM,
    DuelVerb.EXTRA to DeskAction.DUEL_EXTRA, DuelVerb.ATTACH to DeskAction.DUEL_ATTACH, DuelVerb.REVEAL to DeskAction.DUEL_REVEAL,
    DuelVerb.COUNTER_UP to DeskAction.DUEL_COUNTER_UP, DuelVerb.COUNTER_DOWN to DeskAction.DUEL_COUNTER_DOWN, DuelVerb.TARGET to DeskAction.DUEL_TARGET,
    DuelVerb.ATTACK to DeskAction.DUEL_ATTACK,
)

/**
 * The inspector (1.0.78, kai: "the action guide gets shoved down … the card art is a bit too big … the
 * stats take up too much space"): the card read — its art at a modest size, its name and its whole
 * text, with no scrolling for most cards — and, pinned at the foot where nothing pushes it, the keys.
 * What a card can do is no longer here: it stands beside the card on the table ([VerbStrip]).
 * [fill] false lays it out for a drawer, which has no height to share.
 */
@Composable
internal fun DuelInspector(h: NeueHolders, duels: Duels, game: DuelGame, viewers: Set<Int>, modifier: Modifier = Modifier, fill: Boolean = true) {
    val c = Mu.colors
    val s = game.state
    val uid = (duels.hovered ?: duels.inspected)?.takeIf { it in s.cards }
    val body: @Composable () -> Unit = {
        if (uid == null) {
            Micro("The card", color = c.ink45)
            Help("Point at a card to read it here. Click it for what it can do; right-click does the obvious thing; drag puts it anywhere.")
        } else {
            InspectedCard(h, duels, game, viewers, uid)
        }
    }
    Column(modifier.releasesTyping()) {
        if (fill) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        } else {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        }
        KeyCheat(h)
    }
}

@Composable
private fun InspectedCard(h: NeueHolders, duels: Duels, game: DuelGame, viewers: Set<Int>, uid: Int) {
    val c = Mu.colors
    val s = game.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val inst = s.cards.getValue(uid)
        val sees = viewers.any { DuelSight.sees(s, uid, it) }
        val card = if (sees && !(inst.token && inst.code == 0)) h.builder.index.byId(CardId(inst.code)) else null
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(INSPECTOR_ART.dp).aspectRatio(CARD_RATIO)) {
                when {
                    card != null -> NeueCard(card, Modifier.fillMaxSize(), foil = h.neue.prefs.foil)
                    sees -> TokenFace(duels.catalog.nameOf(inst), Modifier.fillMaxSize())
                    else -> CardBack(Modifier.fillMaxSize())
                }
            }
        }
        H2(if (sees) duels.catalog.nameOf(inst) else "A face-down card", maxLines = 2)
        if (sees && inst.token && (inst.atk != null || inst.def != null)) Mono("ATK ${inst.atk ?: "?"} / DEF ${inst.def ?: "?"}", color = c.ink)
        if (card != null) Body(card.description, color = c.ink)
        // What the two players agreed about this card (1.0.79).
        if (sees && inst.code != 0) duels.rulings.forCode(inst.code).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.border(1.dp, c.ink).padding(horizontal = 4.dp)) { Mono("RULING", color = c.ink, size = 9.sp) }
                Small(r.text, Modifier.weight(1f), color = c.ink)
                com.kaiharimoto.neue.kit.IconButton(com.kaiharimoto.neue.kit.Icons.X, { duels.forgetRuling(r.id) }, size = 20.dp, label = "Forget the ruling")
            }
        }
        if (inst.under.isNotEmpty()) {
            Micro("Materials · ${inst.under.size}", color = c.ink70)
            inst.under.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small(duels.catalog.nameOf(s.cards.getValue(m)), Modifier.weight(1f), color = c.ink)
                    VerbChip("Detach") { duels.verb(m, DuelVerb.DETACH) }
                }
            }
        }
        if (duels.attaching == uid) Help("Now click the monster it goes under. Esc to stop.")
    }
}

/**
 * This turn's counts and the locks written down (1.0.79, Ai: "a tracker for running counts and locks"):
 * each seat's Summons and activations, and every lock with a way to lift it. Gone when there is nothing.
 */
@Composable
internal fun TurnTally(duels: Duels, game: DuelGame, viewer: Int? = null) {
    val c = Mu.colors
    val tally = remember(game, viewer) { duels.tally(viewer) } ?: return
    val s = game.state
    val lines = tally.words(s).dropLast(tally.locks.size)
    if (lines.isEmpty() && s.locks.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Micro("This turn", color = c.ink45)
        lines.forEach { Small(it, color = c.ink, maxLines = 2) }
        s.locks.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.border(1.dp, c.ink).padding(horizontal = 4.dp)) { Mono("LOCK", color = c.ink, size = 9.sp) }
                Small("${l.text} · ${DuelWords.untilWords(l.until)}", Modifier.weight(1f), color = c.ink, maxLines = 2)
                com.kaiharimoto.neue.kit.IconButton(com.kaiharimoto.neue.kit.Icons.X, { duels.act(DuelAction.Unlock(l.id)) }, size = 20.dp, label = "Lift the lock")
            }
        }
    }
    HRule()
}

/** The inspector's art: big enough to know the card, small enough that its text needs no scrolling. */
private const val INSPECTOR_ART = 150

@Composable
internal fun VerbChip(label: String, key: String? = null, strong: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Mu.colors
    Row(
        modifier.border(1.dp, if (strong) c.ink else c.ink25).background(if (strong) c.ink else c.paper)
            .cursorPointer(caption = label, showsWords = true).muClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro(label, Modifier.weight(1f, fill = false), color = if (strong) c.paper else c.ink)
        if (key != null) Mono(key, color = if (strong) c.paper else c.ink45)
    }
}

/** The keys that matter most, pinned at the inspector's foot, two to a row; a click folds them away. */
@Composable
private fun KeyCheat(h: NeueHolders) {
    val c = Mu.colors
    val shown = h.neue.prefs.duel.keysShown
    val rows = listOf(
        DeskAction.DUEL_DEFAULT to "Obvious", DeskAction.DUEL_SUMMON to "Summon", DeskAction.DUEL_SET to "Set",
        DeskAction.DUEL_ACTIVATE to "Activate", DeskAction.DUEL_GRAVE to "To GY", DeskAction.DUEL_BANISH to "Banish",
        DeskAction.DUEL_HAND to "To hand", DeskAction.DUEL_DRAW to "Draw", DeskAction.DUEL_NEXT_PHASE to "Next phase",
        DeskAction.DUEL_END_TURN to "End turn", DeskAction.DUEL_COMMAND to "Command", DeskAction.UNDO to "Undo",
    ).mapNotNull { (a, words) ->
        DeskShortcuts.all.firstOrNull { it.action == a && (it.scope == com.kaiharimoto.mastertool.core.input.DeskScope.DUEL || a == DeskAction.UNDO) }?.chord?.let { it to words }
    }
    Column(Modifier.fillMaxWidth()) {
        HRule()
        Row(
            Modifier.fillMaxWidth().cursorPointer(caption = if (shown) "Hide the keys" else "Show the keys")
                .muClickable { h.neue.update { it.copy(duel = it.duel.copy(keysShown = !shown)) } }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro("Keys", Modifier.weight(1f), color = c.ink70)
            Micro(if (shown) "Hide" else "Show", color = c.ink45)
        }
        if (shown) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (chord, words) ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Kbd(DeskShortcuts.kbd(chord), Modifier.widthIn(min = 36.dp))
                                Small(words, color = c.ink70, maxLines = 1)
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

