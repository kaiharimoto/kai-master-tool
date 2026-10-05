package com.kaiharimoto.neue.duel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskScope
import com.kaiharimoto.neue.kit.IconButton
import kotlin.math.roundToInt
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
import com.kaiharimoto.mastertool.core.duel.text.DuelLetters
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.effects.DuelCardEffects
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

/** The key each verb answers to, for the verb strip: [DuelLetters.KEYS], the one list of the verb keys. */
internal val VERB_KEYS: Map<DuelVerb, DeskAction> = DuelLetters.KEYS

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
    // The focus's card while the keys lead (1.0.87): the card is read without a mouse.
    val uid = duels.reading()?.takeIf { it in s.cards }
    val body: @Composable (room: Dp?) -> Unit = { room ->
        if (uid == null) {
            Micro("The card", color = c.ink45)
            Help("Point at a card to read it here. Click it for what it can do; right-click does the default action; drag puts it anywhere.")
        } else {
            InspectedCard(h, duels, game, viewers, uid, room)
        }
    }
    Column(modifier.releasesTyping()) {
        if (fill) {
            // The column's own height, measured, is what the art may grow into (1.0.87).
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val room = maxHeight - 24.dp
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body(room) }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body(null) }
        }
        KeyCheat(h)
    }
}

@Composable
private fun InspectedCard(h: NeueHolders, duels: Duels, game: DuelGame, viewers: Set<Int>, uid: Int, room: Dp?) {
    val s = game.state
    val inst = s.cards.getValue(uid)
    val sees = viewers.any { DuelSight.sees(s, uid, it) }
    val card = if (sees && !(inst.token && inst.code == 0)) h.builder.index.byId(CardId(inst.code)) else null
    // The words are measured first; the art takes what height they leave (1.0.87, kai: "the card art should
    // grow to fill leftover space") — never narrower than [INSPECTOR_ART] unless the column is, never wider
    // than the column, always 59:86. Past the minimum the column scrolls as before; the keys stay pinned.
    SubcomposeLayout { cons ->
        val gap = 8.dp.roundToPx()
        val width = cons.maxWidth
        val words = subcompose("words") { InspectedWords(duels, game, uid, sees, card) }
            .map { it.measure(Constraints(maxWidth = width)) }
        val wordsH = words.sumOf { it.height }
        val least = minOf(INSPECTOR_ART.dp.roundToPx(), width)
        val artW = if (room == null) least else {
            val spare = room.roundToPx() - wordsH - gap
            (spare * CARD_RATIO).toInt().coerceIn(least, width)
        }
        val artH = (artW / CARD_RATIO).roundToInt()
        val art = subcompose("art") {
            Box(Modifier.fillMaxSize()) {
                when {
                    card != null -> NeueCard(card, Modifier.fillMaxSize(), foil = h.neue.prefs.foil)
                    sees -> TokenFace(duels.catalog.nameOf(inst), Modifier.fillMaxSize())
                    else -> CardBack(Modifier.fillMaxSize())
                }
            }
        }.map { it.measure(Constraints.fixed(artW, artH)) }
        layout(width, artH + gap + wordsH) {
            art.forEach { it.place((width - artW) / 2, 0) }
            var y = artH + gap
            words.forEach { it.place(0, y); y += it.height }
        }
    }
}

