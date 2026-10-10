package com.kaiharimoto.neue.effects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckCoverage
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCheck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardPlace
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishBrowse
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDoc
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishResult
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandEnd
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandOutcome
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandPick
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.LineCount
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.NeedKind
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Needed
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.NotComputable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TargetDraft
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Stepper
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The goldfish in the Effects app (Phase D step 4, agent (c); `docs/phases/D.md` §5, §11): the open deck's end boards — made,
 * edited and deleted here without the vocabulary, Ai's marked as Ai's — then a run (going first or second, how many hands,
 * the seed) with its progress and Stop, and the result: the headline sentence, reached / no line / undecided as a bar, the
 * lines as strips of art, the written effects it trusted (open warnings accepted in place), the cards it played as inert
 * with Write these. **Every number opens its hands**, and a hand opens as a replay on the Duel page, unsaved unless kept.
 */

/** The open deck as the goldfish deals it. */
fun NeueHolders.goldfishDeck(): GoldfishDeck {
    val b = builder
    return GoldfishDeck(
        b.deck.main.map { it.value }, b.deck.extra.map { it.value }, b.deckId, Ledger.fingerprint(b.deck, b.index::byId), b.deckName,
        also = setOf(Ledger.fingerprintV1(b.deck)),
    )
}

/** What a run reads by: the library as the goldfish trusts it now (read here, on the main thread) and the pool. */
fun NeueHolders.goldfishKit(): GoldfishKit {
    val index = builder.index
    return GoldfishKit(effects.trust()) { code -> index.byId(CardId(code)) }
}

/** The goldfish, opened: Ai World's Effects app on its Goldfish tab (the palette's command). */
fun NeueHolders.openGoldfish() {
    effects.goldfishRuns.tab = EffectsTab.GOLDFISH
    neue.go(Page.WORLD)
    world.desk.open(BuiltInApp.EFFECTS.ref)
}

/** Hand [k] of the result on screen opened as a replay on the Duel page: not saved unless the person keeps it. */
fun NeueHolders.openGoldfishHand(k: Int): Job? {
    val runs = effects.goldfishRuns
    return runs.open(k, goldfishKit()) { r, replay ->
        duel.openGame(GoldfishBrowse.replayName(r, k), replay.game)
        neue.go(Page.DUEL)
        replay.problem?.let { neue.note = Note("The line stopped short: $it") }
    }
}

private fun name(h: NeueHolders): (Int) -> String {
    val index = h.builder.index
    return { code -> index.byId(CardId(code))?.name ?: "#$code" }
}

/** The goldfish tab of the Effects app, on the open deck. */
@Composable
internal fun GoldfishPane(h: NeueHolders) {
    val c = Mu.colors
    val b = h.builder
    val runs = h.effects.goldfishRuns
    val deckId = b.deckId
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("The goldfish", color = c.ink45)
            RowText(b.deckName.ifBlank { "Untitled deck" }, color = c.ink)
            Small(
                "Deals hands from a seed and searches the written effects for a line to an end board you name, in one turn with " +
                    "no opponent. Cards with no written effect are played as inert, so the number is a lower bound.",
                color = c.ink70,
            )
        }
        when {
            b.deck.totalCards == 0 -> Small("Open a deck in the builder: the goldfish deals its hands.", color = c.ink)
            deckId == null -> Small("Save the deck first: its end boards and the results you keep are kept with it.", color = c.ink)
            else -> {
                val doc by produceState(GoldfishDoc(deck = deckId), deckId, h.effects.goldfishRevision) {
                    value = withContext(Dispatchers.IO) { h.effects.goldfish(deckId) }
                }
                val editing = runs.editing
                if (editing != null) {
                    TargetEditor(h, deckId, editing)
                } else {
                    // What the goldfish can play of the deck, the inert cards dimmed (Phase G: one coverage, three places).
                    val coverage = remember(b.deck, h.effects.revision, h.effects.loaded) { DeckCoverage.of(h.goldfishDeck(), h.goldfishKit()) }
                    CoverageStrip(h, coverage, "the cards the goldfish plays as inert in ${b.deckName}")
                    HRule()
                    Targets(h, deckId, doc)
                    HRule()
                    RunControls(h, doc)
                    runs.shown?.let { s ->
                        HRule()
                        ResultView(h, s)
                    }
                    HRule()
                    Kept(h, doc)
                }
            }
        }
    }
}

