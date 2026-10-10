package com.kaiharimoto.neue.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.prep.EventOdds
import com.kaiharimoto.neue.prep.ledger
import com.kaiharimoto.mastertool.core.prep.PracticePlan
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.world.MatchMath
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.effects.LocalEffectsHolders
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.prep.RangeLine
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.web.Webs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.round

/*
 * Format as the event (Phase G, G.5; mockup B): where the event is won or lost. The field as one strip; a row per deck with
 * your match win against it (its range and the games behind it), how far its siding is written, what it costs you — its
 * share times the matches you lose to it — and the roll's call; Shootout's rates beside Prep's when they have been read;
 * and what to practise next. Read from the same reading as Prep ([EventOdds]).
 */

/** The field as one strip: each deck's share as a length, yours solid, the rest in steps of ink; labels under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldStrip(web: DeckWeb, names: Map<String, String>, other: Int) {
    val c = Mu.colors
    val parts = web.entries.filter { (it.share ?: 0) > 0 }.map { Triple(names[it.deckId] ?: "A deck", it.share!!, it.mine) } +
        listOfNotNull(other.takeIf { it > 0 }?.let { Triple("The rest of the room", it, false) })
    if (parts.isEmpty()) return
    val total = parts.sumOf { it.second }.toFloat()
    val inks = listOf(c.ink70, c.ink45, c.ink25, c.ink12)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(14.dp).drawBehind {
                var x = 0f
                val gap = 2.dp.toPx()
                parts.forEachIndexed { i, (_, share, mine) ->
                    val w = size.width * share / total
                    drawRect(if (mine) c.ink else inks[i % inks.size], Offset(x, 0f), Size((w - gap).coerceAtLeast(1f), size.height))
                    x += w
                }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.forEach { (name, share, mine) -> Small("${if (mine) "★ " else ""}$name $share%", color = if (mine) c.ink else c.ink70) }
        }
    }
}

/** A share's source in a word (Phase G, F3): where the number came from. */
internal fun sourceWord(source: String?): String? = when (source) {
    WebEntry.SOURCE_TOPS -> "top cuts"
    WebEntry.SOURCE_ESTIMATE -> "estimate"
    WebEntry.SOURCE_HAND -> "yours"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventTable(web: DeckWeb, decks: List<StoredDeck>, state: DeckBuilderState, neue: NeueState, webs: Webs, modifier: Modifier) {
    val c = Mu.colors
    val h = LocalEffectsHolders.current ?: return
    val phone = LocalPhone.current
    val mine = decks.filter { web.entry(it.entry.id)?.mine == true }
    if (mine.isEmpty()) {
        EmptyState("None of these decks is marked as yours.", "Star the deck you play — the ☆ on a tile — and the event is read from its side: your match win against each deck, and what each costs you.")
        return
    }
    var asId by remember(web.id) { mutableStateOf(mine.first().entry.id) }
    val me = mine.firstOrNull { it.entry.id == asId } ?: mine.first()
    // The event this web is the field of, for the rest of the room and the clock; else none.
    val event = h.prep.doc.events.firstOrNull { it.webId == web.id && it.deckId == me.entry.id } ?: h.prep.doc.events.firstOrNull { it.webId == web.id }
    val doc = h.prep.doc
    val reading by produceState<EventOdds.Reading?>(null, doc.games, doc.sources, doc.earlier, web.entries, event, me.entry.id, h.versions.revision) {
        // The deck's games from the one ledger (Phase G, G.8), as Prep reads them.
        val games = h.ledger(me.entry.id, web, h.prep.doc).games
        value = withContext(Dispatchers.Default) {
            EventOdds.read(games, web.entries, event ?: PrepEvent("", "", ""), me.entry.id, me.entry.name)
        }
    }
    val names = decks.associate { it.entry.id to it.entry.name }
    val siding = webs.sidingOf(me, state)
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mine.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mine.forEach { d -> Tag("★ ${d.entry.name}", d.entry.id == me.entry.id, { asId = d.entry.id }, caption = "Read as") }
            }
            FieldStrip(web, names, event?.otherShare ?: 0)
            val r = reading
            val i = r?.interval
            if (r != null && i != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(if (phone) 140.dp else 220.dp)) {
                        Micro("Your match win", color = c.ink45)
                        Mono(EventOdds.pct(i.point), color = c.ink, size = 28.sp)
                        Small("${EventOdds.pct(i.low)}–${EventOdds.pct(i.high)} · ${r.games} games", color = c.ink70)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.cut?.let { Small("Top ${r.swiss?.topCut}: ${EventOdds.pct(it)} at this rate. ${r.cutRecord}.", color = c.ink) }
                        r.next?.takeIf { it.gain >= 0.5 }?.let { Small("Practise next: ${PracticePlan.words(it)}.", color = c.ink) }
                    }
                }
            } else if (r != null) {
                Help("Give the decks their shares (a tile's menu) and your match win against the field stands here with its range.")
            }
            FieldBlock(state)
            EventRows(r, web, decks, me, state, neue, siding, phone)
            Help(
                "Your match win against each deck is best of three from your logged games (Prep), with its 95% range. What it costs you is its share times the matches you lose to it, in points of your event's match win: the deck to work on first. Shares are of the room as you set them; a share taken from top cuts over-counts strong decks.",
            )
        }
        ScrollbarFor(scroll)
    }
}

