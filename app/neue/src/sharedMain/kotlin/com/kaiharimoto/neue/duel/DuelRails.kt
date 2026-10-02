package com.kaiharimoto.neue.duel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.kaiharimoto.neue.theme.Mu

/** The key each verb answers to, for the inspector's column. */
private val VERB_KEYS = mapOf(
    DuelVerb.ACTIVATE to DeskAction.DUEL_ACTIVATE, DuelVerb.SUMMON to DeskAction.DUEL_SUMMON, DuelVerb.SPECIAL to DeskAction.DUEL_SPECIAL,
    DuelVerb.SET to DeskAction.DUEL_SET, DuelVerb.POSITION to DeskAction.DUEL_POSITION, DuelVerb.FLIP to DeskAction.DUEL_FLIP,
    DuelVerb.GRAVE to DeskAction.DUEL_GRAVE, DuelVerb.BANISH to DeskAction.DUEL_BANISH, DuelVerb.BANISH_DOWN to DeskAction.DUEL_BANISH_DOWN,
    DuelVerb.HAND to DeskAction.DUEL_HAND, DuelVerb.DECK_TOP to DeskAction.DUEL_DECK_TOP, DuelVerb.DECK_BOTTOM to DeskAction.DUEL_DECK_BOTTOM,
    DuelVerb.EXTRA to DeskAction.DUEL_EXTRA, DuelVerb.ATTACH to DeskAction.DUEL_ATTACH, DuelVerb.REVEAL to DeskAction.DUEL_REVEAL,
    DuelVerb.COUNTER_UP to DeskAction.DUEL_COUNTER_UP, DuelVerb.COUNTER_DOWN to DeskAction.DUEL_COUNTER_DOWN, DuelVerb.TARGET to DeskAction.DUEL_TARGET,
)

/**
 * The inspector: the card under the pointer (or the one clicked last) read large, its words, and
 * every verb that fits it where it is — the default first, each with its key. Nothing modal: the
 * table stays live beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DuelInspector(h: NeueHolders, duels: Duels, game: DuelGame, viewers: Set<Int>, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val s = game.state
    val uid = (duels.hovered ?: duels.inspected)?.takeIf { it in s.cards }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (uid == null) {
            Micro("The card", color = c.ink45)
            Help("Point at a card to read it here. Right-click does the obvious thing; hold for every verb; drag puts it anywhere.")
            KeyCheat()
        } else {
            InspectedCard(h, duels, game, viewers, uid)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectedCard(h: NeueHolders, duels: Duels, game: DuelGame, viewers: Set<Int>, uid: Int) {
    val c = Mu.colors
    val s = game.state
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val inst = s.cards.getValue(uid)
        val sees = viewers.any { DuelSight.sees(s, uid, it) }
        val card = if (sees && !(inst.token && inst.code == 0)) h.builder.index.byId(CardId(inst.code)) else null
        val where = s.placeOf(uid)
        Box(Modifier.fillMaxWidth().widthIn(max = 260.dp).aspectRatio(CARD_RATIO)) {
            when {
                card != null -> NeueCard(card, Modifier.fillMaxSize(), foil = h.neue.prefs.foil)
                sees -> TokenFace(duels.catalog.nameOf(inst), Modifier.fillMaxSize())
                else -> CardBack(Modifier.fillMaxSize())
            }
        }
        H2(if (sees) duels.catalog.nameOf(inst) else "A face-down card", maxLines = 2)
        Small(placeWords(game, uid, where), color = c.ink70)
        if (card != null) {
            val stats = listOfNotNull(
                card.attribute.takeIf { it.name != "UNKNOWN" }?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                card.race,
                card.level?.let { (if (card.frameType.contains("xyz")) "Rank " else "Level ") + it },
                card.linkValue?.let { "Link $it" },
                card.atk?.let { "ATK $it" + (card.def?.let { d -> " / DEF $d" } ?: "") },
            ).joinToString(" · ")
            if (stats.isNotEmpty()) Mono(stats, color = c.ink70)
            Body(card.description, color = c.ink)
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
        HRule()
        val actor = duels.seatFor(uid)
        val mine = s.solo || actor == duels.bottom
        Micro(if (mine) "Do" else "Their card", color = c.ink70)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val verbs = if (mine) DuelVerbs.offered(s, actor, uid, duels.catalog) else listOf(DuelVerb.TARGET) + DuelVerbs.offered(s, actor, uid, duels.catalog).filter { it != DuelVerb.TARGET }
            verbs.forEach { v ->
                val key = VERB_KEYS[v]?.let { DeskShortcuts.chordFor(it) }?.let(DeskShortcuts::kbd)
                VerbChip(v.label, key, strong = v == verbs.first()) {
                    if (v == DuelVerb.TARGET && !mine) duels.verb(uid, v, seat = duels.bottom) else duels.verb(uid, v)
                }
            }
            VerbChip("Point at it", "Alt click") { duels.act(DuelAction.Ping(duels.bottom, DuelAction.PING_LOOK, uid = uid)) }
        }
        if (duels.attaching == uid) Help("Now click the monster it goes under. Esc to stop.")
    }
}

@Composable
private fun VerbChip(label: String, key: String? = null, strong: Boolean = false, onClick: () -> Unit) {
    val c = Mu.colors
    Row(
        Modifier.border(1.dp, if (strong) c.ink else c.ink25).background(if (strong) c.ink else c.paper)
            .cursorPointer(caption = label, showsWords = true).muClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro(label, color = if (strong) c.paper else c.ink)
        if (key != null && !strong) Mono(key, color = c.ink45)
    }
}

/** The keys that matter most, for the empty inspector. */
@Composable
private fun KeyCheat() {
    val c = Mu.colors
    val rows = listOf(
        DeskAction.DUEL_DEFAULT to "The obvious thing", DeskAction.DUEL_SUMMON to "Summon", DeskAction.DUEL_SET to "Set",
        DeskAction.DUEL_ACTIVATE to "Activate", DeskAction.DUEL_GRAVE to "To the GY", DeskAction.DUEL_BANISH to "Banish",
        DeskAction.DUEL_HAND to "To the hand", DeskAction.DUEL_DRAW to "Draw", DeskAction.DUEL_NEXT_PHASE to "Next phase",
        DeskAction.DUEL_END_TURN to "End the turn", DeskAction.DUEL_COMMAND to "The command line", DeskAction.UNDO to "Undo",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.mapNotNull { (a, words) ->
            DeskShortcuts.all.firstOrNull { it.action == a && it.scope == com.kaiharimoto.mastertool.core.input.DeskScope.DUEL }?.chord?.let { it to words }
        }.forEach { (chord, words) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kbd(DeskShortcuts.kbd(chord), Modifier.widthIn(min = 56.dp))
                Small(words, color = c.ink70)
            }
        }
    }
}