// ---- End boards ---------------------------------------------------------------------------------------------------

/** The end board chosen: the one picked, else the first. */
private fun chosen(runs: GoldfishRuns, doc: GoldfishDoc): EndBoard? = runs.targetId?.let(doc::target) ?: doc.targets.firstOrNull()

@Composable
private fun Targets(h: NeueHolders, deckId: String, doc: GoldfishDoc) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val scope = rememberCoroutineScope()
    val chosen = chosen(runs, doc)
    var deleting by remember(deckId) { mutableStateOf<String?>(null) }
    val name = name(h)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("End boards", color = c.ink45)
        if (doc.targets.isEmpty()) {
            Small("No end board yet. Make one: what the deck wants on the table after its first turn.", color = c.ink70)
        }
        doc.targets.forEach { t ->
            key(t.id) {
                val on = t.id == chosen?.id
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, if (on) c.ink else c.ink12)
                        .cursorPointer(caption = if (on) null else "Run this one")
                        .muClickable { runs.targetId = t.id }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).border(1.dp, c.ink).background(if (on) c.ink else c.paper))
                        RowText(t.name, Modifier.weight(1f), color = c.ink, maxLines = 2)
                        if (t.by == EndBoard.AI) Badge("${h.ai.name}'s")
                    }
                    Small(BoardCheck.words(t, name), color = c.ink70)
                    if (deleting == t.id) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Small("Delete “${t.name}”? Kept results keep their copy of it.", Modifier.weight(1f), color = c.ink)
                            MuButton("Delete", {
                                deleting = null
                                runs.forgetTarget(t.id)
                                scope.launch { h.effects.updateGoldfish(deckId) { GoldfishCodec.dropTarget(it, t.id) } }
                            }, size = BtnSize.SM)
                            MuButton("Cancel", { deleting = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MicroLink("Edit", { runs.editing = GoldfishRuns.Editing(t.id, TargetDraft.of(t), t.by) })
                            MicroLink("Delete", { deleting = t.id })
                        }
                    }
                }
            }
        }
        MuButton(
            "New end board",
            { runs.editing = GoldfishRuns.Editing(null, TargetDraft(), EndBoard.PERSON) },
            size = BtnSize.SM,
            icon = Icons.Plus,
            enabled = doc.targets.size < GoldfishDoc.MOST_TARGETS,
            reason = "At most ${GoldfishDoc.MOST_TARGETS} end boards a deck: delete one first",
        )
    }
}

// ---- The run ------------------------------------------------------------------------------------------------------