@Composable
private fun EventRows(
    r: EventOdds.Reading?,
    web: DeckWeb,
    decks: List<StoredDeck>,
    me: StoredDeck,
    state: DeckBuilderState,
    neue: NeueState,
    siding: DeckSiding,
    phone: Boolean,
) {
    val c = Mu.colors
    val h = LocalEffectsHolders.current ?: return
    val opponents = decks.filter { it.entry.id != me.entry.id }
    val total = (r?.shares?.values?.sum() ?: 0) + (r?.other?.share ?: 0)
    val rows = r?.rows.orEmpty().associateBy { it.opponent }
    // Each deck's match win and its range, and what it costs: share × matches lost, in points of the event's match win.
    class Line(val deck: StoredDeck, val share: Int, val win: Double, val low: Double, val high: Double, val games: Int, val cost: Double)
    val lines = remember(r, decks, web.entries, me.entry.id) {
        opponents.map { o ->
            val share = web.entry(o.entry.id)?.share ?: 0
            val row = rows[o.entry.id]
            val point = TestStats.matchAgainst(row)
            val range = MatchMath.field(listOfNotNull(row), mapOf(o.entry.id to 100), draws = 1500)
            Line(o, share, point, range.low, range.high, row?.all?.games ?: 0, if (total > 0) 100.0 * share / total * (1 - point) else 0.0)
        }.sortedByDescending { it.cost }
    }
    if (!phone) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Deck", Modifier.weight(1.4f), color = c.ink45)
            Micro("Share", Modifier.width(88.dp), color = c.ink45)
            Micro("Your match win", Modifier.weight(1.2f), color = c.ink45)
            Micro("Siding", Modifier.width(96.dp), color = c.ink45)
            Micro("Costs you", Modifier.width(88.dp), color = c.ink45)
            Micro("Win the roll", Modifier.weight(1f), color = c.ink45)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(c.ink) })
    }
    lines.forEach { l ->
        key(l.deck.entry.id) {
            val m = siding.against(l.deck.entry.id, l.deck.entry.name)
            val plans = m?.let { x -> Turn.entries.count { x.plan(it).sided } } ?: 0
            val entry = web.entry(l.deck.entry.id)
            val call = r?.calls?.get(l.deck.entry.id)
            val shootout = if (h.shootoutStarted) h.shootout.rates[h.shootout.ratesKey(me.entry.id, l.deck.entry.id)] else null
            val name: @Composable (Modifier) -> Unit = { mod ->
                Row(mod, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    faces(l.deck, neue, state).take(2).forEach { NeueCard(it, Modifier.size(20.dp, 29.dp), foil = "off") }
                    RowText(l.deck.entry.name, Modifier.weight(1f), color = c.ink, maxLines = 2)
                }
            }
            val win: @Composable (Modifier) -> Unit = { mod ->
                Column(mod, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RangeLine(l.low, l.win, l.high, Modifier.fillMaxWidth().height(10.dp), faint = l.games < 5)
                    Small("${EventOdds.pct(l.win)} (${EventOdds.pct(l.low)}–${EventOdds.pct(l.high)}) · ${l.games} games", color = if (l.games < 5) c.ink45 else c.ink70, maxLines = 1)
                    shootout?.let { s ->
                        val g1 = listOfNotNull(s[Stratum.G1_FIRST], s[Stratum.G1_SECOND])
                        if (g1.size == 2) Small("Shootout, Game 1: ${round(g1[0].value).toInt()}% first · ${round(g1[1].value).toInt()}% second", color = c.ink45, maxLines = 1)
                    }
                }
            }
            if (phone) {
                Column(Modifier.fillMaxWidth().border(1.dp, c.ink25).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    name(Modifier.fillMaxWidth())
                    Small("${l.share}% of the room" + (sourceWord(entry?.shareSource)?.let { " ($it)" } ?: "") + " · costs you ${round(l.cost).toInt()} points · $plans of 2 plans", color = c.ink70)
                    win(Modifier.fillMaxWidth())
                    call?.let { Small("Win the roll: ${EventOdds.callWords(it)}", color = c.ink70) }
                    MuButton(if (plans > 0) "Siding" else "Write the plans", { h.webs.side(me.entry.id, l.deck.entry.id) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    name(Modifier.weight(1.4f))
                    Column(Modifier.width(88.dp)) {
                        Mono("${l.share}%", color = c.ink)
                        sourceWord(entry?.shareSource)?.let { Small(it, color = c.ink45, maxLines = 1) }
                    }
                    win(Modifier.weight(1.2f))
                    Box(Modifier.width(96.dp)) {
                        MuButton("$plans of 2", { h.webs.side(me.entry.id, l.deck.entry.id) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                    }
                    Mono(if (l.share == 0) "--" else "−${round(l.cost).toInt()}", Modifier.width(88.dp), color = c.ink)
                    Small(call?.let(EventOdds::callWords) ?: "", Modifier.weight(1f), color = c.ink70, maxLines = 2)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(c.ink12) })
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * What the field interrupts with (Phase G, G.5; the red team's F1): from the lists last read, how often it opens a hand trap
 * or a negate going first and second, and the cards it plays most; "Read the field" fetches the lists for someone without Ai.
 */
@Composable
private fun FieldBlock(state: DeckBuilderState) {
    val c = Mu.colors
    val h = LocalEffectsHolders.current ?: return
    val f = h.field
    LaunchedEffect(Unit) { f.load() }
    val read = f.read
    Row(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro("What the field interrupts with", color = c.ink70)
            if (read == null) {
                Small("Read the field to see the hand traps and negates of every list that topped lately, and how often it opens them.", color = c.ink45)
            } else {
                val o = read.profile.field
                Small(
                    "It opens a hand trap or a negate ${EventOdds.pct(o.one5)} of the time going first (five cards) and ${EventOdds.pct(o.one6)} going second; " +
                        "two or more ${EventOdds.pct(o.two5)} and ${EventOdds.pct(o.two6)}.",
                    color = c.ink,
                )
                val names = read.profile.interaction().take(6).joinToString(" · ") { "${h.builder.index.byId(it.card)?.name ?: "#${it.card.value}"} ${EventOdds.pct(it.share)}" }
                if (names.isNotEmpty()) Small("Most played: $names", color = c.ink70, maxLines = 2)
                val ago = ((System.currentTimeMillis() - read.snapshot.readAt) / 86_400_000L).toInt()
                Help(
                    "From ${read.snapshot.lists.size} ${read.snapshot.format} lists that topped (tier ${read.snapshot.tier}+, ${read.snapshot.days} days, YGOPRODeck), read " +
                        (if (ago <= 0) "today" else if (ago == 1) "yesterday" else "$ago days ago") + ". Counts only: the share of lists, weighted by result.",
                )
            }
            f.problem?.let { Small(it, color = c.ink) }
        }
        MuButton(
            if (f.reading) "Reading…" else if (read == null) "Read the field" else "Read again",
            { f.fetch(appFormat(state)) },
            variant = BtnVariant.SUBTLE,
            size = BtnSize.SM,
            enabled = !f.reading,
            reason = "Reading YGOPRODeck",
        )
    }
}

/** The app's play as the field is read for: Genesys is played on the TCG's region, so it is asked for by name. */
internal fun appFormat(state: DeckBuilderState): DeckFormat = when {
    state.rulesInForce.genesys -> DeckFormat.GENESYS
    state.format.name == "OCG" -> DeckFormat.OCG
    else -> DeckFormat.TCG
}