private fun placeWords(game: DuelGame, uid: Int, where: Place?): String {
    val s = game.state
    val owner = s.cards[uid]?.owner ?: 0
    val who = DuelWords.seatName(s, owner)
    return when (where) {
        is Place.Zone -> "${DuelWords.zoneName(where)} · ${DuelWords.seatName(s, s.cards.getValue(uid).controller)}'s field"
        is Place.Pile -> "$who's ${where.kind.label.let { if (it == "Hand") "hand" else it }}"
        is Place.Under -> "A material"
        else -> ""
    }
}

/**
 * The log in words, with the chat in it, newest at the bottom, a rule at each turn — and the line to
 * talk on. [viewer] null reads everything (the hot-seat with both hands shown).
 */
@Composable
internal fun DuelLogRail(duels: Duels, game: DuelGame, viewer: Int?, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val lines = remember(game.header, game.entries, game.cursor, viewer) { logLines(game, viewer, duels) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    val chatFocus = remember { FocusRequester() }
    LaunchedEffect(duels.chatFocus) { if (duels.chatFocus > 0) runCatching { chatFocus.requestFocus() } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("Log", Modifier.weight(1f), color = c.ink70)
            Mono("${game.cursor - game.floor}", color = c.ink45)
        }
        HRule()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(lines) { _, line ->
                when (line) {
                    is LogLine.Turn -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Micro(line.text, color = c.ink)
                        Box(Modifier.weight(1f).padding(start = 8.dp)) { HRule() }
                    }
                    is LogLine.Said -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Box(Modifier.background(c.ink06).padding(horizontal = 6.dp, vertical = 3.dp)) { Small(line.text, color = c.ink) }
                    }
                    is LogLine.Done -> Small(line.text, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = if (line.mine) c.ink else c.ink70)
                }
            }
        }
        HRule()
        MuInput(
            duels.chat,
            { duels.chat = it },
            Modifier.fillMaxWidth().padding(8.dp),
            placeholder = "Say something · Enter",
            dense = true,
            focusRequester = chatFocus,
            onSubmit = { duels.say(duels.chat) },
        )
    }
}

internal sealed interface LogLine {
    data class Turn(val text: String) : LogLine
    data class Done(val text: String, val mine: Boolean) : LogLine
    data class Said(val text: String) : LogLine
}

private fun logLines(game: DuelGame, viewer: Int?, duels: Duels): List<LogLine> {
    val out = ArrayList<LogLine>()
    var s = DuelSetup.initial(game.header)
    game.played.forEachIndexed { i, e ->
        val after = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
        if (i == game.floor) out += LogLine.Turn("Turn 1")
        if (i >= game.floor) {
            when (e.action) {
                is DuelAction.Chat -> out += LogLine.Said(DuelWords.say(s, after, e, viewer, duels.catalog))
                DuelAction.EndTurn -> out += LogLine.Turn("Turn ${after.turn} · ${DuelWords.seatName(after, after.active)}")
                is DuelAction.Thinking, is DuelAction.Ping -> out += LogLine.Done(DuelWords.say(s, after, e, viewer, duels.catalog), false)
                else -> out += LogLine.Done(DuelWords.say(s, after, e, viewer, duels.catalog), e.seat == duels.bottom)
            }
        }
        s = after
    }
    if (game.floor >= game.cursor) out += LogLine.Turn("Turn 1")
    return out
}