/** The hand counts offered: a phone's default, the desk's, and two larger runs. */
private val HAND_COUNTS = listOf(Goldfish.PHONE_HANDS, Goldfish.DESK_HANDS, 5_000, Goldfish.MOST_HANDS)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunControls(h: NeueHolders, doc: GoldfishDoc) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val phone = LocalPhone.current
    val target = chosen(runs, doc)
    val default = if (phone) Goldfish.PHONE_HANDS else Goldfish.DESK_HANDS
    val hands = runs.hands(default)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro("Run", color = c.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Segmented(runs.first, listOf(true, false), { if (it) "Going first" else "Going second" }, { runs.first = it }, small = true)
            Segmented(hands, (HAND_COUNTS + hands).distinct().sorted(), { GoldfishWords.count(it) }, { runs.handsText = it.toString() }, small = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Small("Seed", color = c.ink70)
                MuInput(runs.seedText, { t -> runs.seedText = t.filter { it.isDigit() || it == '-' }.take(18) }, Modifier.width(120.dp), placeholder = "1", dense = true)
                Tip("Another seed: the next run deals other hands") {
                    MuButton("Re-roll", { runs.reroll() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
            }
        }
        Help("The same seed deals the same hands everywhere. ${GoldfishWords.count(hands)} hands, at most ${GoldfishWords.count(Goldfish.MOST_HANDS)}.", color = c.ink45)
        val busy = runs.busy
        if (busy) {
            val p = runs.progress
            // What is running, in its own words: the controls above may already be set for the next run.
            runs.running?.let { s ->
                RowText(
                    "Running “${s.target.name}”: going ${if (s.first) "first" else "second"}, ${GoldfishWords.count(s.hands)} hands, seed ${s.seed}",
                    color = c.ink,
                    maxLines = 2,
                )
            }
            Progress(p?.let { if (it.total == 0) null else it.done.toFloat() / it.total })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono(p?.let(GoldfishBrowse::progressWords) ?: "Starting…", Modifier.weight(1f), color = c.ink)
                MuButton("Stop", { runs.stop() }, size = BtnSize.SM)
            }
        } else {
            val why = when {
                target == null -> "Make an end board first"
                !h.effects.loaded -> "The library of written effects is still being read"
                else -> null
            }
            MuButton(
                "Run",
                { target?.let { t -> runs.start(GoldfishSetup(h.goldfishDeck(), t, runs.first, hands, runs.seed), h.goldfishKit()) } },
                variant = BtnVariant.PRIMARY,
                size = BtnSize.SM,
                enabled = why == null,
                reason = why,
            )
        }
        runs.said?.let { Small(it, color = c.ink) }
        runs.refusal?.let { Refusal(h, it) }
    }
}

/** A run that could not start: why, the cards it needs, and Write these (the person's go). */
@Composable
private fun Refusal(h: NeueHolders, nc: NotComputable) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Not computable yet", color = c.ink)
        Small(nc.why, color = c.ink)
        if (nc.cards.isNotEmpty()) {
            ArtStrip(h, nc.cards, if (LocalPhone.current) 44.dp else 56.dp)
            WriteThese(h, nc.cards, "the cards the end board “${nc.name}” needs")
        }
    }
}

/** **Write these** for [cards]: the cost said first, the person's go (`NeueHolders.go`). */
@Composable
internal fun WriteThese(h: NeueHolders, cards: List<Int>, what: String) {
    val c = Mu.colors
    val deckId = h.builder.deckId
    val offer = remember(cards, h.effects.revision, h.effects.asked, h.ai.prefs.connection) { h.offerFor(cards, what, deckId) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuButton(
            if (offer.toWrite.size <= 1) "Write it" else "Write these ${offer.toWrite.size}",
            { h.go(offer, FxFrom.PANE) },
            size = BtnSize.SM,
            enabled = offer.toWrite.isNotEmpty() && h.neue.prefs.ai.enabled && !h.ai.running,
            reason = h.goBlocked(offer) ?: "${h.ai.name} is answering: wait, or Stop it first",
        )
        Help(FxCost.words(offer.estimate), Modifier.weight(1f), color = c.ink45)
    }
}

