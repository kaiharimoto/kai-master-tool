package com.kaiharimoto.neue.duel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.kaiharimoto.mastertool.core.ai.providers.ModelNames
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
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
 * What the table calls the player a connection seats (the design review, finding 2): its model said short — "Opus 5.5",
 * "GPT-5" — so the score column never cuts it; the connection's label when it names no model. Display only.
 */
internal fun playerName(c: AiConnection?): String =
    c?.let { ModelNames.short(it.model).ifBlank { it.label.ifBlank { Providers.byId(it.provider)?.label ?: it.provider } } } ?: "Ai"

/** A count of tokens in words: 18k, 1.5M. */
internal fun tokens(n: Long): String = when {
    n >= 1_000_000 -> (if (n % 1_000_000 == 0L) "${n / 1_000_000}" else "%.1f".format(n / 1_000_000.0)) + "M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> "$n"
}

/**
 * Starting Ai vs Ai (`docs/phases/C.md` §6): each seat's deck and connection — the bottom seat the active connection, the
 * top seat another when there is one — the turn cap and the token budget, Seed and the response windows under More
 * options; what the match may spend is said in the footer before it starts, and why it cannot start when it cannot.
 * One column on a phone, each label above its control and its help under it (the design review, findings 3, 4, 6, 10).
 */
@Composable
internal fun AiVsAiDialog(h: NeueHolders) {
    val c = Mu.colors
    val phone = LocalPhone.current
    val duels = h.duel
    val ai = h.neue.prefs.ai
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(Unit) { library = h.deps.deckRepository.all() }
    val b = h.builder
    val builderDeck = MatchDeck(b.deckId, "${b.deckName} (the builder)", b.deck)
    val decks = listOf(builderDeck) + library.filter { it.entry.id != b.deckId }.map { MatchDeck(it.entry.id, it.entry.name, it.entry.deck) }
    val connections = ai.connections
    val fallback = ai.connection
    // The seat at the bottom of the table first, as the table will show it.
    val near = duels.bottom.coerceIn(0, 1)
    val other = connections.firstOrNull { it.id != fallback?.id } ?: fallback
    val deck = remember(library) { List(2) { i -> mutableStateOf(if (i == near) builderDeck else decks.getOrNull(1) ?: builderDeck) } }
    // kai asked for two different sessions: the top seat takes another connection when there is one.
    val conn = remember { List(2) { i -> mutableStateOf(if (i == near) fallback else other) } }
    var seed by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf(12) }
    var windows by remember { mutableStateOf(Windows.ACTIVATIONS) }
    // The budget follows the turn cap — the smallest that covers it — until the person sets it.
    var chosenBudget by remember { mutableStateOf<Long?>(null) }
    val budget = chosenBudget ?: AiMatch.budgetFor(turns)
    var more by remember { mutableStateOf(false) }
    val rules = MatchRules(turnCap = turns, windows = windows, tokenCap = budget)
    val cost = AiMatch.cost(rules)
    val names = List(2) { i -> playerName(conn[i].value) }.let { n -> if (n[0] == n[1]) listOf("${n[0]} A", "${n[1]} B") else n }
    fun seat(i: Int): MatchSeat {
        val d = deck[i].value
        val k = conn[i].value
        return MatchSeat(
            name = names[i],
            deckId = d.id,
            deckName = d.name.removeSuffix(" (the builder)"),
            main = d.deck.main.map { it.value },
            extra = d.deck.extra.map { it.value },
            connection = k?.let { it.label.ifBlank { Providers.byId(it.provider)?.label ?: it.provider } }.orEmpty(),
            model = k?.model.orEmpty(),
        )
    }
    val choice = MatchChoice(
        seats = listOf(seat(0), seat(1)),
        connections = listOfNotNull(conn[0].value, conn[1].value),
        seed = seed.trim().toLongOrNull(),
        rules = rules,
    )
    val problems = matchProblems(h, choice)
    val gap = 16.dp
    MuDialog(
        "Ai vs Ai",
        { duels.matches.dialogOpen = false },
        width = 600.dp,
        description = "Two Ai players play each other, each seeing only its own hand, deck guide and combos. You watch both hands. Your own duel waits, untouched.",
        footer = {
            // What it may spend, said before it starts, beside the button that spends it (the kit's §16.4).
            Mono(
                "≈ ${tokens(cost.estimate)} tokens${if (cost.capped) "" else " at most"} · stops at ${tokens(budget)}",
                Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                color = c.ink70,
            )
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
        // The seats, bottom then top: each its deck and its connection.
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            listOf(near, 1 - near).forEach { i ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro(if (i == near) "Bottom seat" else "Top seat", color = c.ink)
                    val deckField: @Composable (Modifier) -> Unit = { m ->
                        Column(m) {
                            FieldLabel("Deck")
                            MuSelect(deck[i].value, decks, { it.name }, { deck[i].value = it }, Modifier.fillMaxWidth())
                        }
                    }
                    val connField: @Composable (Modifier) -> Unit = { m ->
                        Column(m) {
                            FieldLabel("Connection")
                            if (connections.isEmpty()) Help("No connection yet: set one up in Settings › Ai.")
                            else MuSelect(conn[i].value, connections, { it?.let(::connectionWords) ?: "—" }, { conn[i].value = it }, Modifier.fillMaxWidth())
                        }
                    }
                    if (phone) {
                        deckField(Modifier.fillMaxWidth())
                        connField(Modifier.fillMaxWidth())
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            deckField(Modifier.weight(1f))
                            connField(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // The match's bounds.
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            val turnField: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Turns at most")
                    Segmented(turns, listOf(6, 12, 20, 40), { "$it" }, { turns = it }, if (phone) Modifier.fillMaxWidth() else Modifier, small = true, fill = phone)
                    Help("Then a draw by limit.")
                }
            }
            val budgetField: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Token budget")
                    Segmented(budget, AiMatch.BUDGETS, { tokens(it) }, { chosenBudget = it }, if (phone) Modifier.fillMaxWidth() else Modifier, small = true, fill = phone)
                    Help("Both players together.")
                }
            }
            if (phone) {
                turnField(Modifier.fillMaxWidth())
                budgetField(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    turnField(Modifier.weight(1f))
                    budgetField(Modifier.weight(1f))
                }
            }
            // Where the cap still bites, said on its own line.
            if (cost.capped) Small("— ${tokens(budget)} stops it at about turn ${AiMatch.stopsAt(budget)}, as a draw.", color = c.ink)
            MicroLink(if (more) "Fewer options ▾" else "More options ▸", { more = !more })
            if (more) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Seed")
                    MuInput(seed, { seed = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), placeholder = "A fresh one each match")
                    Help("The same seed deals the same hands.")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Response windows")
                    Segmented(
                        windows, listOf(Windows.ACTIVATIONS, Windows.SUMMONS), { if (it == Windows.ACTIVATIONS) "On activations" else "Summons too" }, { windows = it },
                        if (phone) Modifier.fillMaxWidth() else Modifier, small = true, fill = phone,
                    )
                    Help("When the other player is asked whether it responds.")
                }
            }
            Help("Each turn costs both players a few requests. The budget is a hard stop: past it the match ends as a draw. Stop ends both at once.")
            problems.forEach { Small(it, color = c.ink) }
        }
    }
}