/** Everything the inspector says under the art: name, text, rulings, materials. */
@Composable
private fun InspectedWords(duels: Duels, game: DuelGame, uid: Int, sees: Boolean, card: com.kaiharimoto.mastertool.core.model.Card?) {
    val c = Mu.colors
    val s = game.state
    val inst = s.cards.getValue(uid)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        H2(if (sees) duels.catalog.nameOf(inst) else "A face-down card", maxLines = 2)
        if (sees && inst.token && (inst.atk != null || inst.def != null)) Mono("ATK ${inst.atk ?: "?"} / DEF ${inst.def ?: "?"}", color = c.ink)
        if (card != null) Body(card.description, color = c.ink)
        // Its effect written as code (Phase D step 2): the words, or Write its effect.
        if (card != null) DuelCardEffects(card)
        // What the two players agreed about this card (1.0.79).
        if (sees && inst.code != 0) duels.rulings.forCode(inst.code).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.border(1.dp, c.ink).padding(horizontal = 4.dp)) { Mono("RULING", color = c.ink, size = 9.sp) }
                Small(r.text, Modifier.weight(1f), color = c.ink)
                IconButton(com.kaiharimoto.neue.kit.Icons.X, { duels.forgetRuling(r.id) }, size = 20.dp, label = "Forget the ruling")
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
                IconButton(com.kaiharimoto.neue.kit.Icons.X, { duels.act(DuelAction.Unlock(l.id)) }, size = 20.dp, label = "Lift the lock")
            }
        }
    }
    HRule()
}

/** The four arrows, as the key cheat writes them. */
private const val ARROWS = "← ↑ → ↓"

/**
 * The inspector's least art: big enough to know the card, small enough that its text needs no scrolling.
 * Where the column has height to spare the art grows past it, up to the column's width (1.0.87).
 */
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

/** The keys [KeyCheat] shows, as `chord to words`, the arrows first. */
private fun keyRows(): List<Pair<String, String>> = listOf(
    DeskAction.DUEL_DEFAULT to "Default", DeskAction.DUEL_SUMMON to "Summon", DeskAction.DUEL_SET to "Set",
    DeskAction.DUEL_ACTIVATE to "Activate", DeskAction.DUEL_GRAVE to "To GY", DeskAction.DUEL_BANISH to "Banish",
    DeskAction.DUEL_HAND to "To hand", DeskAction.DUEL_DRAW to "Draw", DeskAction.DUEL_NEXT_PHASE to "Next phase",
    DeskAction.DUEL_END_TURN to "End turn", DeskAction.DUEL_COMMAND to "Command", DeskAction.UNDO to "Undo",
    // Command mode (1.0.87): the whole table without a mouse.
    DeskAction.DUEL_FOCUS_ACT to "Act on the focus", DeskAction.DUEL_PICK to "Pick up", DeskAction.DUEL_COORDINATES to "Coordinates",
).mapNotNull { (a, words) ->
    DeskShortcuts.all.firstOrNull { it.action == a && (it.scope == DeskScope.DUEL || a == DeskAction.UNDO) }?.chord?.let { DeskShortcuts.kbd(it) to words }
}.let { listOf(ARROWS to "Walk the table") + it }

/**
 * The keys a watcher has while Ai vs Ai is on the table (the design review, finding 1): Esc stops the match, or once it is
 * over takes the person back to their duel; the arrows walk the table to read its cards; the coordinates. A player's keys
 * would only be refused.
 */
private fun watcherRows(running: Boolean): List<Pair<String, String>> = listOfNotNull(
    DeskShortcuts.chordFor(DeskAction.DISMISS)?.let { DeskShortcuts.kbd(it) to if (running) "Stop the match" else "Back to your duel" },
    ARROWS to "Walk the table",
    DeskShortcuts.all.firstOrNull { it.action == DeskAction.DUEL_COORDINATES && it.scope == DeskScope.DUEL }?.chord?.let { DeskShortcuts.kbd(it) to "Coordinates" },
)

/** The keys that matter most, pinned at the inspector's foot, two to a row; a click folds them away. */
@Composable
private fun KeyCheat(h: NeueHolders) {
    val c = Mu.colors
    val shown = h.neue.prefs.duel.keysShown
    // The keys' words are the tables', which do not change while the app runs: read once (1.0.92).
    val all = remember(DeskShortcuts.all) {
        keyRows()
    }
    val rows = if (h.duel.spectating) watcherRows(h.duel.matches.running) else all
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
                                Kbd(chord, Modifier.widthIn(min = 36.dp))
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