// ---- The result ---------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ResultView(h: NeueHolders, s: GoldfishRuns.Shown) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val r = s.result
    val scope = rememberCoroutineScope()
    val name = name(h)
    val deck = h.builder.deck
    val copies = remember(deck, h.builder.index.size) {
        val canon = h.goldfishKitCanon()
        val counts = deck.main.map { canon(it.value) }.groupingBy { it }.eachCount()
        val f: (Int) -> Int = { counts[it] ?: 0 }
        f
    }
    val stale = remember(r, deck, h.effects.revision) {
        if (s.deckId != h.builder.deckId) emptyList()
        else GoldfishBrowse.stale(
            r, Ledger.fingerprint(deck, h.builder.index::byId), h.effects.trust().library((deck.main + deck.extra).map { it.value }),
            also = setOf(Ledger.fingerprintV1(deck)),
        )
    }
    // A result that arrives while the pane is open is brought into view: the headline, not the controls above it.
    val into = remember { BringIntoViewRequester() }
    val seen = remember { runs.reveal }
    LaunchedEffect(runs.reveal) { if (runs.reveal != seen) into.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(into), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (s.kept) "Kept result" else "Result", color = c.ink45)
            RowText("“${r.target.name}”", Modifier.weight(1f), color = c.ink70)
            MicroLink("Close", { runs.shown = null; runs.picked = null })
        }
        if (stale.isNotEmpty()) Small("Stale: ${stale.joinToString(", and ")}. Run it again for today's number.", color = c.ink)
        // The number big and plain; what follows it (the cards played as inert) in the body's tier, so a long list of
        // names never buries the number.
        val headline = remember(r, copies) { GoldfishWords.headline(r, name, copies) }
        val cut = headline.indexOf(". ").let { if (it < 0) headline.length else it + 1 }
        H2(headline.substring(0, cut), maxLines = 6)
        if (cut < headline.length) RowText(headline.substring(cut).trim(), color = c.ink70, maxLines = 12)
        OutcomeBar(r, runs.picked) { runs.pick(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(HandEnd.REACHED, HandEnd.NO_LINE, HandEnd.UNDECIDED).forEach { end ->
                val pick = HandPick.End(end)
                val n = GoldfishBrowse.count(r, pick)
                Tag(
                    "${GoldfishBrowse.endWords(end)} ${GoldfishWords.pct(n.toDouble() / r.hands.coerceAtLeast(1))}",
                    runs.picked == pick,
                    { runs.pick(pick) },
                    count = GoldfishWords.count(n),
                    caption = "List these hands",
                )
            }
        }
        (runs.picked as? HandPick.End)?.let { HandList(h, s, it) }
        Lines(h, s)
        Trust(h, r)
        Inert(h, s)
        r.notComputable.forEach { nc -> Small(nc.why, color = c.ink) }
        if (!s.kept) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MuButton("Keep this result", { scope.launch { runs.keep() } }, size = BtnSize.SM, enabled = s.deckId != null, reason = "Save the deck first")
                Help("Kept with the deck, its seed beside it, to compare with the next run. ${(r.ms / 100) / 10.0} s.", Modifier.weight(1f), color = c.ink45)
            }
        }
    }
}

private fun NeueHolders.goldfishKitCanon(): (Int) -> Int {
    val index = builder.index
    return { code -> index.byId(CardId(code))?.id?.value ?: code }
}

/** Reached, no line and undecided across the width, each share pressable: its hands are listed. */
@Composable
private fun OutcomeBar(r: GoldfishResult, picked: HandPick?, onPick: (HandPick) -> Unit) {
    val c = Mu.colors
    val (a, b, u) = GoldfishBrowse.shares(r)
    Row(Modifier.fillMaxWidth().height(20.dp).border(1.dp, c.ink)) {
        listOf(Triple(HandEnd.REACHED, a, 0), Triple(HandEnd.NO_LINE, b, 1), Triple(HandEnd.UNDECIDED, u, 2)).forEach { (end, share, kind) ->
            if (share > 0) {
                val on = picked == HandPick.End(end)
                Box(
                    Modifier.weight(share.toFloat()).fillMaxHeight()
                        .background(if (kind == 0) c.ink else c.paper)
                        .then(if (on) Modifier.border(2.dp, c.ink) else Modifier)
                        .cursorPointer(caption = "${GoldfishBrowse.endWords(end)}: list these hands")
                        .muClickable { onPick(HandPick.End(end)) },
                ) {
                    if (kind == 1) Hatch(Modifier.fillMaxSize(), color = c.ink45)
                }
            }
        }
    }
}