/** What Stop does, said before it is pressed (the design review, finding 9). */
private const val STOP_TIP = "Stop both players now. The replay is kept; no result is counted."

/** Who sits where, as the table shows them: "↓ Opus 5.5 with lab · ↑ GPT-5 with K9 Vanquish Soul". */
private fun seatsWords(m: DuelMatches, bottom: Int): String {
    fun seat(i: Int) = (m.engines.getOrNull(i) ?: "Seat ${i + 1}") + (m.decks.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty())
    return "↓ ${seat(bottom)} · ↑ ${seat(1 - bottom)}"
}

/** The spend so far against the budget: "≈ 210k of 1M tokens". */
private fun spendWords(m: DuelMatches, short: Boolean = false): String = "≈ ${tokens(m.spent)} of ${tokens(m.budget)}" + if (short) "" else " tokens"

/** Each seat's conversation, to read once the match is over: "Read Opus 5.5's game", the bottom seat first. */
@Composable
private fun ReadButtons(h: NeueHolders) {
    val m = h.duel.matches
    val bottom = h.duel.bottom
    for (i in listOf(bottom, 1 - bottom)) {
        val id = m.sessions.getOrNull(i)
        if (id != null) {
            val who = m.engines.getOrNull(i) ?: "Seat ${i + 1}"
            MuButton("Read $who's game", { h.ai.open(id); h.ai.setOpen(true) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
        }
    }
}

/**
 * The match being watched, in the window's bar where the command line stands while one plays (`docs/phases/C.md` §6;
 * the design review, findings 1, 2, 5, 9): a square that breathes while it runs, WATCHING · AI VS AI, who sits where,
 * the turn and who is moving, the spend against the budget and Stop; once it is over, how it ended, each player's
 * conversation to read, and the way back to your duel. Off the table's edge, so it never covers the far hand.
 */
@Composable
internal fun RowScope.MatchStatus(h: NeueHolders) {
    val c = Mu.colors
    val m = h.duel.matches
    val g = m.live ?: return
    val bottom = h.duel.bottom
    if (m.running) {
        Breathe()
        Micro("Watching · Ai vs Ai", color = c.ink)
        Small("${seatsWords(m, bottom)} · turn ${g.state.turn} · ${m.status ?: "Dealing"}", Modifier.weight(1f), color = c.ink, maxLines = 1)
        Mono(spendWords(m), color = c.ink70)
        Tip(STOP_TIP, kbd = DeskShortcuts.chordFor(DeskAction.DISMISS)?.let(DeskShortcuts::kbd)) {
            MuButton("Stop", { m.stop() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
        }
    } else {
        Micro("Ai vs Ai · over", color = c.ink)
        // The result first: on a narrow bar it is the part that stays.
        Small("${m.ended.orEmpty()} ${seatsWords(m, bottom)}".trim(), Modifier.weight(1f), color = c.ink, maxLines = 1)
        ReadButtons(h)
        MuButton("Back to your duel", { m.close() }, size = BtnSize.SM)
    }
}

/**
 * The match on a phone, in the row the command line has there: AI VS AI · TURN 3 with Stop or Back to your duel, then who
 * is moving or how it ended in words that wrap — never cut — and who sits where (the design review, finding 5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMatchBar(h: NeueHolders) {
    val c = Mu.colors
    val m = h.duel.matches
    val g = m.live ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.running) Breathe()
            Micro("Ai vs Ai · Turn ${g.state.turn}", Modifier.weight(1f), color = c.ink)
            if (m.running) {
                Mono(spendWords(m, short = true), color = c.ink70)
                MuButton("Stop", { m.stop() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            } else {
                MuButton("Back to your duel", { m.close() }, size = BtnSize.SM)
            }
        }
        Small(if (m.running) m.status ?: "Dealing" else m.ended.orEmpty(), color = c.ink)
        Small(seatsWords(m, h.duel.bottom), color = c.ink70)
        if (!m.running && m.sessions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { ReadButtons(h) }
        }
    }
}
