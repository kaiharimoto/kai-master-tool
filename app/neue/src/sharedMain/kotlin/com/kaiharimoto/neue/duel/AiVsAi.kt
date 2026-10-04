package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/** A deck a seat of the match can sit down with: the builder's, or one from the library. */
private data class MatchDeck(val id: String?, val name: String, val deck: Deck)

/** A connection's name in the dialog: the person's label, the provider's, and the model. */
internal fun connectionWords(c: AiConnection): String {
    val p = Providers.byId(c.provider)
    val name = c.label.ifBlank { p?.label ?: c.provider }
    return if (c.model.isBlank()) name else "$name · ${c.model}"
}

/**
 * Starting Ai vs Ai (`docs/phases/C.md` §6): each seat's deck and connection (the active one for both by default), a
 * seed, the turn cap, the response windows and the token budget — with what the match may spend, said before it starts,
 * and why it cannot start when it cannot (no Ai, a networked table, a plan's command-line app, an empty deck).
 */
@Composable
internal fun AiVsAiDialog(h: NeueHolders) {
    val c = Mu.colors
    val duels = h.duel
    val ai = h.neue.prefs.ai
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(Unit) { library = h.deps.deckRepository.all() }
    val b = h.builder
    val builderDeck = MatchDeck(b.deckId, "${b.deckName} (the builder)", b.deck)
    val decks = listOf(builderDeck) + library.filter { it.entry.id != b.deckId }.map { MatchDeck(it.entry.id, it.entry.name, it.entry.deck) }
    val connections = ai.connections
    val fallback = ai.connection
    var deck0 by remember(library) { mutableStateOf(builderDeck) }
    var deck1 by remember(library) { mutableStateOf(decks.getOrNull(1) ?: builderDeck) }
    var conn0 by remember { mutableStateOf(fallback) }
    var conn1 by remember { mutableStateOf(fallback) }
    var seed by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf(12) }
    var windows by remember { mutableStateOf(Windows.ACTIVATIONS) }
    var budget by remember { mutableStateOf(500_000L) }
    val rules = MatchRules(turnCap = turns, windows = windows, tokenCap = budget)
    val cost = AiMatch.cost(rules)
    fun seat(i: Int, d: MatchDeck, conn: AiConnection?) = MatchSeat(
        name = conn?.let { it.model.ifBlank { it.label.ifBlank { Providers.byId(it.provider)?.label ?: it.provider } } }?.let { if (i == 1 && conn0?.id == conn1?.id) "$it (2)" else it } ?: "Seat $i",
        deckId = d.id,
        deckName = d.name.removeSuffix(" (the builder)"),
        main = d.deck.main.map { it.value },
        extra = d.deck.extra.map { it.value },
        connection = conn?.let { it.label.ifBlank { Providers.byId(it.provider)?.label ?: it.provider } }.orEmpty(),
        model = conn?.model.orEmpty(),
    )
    val choice = MatchChoice(
        seats = listOf(seat(0, deck0, conn0), seat(1, deck1, conn1)),
        connections = listOfNotNull(conn0, conn1),
        seed = seed.trim().toLongOrNull(),
        rules = rules,
    )
    val problems = matchProblems(h, choice)
    MuDialog(
        "Ai vs Ai",
        { duels.matches.dialogOpen = false },
        width = 600.dp,
        description = "Two Ai sessions, one a seat, each seeing only its own seat — its own hand, its own deck's guide and combos, never the other's. A referee keeps the turn and the response windows; you watch the whole table. Your duel waits behind it, untouched.",
        footer = {
            MuButton("Cancel", { duels.matches.dialogOpen = false }, variant = BtnVariant.GHOST)
            MuButton(
                "Start the match",
                {
                    val why = startAiVsAi(h, choice)
                    if (why != null) h.neue.note = Note(why) else duels.matches.dialogOpen = false
                },
                variant = BtnVariant.PRIMARY,
                enabled = problems.isEmpty(),
                reason = problems.firstOrNull(),
            )
        },
    ) {
        listOf(0, 1).forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Seat $i's deck")
                    MuSelect(if (i == 0) deck0 else deck1, decks, { it.name }, { if (i == 0) deck0 = it else deck1 = it }, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Seat $i's connection")
                    if (connections.isEmpty()) Help("No connection yet: set one up in Settings › Ai.")
                    else MuSelect(if (i == 0) conn0 else conn1, connections, { it?.let(::connectionWords) ?: "—" }, { if (i == 0) conn0 = it else conn1 = it }, Modifier.fillMaxWidth())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Turns at most", hint = "then a draw by limit")
                Segmented(turns, listOf(6, 12, 20, 40), { "$it" }, { turns = it }, small = true)
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("Seed", hint = "the same seed, the same deal")
                MuInput(seed, { seed = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), placeholder = "A fresh one each match")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Response windows")
                Segmented(windows, listOf(Windows.ACTIVATIONS, Windows.SUMMONS), { if (it == Windows.ACTIVATIONS) "On activations" else "Summons too" }, { windows = it }, small = true)
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("Token budget", hint = "both seats")
                Segmented(budget, listOf(250_000L, 500_000L, 1_000_000L, 2_000_000L), { tokens(it) }, { budget = it }, small = true)
            }
        }
        // What it may spend, said before it starts (the cap is where it stops).
        Small(
            "Up to about ${cost.cues} cues (both seats, about ${AiMatch.CUES_PER_TURN} a turn) at about ${tokens(AiMatch.TOKENS_PER_CUE)} tokens each: " +
                "about ${tokens(cost.estimate)} tokens" + if (cost.capped) " — the budget stops it at ${tokens(budget)}, a draw by limit." else ", within the budget of ${tokens(budget)}.",
            color = c.ink,
        )
        Help("Only API connections play a seat: a plan's command-line app (Claude Code, Codex) runs its own loop and the app's tools for the duel in play, so it cannot be held to one seat of the match's table. Each cue is bounded (${rules.cueMoves} moves, ${rules.cueSteps} rounds, ${rules.cueMillis / 60_000} minutes); a seat that keeps failing forfeits. Stop ends both at once.")
        problems.forEach { Small(it, color = c.ink) }
    }
}

/** A count of tokens in words: 18k, 1.5M. */
private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> (if (n % 1_000_000 == 0L) "${n / 1_000_000}" else "%.1f".format(n / 1_000_000.0)) + "M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> "$n"
}

/**
 * The match being watched, over the table's top edge (`docs/phases/C.md` §6): who plays whom, the turn and who is moving,
 * Stop while it runs; once it is over, how it ended, each seat's conversation to read, and the way back to your duel.
 */
@Composable
internal fun MatchBar(h: NeueHolders) {
    val c = Mu.colors
    val m = h.duel.matches
    val g = m.live ?: return
    val s = g.state
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(c.paper).border(1.dp, c.ink).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val who = m.engines.takeIf { it.size == 2 }?.joinToString(" v ") ?: "${DuelWords.seatName(s, 0)} v ${DuelWords.seatName(s, 1)}"
        val now = if (m.running) "turn ${s.turn}${m.status?.let { " · $it" } ?: ""}" else m.ended.orEmpty()
        Small("Ai vs Ai · $who · $now", Modifier.weight(1f), color = c.ink, maxLines = 1)
        if (m.running) {
            MuButton("Stop", { m.stop() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
        } else {
            m.sessions.forEachIndexed { i, id ->
                MuButton("Seat $i's side", { h.ai.open(id); h.ai.setOpen(true) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
            }
            MuButton("Back to your duel", { m.close() }, size = BtnSize.SM)
        }
    }
}