/** The lines found, each as its cards' art with the hands it reached; a line pressed lists them. */
@Composable
private fun Lines(h: NeueHolders, s: GoldfishRuns.Shown) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val r = s.result
    val phone = LocalPhone.current
    var all by remember(r) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Lines", color = c.ink45)
        RowText(GoldfishWords.lines(r), color = c.ink, maxLines = 4)
        val shown = if (all) r.lines else r.lines.take(LINES)
        shown.forEachIndexed { i, line ->
            key(i) {
                val pick = HandPick.Line(i)
                val on = runs.picked == pick
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, if (on) c.ink else c.ink12)
                        .cursorPointer(caption = "List these hands")
                        .muClickable { runs.pick(pick) }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LineStrip(h, line, if (phone) 36.dp else 44.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mono("${GoldfishWords.count(line.count)} hands · ${GoldfishWords.pct(line.count.toDouble() / r.hands.coerceAtLeast(1))}", color = c.ink)
                        if (line.touches.isNotEmpty()) Small("touches ${line.touches.joinToString { name(h)(it) }}, unknown", color = c.ink70)
                    }
                }
                if (on) HandList(h, s, pick)
            }
        }
        if (r.lines.size > LINES) MicroLink(if (all) "Fewer lines" else "All ${r.lines.size} lines", { all = !all })
    }
}

/** At most this many lines before "All N lines". */
private const val LINES = 6

/** A line as its cards' art in order, an arrow between; its words under the art where a card cannot be drawn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineStrip(h: NeueHolders, line: LineCount, width: Dp) {
    val c = Mu.colors
    val index = h.builder.index
    // A result kept before the line carried its cards: read them off the skeleton's names.
    val cards = remember(line, index.size) {
        line.cards.ifEmpty {
            if (line.skeleton == "as dealt") emptyList()
            else line.skeleton.split(" → ").mapNotNull { part -> index.byName(part.removePrefix("Set "))?.id?.value }
        }
    }
    if (cards.isEmpty()) {
        RowText(line.skeleton, color = c.ink, maxLines = 3)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            cards.forEachIndexed { i, code ->
                if (i > 0) Mono("→", color = c.ink45)
                CardArt(h, code, width, dimmed = false, caption = "Open") { openCard(h, code) }
            }
        }
        Small(line.skeleton, color = c.ink70, maxLines = 3)
    }
}

/** The hands [pick] stands for, a page at a time, each its cards' art; a hand pressed opens as a replay. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HandList(h: NeueHolders, s: GoldfishRuns.Shown, pick: HandPick) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val r = s.result
    val hands = remember(r, pick) { GoldfishBrowse.hands(r, pick) }
    val phone = LocalPhone.current
    // Pressed open, the list is brought into view.
    val into = remember { BringIntoViewRequester() }
    LaunchedEffect(pick) { into.bringIntoView() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(into).background(c.ink06).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowText(GoldfishBrowse.title(r, pick), Modifier.weight(1f), color = c.ink, maxLines = 3)
            MicroLink("Close", { runs.pick(null) })
        }
        Small(GoldfishBrowse.explain(pick), color = c.ink70)
        s.why?.let { Small(it, color = c.ink) } ?: Help("Press a hand to watch it as a replay on the Duel page: the deal, then its line a step at a time.", color = c.ink45)
        hands.take(runs.listed).forEach { o -> key(o.index) { HandRow(h, s, o, if (phone) 30.dp else 44.dp) } }
        if (hands.size > runs.listed) {
            MicroLink("${GoldfishWords.count(hands.size - runs.listed)} more — show ${GoldfishRuns.PAGE} more", { runs.listed += GoldfishRuns.PAGE })
        }
        if (hands.isEmpty()) Small("No hands.", color = c.ink70)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandRow(h: NeueHolders, s: GoldfishRuns.Shown, o: HandOutcome, width: Dp) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val opens = s.setup != null
    val opening = runs.opening == o.index
    Row(
        Modifier.fillMaxWidth()
            .background(c.paper)
            .cursorPointer(caption = "Watch it as a replay", enabled = opens && runs.opening == null, reason = s.why ?: "Another hand is opening")
            .muClickable(enabled = opens && runs.opening == null) { h.openGoldfishHand(o.index) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.widthIn(min = 64.dp)) {
            Mono(GoldfishBrowse.handName(o), color = c.ink)
            Micro(if (opening) "Opening…" else GoldfishBrowse.endWords(o.end), color = c.ink45)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            o.hand.forEach { code -> CardThumb(h, code, width) }
        }
    }
}

/** The written effects the run trusted, and those with open warnings — accepted here, with why, or repaired. */
@Composable
private fun Trust(h: NeueHolders, r: GoldfishResult) {
    val c = Mu.colors
    val name = name(h)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Written effects it used", color = c.ink45)
        RowText(GoldfishWords.trust(r, name), color = c.ink, maxLines = 8)
        if (r.used.isNotEmpty()) ArtStrip(h, r.used, if (LocalPhone.current) 36.dp else 44.dp)
        r.warned.forEach { code ->
            key(code) {
                val e = h.effects.entry(code)
                Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CardArt(h, code, 36.dp, dimmed = false, caption = "Open") { openCard(h, code) }
                        Column(Modifier.weight(1f)) {
                            RowText(name(code), color = c.ink)
                            Small(
                                if (e == null || e.open.isEmpty()) "Its warnings are settled: the next run says so."
                                else "${e.open.size} open warning${if (e.open.size == 1) "" else "s"}: accept with why if it is right, or repair it.",
                                color = c.ink70,
                            )
                        }
                    }
                    e?.open?.forEach { f -> key(f.key) { WarningRow(h, code, f.key, "Warning${f.effect?.let { " · $it" }.orEmpty()}: ${f.message}") } }
                    if (e != null && e.open.isNotEmpty() && h.neue.prefs.ai.enabled) {
                        MicroLink("Repair it", { h.writeEffects(listOf(code), FxFrom.PANE, "${name(code)}'s effect") })
                    }
                }
            }
        }
    }
}

/** The cards played as inert: how many hands held one (pressable), their art, and Write these. */
@Composable
private fun Inert(h: NeueHolders, s: GoldfishRuns.Shown) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val r = s.result
    if (r.unknown.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Played as inert", color = c.ink45)
        RowText(
            "${r.unknown.size} card${if (r.unknown.size == 1) "" else "s"} of the deck ha${if (r.unknown.size == 1) "s" else "ve"} no written effect the goldfish trusts: never activated, summoned or used as material.",
            color = c.ink,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MicroLink("${GoldfishWords.count(r.heldUnknown)} hands held one", { runs.pick(HandPick.HeldUnknown) })
            if (r.touchedUnknown > 0) MicroLink("${GoldfishWords.count(r.touchedUnknown)} reached through one", { runs.pick(HandPick.TouchedUnknown) })
        }
        (runs.picked as? HandPick.HeldUnknown)?.let { HandList(h, s, it) }
        (runs.picked as? HandPick.TouchedUnknown)?.let { HandList(h, s, it) }
        ArtStrip(h, r.unknown, if (LocalPhone.current) 36.dp else 44.dp)
        WriteThese(h, r.unknown, "the cards the goldfish played as inert in ${h.builder.deckName}")
    }
}

// ---- Kept results -------------------------------------------------------------------------------------------------

@Composable
private fun Kept(h: NeueHolders, doc: GoldfishDoc) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val deck = h.builder.deck
    val fingerprint = remember(deck) { Ledger.fingerprint(deck, h.builder.index::byId) }
    val earlier = remember(deck) { Ledger.fingerprintV1(deck) }
    val library = remember(deck, h.effects.revision) { h.effects.trust().library((deck.main + deck.extra).map { it.value }) }
    val results = remember(doc) { GoldfishBrowse.newestFirst(doc.results) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Kept results", color = c.ink45)
        if (results.isEmpty()) Small("A result you keep stays here with its seed, to set beside the next run.", color = c.ink70)
        results.forEach { r ->
            key(r.at, r.seed, r.target.id) {
                val stale = GoldfishBrowse.stale(r, fingerprint, library, also = setOf(earlier))
                val on = runs.shown?.result == r
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, if (on) c.ink else c.ink12)
                        .cursorPointer(caption = "Show it")
                        .muClickable { runs.show(r, h.goldfishDeck()) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RowText(GoldfishBrowse.keptWords(r), color = c.ink, maxLines = 2)
                    Help(
                        listOfNotNull(
                            r.at.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(java.util.Date(it)) },
                            if (stale.isEmpty()) null else "stale: ${stale.joinToString(", ")}",
                        ).joinToString(" · ").ifEmpty { "kept" },
                        color = if (stale.isEmpty()) c.ink45 else c.ink70,
                    )
                }
            }
        }
    }
}

// ---- The target editor --------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetEditor(h: NeueHolders, deckId: String, e: GoldfishRuns.Editing) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val scope = rememberCoroutineScope()
    val name = name(h)
    val d = e.draft
    fun set(next: TargetDraft) { runs.editing = e.copy(draft = next) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro(if (e.id == null) "New end board" else "Edit end board", color = c.ink45)
            if (e.by == EndBoard.AI) Small("${h.ai.name} named this one. Saved here, it is yours.", color = c.ink70)
            MuInput(d.name, { set(d.copy(name = it.take(120))) }, Modifier.fillMaxWidth(), placeholder = d.suggestedName(name))
        }
        BoardPlace.entries.forEach { p -> key(p) { PlaceEditor(h, e, p) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                RowText("Set Spells and Traps", color = c.ink)
                Help("Face-down in your Spell & Trap Zones at the turn's end.", color = c.ink45)
            }
            Stepper(d.set, { set(d.copy(set = it)) }, min = 0, max = TargetDraft.MOST)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                RowText("Interruptions", color = c.ink)
                Help(
                    "Counted from the written effects on the board, never guessed: an effect usable on their turn that negates, or " +
                        "destroys, banishes or returns a card of theirs — one per once-per-turn.",
                    color = c.ink45,
                )
            }
            Stepper(d.interruptions, { set(d.copy(interruptions = it)) }, min = 0, max = TargetDraft.MOST)
        }
        if (d.kept.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Also, as written", color = c.ink45)
                d.kept.forEachIndexed { i, cond ->
                    key(i) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Small(BoardCheck.words(cond, name), Modifier.weight(1f), color = c.ink)
                            MicroLink("Remove", { set(d.copy(kept = d.kept.filterIndexed { k, _ -> k != i })) })
                        }
                    }
                }
            }
        }
        HRule()
        val board = d.board(e.id ?: "", deckId, EndBoard.PERSON, 0L, name)
        Small(if (d.conditions == 0) "Nothing asked yet." else "Reads: ${BoardCheck.words(board, name)}.", color = c.ink70)
        val problem = d.problem()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton(
                "Save",
                {
                    val id = e.id ?: "t${System.currentTimeMillis().toString(36)}"
                    val target = d.board(id, deckId, EndBoard.PERSON, System.currentTimeMillis(), name)
                    runs.editing = null
                    runs.targetId = id
                    scope.launch { h.effects.putTarget(deckId, target) }
                },
                variant = BtnVariant.PRIMARY,
                size = BtnSize.SM,
                enabled = problem == null,
                reason = problem,
            )
            MuButton("Cancel", { runs.editing = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    }
}

/** One place's needs — each its art (or its kind's words), a count and ✕ — "any one of these", and Add with its picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(h: NeueHolders, e: GoldfishRuns.Editing, p: BoardPlace) {
    val c = Mu.colors
    val runs = h.effects.goldfishRuns
    val name = name(h)
    val d = e.draft
    val s = d.section(p)
    val phone = LocalPhone.current
    fun set(next: TargetDraft) { runs.editing = (runs.editing ?: e).copy(draft = next) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowText(p.words, Modifier.weight(1f), color = c.ink)
            val picking = e.picking == p
            MuButton(
                if (picking) "Done" else "Add",
                { runs.editing = (runs.editing ?: e).copy(picking = if (picking) null else p) },
                size = BtnSize.SM,
                variant = if (picking) BtnVariant.SECONDARY else BtnVariant.SUBTLE,
                icon = if (picking) null else Icons.Plus,
            )
        }
        s.needs.forEach { need ->
            key(need.what) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val w = need.what) {
                        is Needed.Card -> CardArt(h, w.code, 36.dp, dimmed = false, caption = "Open") { openCard(h, w.code) }
                        else -> Box(Modifier.size(36.dp, 52.dp).border(1.dp, c.ink25))
                    }
                    RowText(TargetDraft.needWords(need.copy(n = 1), name).removePrefix("1 "), Modifier.weight(1f), color = c.ink, maxLines = 2)
                    Stepper(need.n, { set(d.count(p, need.what, it)) }, min = 1, max = TargetDraft.MOST)
                    IconButton(Icons.X, { set(d.remove(p, need.what)) }, label = "Take it out")
                }
            }
        }
        if (s.needs.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuCheckbox(s.anyOne, { set(d.anyOne(p, it)) })
                Small("Any one of these is enough", color = c.ink)
            }
        }
        if (e.picking == p) {
            val index = h.builder.index
            val deck = h.builder.deck
            val cards = remember(deck, index.size, p) {
                val canon = h.goldfishKitCanon()
                val main = deck.main.map { canon(it.value) }.distinct()
                val extra = deck.extra.map { canon(it.value) }.distinct()
                if (p == BoardPlace.FIELD) extra + main else if (p == BoardPlace.HAND) main else main + extra
            }
            Column(Modifier.fillMaxWidth().background(c.ink06).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Help("Pick by art: the deck's cards. Or a kind, below.", color = c.ink70)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val across = if (phone) 5 else 8
                    val w = ((maxWidth - 4.dp * (across - 1)) / across).coerceIn(40.dp, 64.dp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cards.forEach { code ->
                            key(code) {
                                val had = s.needs.any { it.what == Needed.Card(code) }
                                CardArt(h, code, w, dimmed = had, caption = if (had) "One more" else "Add it") { set(d.add(p, Needed.Card(code))) }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeedKind.offered(p).forEach { k ->
                        val had = s.needs.any { it.what == Needed.Kind(k) }
                        Tag("Any ${k.words}", had, { set(d.add(p, Needed.Kind(k))) }, caption = "Add it")
                    }
                }
            }
        }
        HRule()
    }
}

// ---- Art ----------------------------------------------------------------------------------------------------------

/** Cards' art in a row that wraps, each opening the card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtStrip(h: NeueHolders, cards: List<Int>, width: Dp) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cards.forEach { code -> key(code) { CardArt(h, code, width, dimmed = false, caption = "Open") { openCard(h, code) } } }
    }
}

/** A card in a hand's row: its art, small, not a target of its own (the row opens the hand). */
@Composable
private fun CardThumb(h: NeueHolders, code: Int, width: Dp) {
    val c = Mu.colors
    val card = h.builder.index.byId(CardId(code))
    if (card == null) {
        Box(Modifier.width(width).height(width / CARD_RATIO).border(1.dp, c.ink25))
    } else {
        NeueCard(card, Modifier.width(width), format = h.builder.format, foil = "off")
    }
}

/** Opens [code] large, in the card viewer. */
private fun openCard(h: NeueHolders, code: Int) {
    h.builder.index.byId(CardId(code))?.let { h.neue.viewing = Viewing(it, null, 0) }
}
