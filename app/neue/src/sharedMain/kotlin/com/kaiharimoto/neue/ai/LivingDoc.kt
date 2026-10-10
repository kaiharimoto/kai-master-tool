package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.report.GuideDoc
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.ReportPdf
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope

/**
 * A living document Ai keeps (1.0.54, kai: "a living document for each deck that is a guide that
 * Ai updates across sessions … formatted in Master UI and … PDF exportable"): a deck's guide, or
 * the person's profile (Learn About You). Read here as a document — its scores and how they moved,
 * its key cards, its sections numbered — and exported as the same thing in a PDF. It is the memory
 * file itself: what Ai writes in any session appears here, and the brain edits the words.
 */
@Composable
fun LivingDocDialog(ai: AiState) {
    val open = ai.docOpen ?: return
    val h = ai.h
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    // Read afresh each time the document is opened, and after an edit in the brain.
    val stamp = remember(open) { mutableIntStateOf(0) }
    when (open) {
        is LivingDoc.Guide -> {
            // Read and sorted off the frame thread (1.1.11): the guide has no cap, and may be thousands of entries.
            val read by androidx.compose.runtime.produceState<GuideRead?>(null, open, stamp.intValue) {
                val deck = h.builder.deck.takeIf { h.builder.deckId == open.deckId }
                val print = deck?.let { Ledger.fingerprint(it, h.builder.index::byId) }
                val earlier = deck?.let { setOf(Ledger.fingerprintV1(it)) }.orEmpty()
                value = withContext(Dispatchers.IO) { GuideRead.of(ai, open.deckId, print, earlier) }
            }
            val doc = read?.doc ?: GuideDoc("", emptyList())
            val reports = read?.reports.orEmpty()
            MuDialog(
                title = "How ${open.deckName} plays",
                onDismiss = { ai.docOpen = null },
                width = 880.dp,
                description = "${ai.name}'s guide to this deck, kept across every Fine Tuning session. ${sessionsWords(reports.size)}." +
                    (read?.let { r -> if (r.doc.entryCount > 0) " ${r.doc.entryCount} entries, all of them here." else "" } ?: ""),
                scrolls = false,
                footer = {
                    MuButton("Edit in the brain", {
                        ai.docOpen = null
                        ai.memoryOpen = AiMemory.path(MemoryKind.GUIDE, open.deckId)
                    }, variant = BtnVariant.GHOST)
                    // Refactor guide (1.0.66): drop what does not help, sharpen the rest, put it in order.
                    MuButton("Refactor", {
                        ai.docOpen = null
                        ai.askTune(AiSession.MODE_REFACTOR)
                    }, variant = BtnVariant.GHOST, enabled = h.builder.deckId == open.deckId && !doc.isEmpty, reason = if (doc.isEmpty) "Nothing to refactor yet" else "Open the deck in the builder first")
                    // The reader's guide (1.0.67): the book written for people from these notes.
                    MuButton("Reader's guide", {
                        ai.docOpen = null
                        ai.openBook(open.deckId)
                    }, variant = BtnVariant.GHOST)
                    MuButton("Teach it more", {
                        ai.docOpen = null
                        ai.askTune()
                    }, variant = BtnVariant.SECONDARY, enabled = h.builder.deckId == open.deckId, reason = "Open the deck in the builder first")
                    MuButton(if (making) "Making the PDF…" else "Guide · PDF", {
                        making = true
                        scope.launch {
                            try {
                                AiDocs.deliverGuide(h, open.deckId, open.deckName)
                            } finally {
                                making = false
                            }
                        }
                    }, variant = BtnVariant.PRIMARY, icon = Icons.Export, enabled = !making, reason = "The PDF is being made")
                },
            ) {
                // Every entry its own row of a lazy list (1.1.11): the whole guide, never cut, and light to scroll.
                val r = read
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r == null) item { Help("Reading the guide…") }
                    if (r != null) item {
                        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            val latest = reports.lastOrNull()
                            if (latest != null) {
                                Micro("How well it knows this deck", color = Mu.colors.ink70)
                                ScoresRow(latest, reports)
                                if (reports.size >= 2) HistoryBars(reports.takeLast(8))
                                if (latest.why.isNotBlank()) Small(latest.why, color = Mu.colors.ink70)
                            } else {
                                Help("No session has scored this deck yet: each Fine Tuning session ends with ${ai.name}'s confidence, and it shows here.")
                            }
                            val cards = remember(doc) { AiDocs.keyCards(h, doc) }
                            if (cards.isNotEmpty()) KeyCards(ai, cards)
                        }
                    }
                    // Each number's proof, as it stands (1.0.98, the evidence ledger): checked, stale, contradicted, estimate.
                    if (r != null) sections(r.doc, empty = "The guide is empty. Teach ${ai.name} the deck, let it study it, or have it learn it from first principles.") { e -> r.proofOf(e) }
                }
            }
        }
        LivingDoc.Profile -> {
            val doc = androidx.compose.runtime.produceState(GuideDoc("", emptyList()), stamp.intValue) {
                value = withContext(Dispatchers.IO) { GuideDoc.profile(ai.files.read(AiMemory.path(MemoryKind.USER))) }
            }.value
            MuDialog(
                title = "What ${ai.name} knows about you",
                onDismiss = { ai.docOpen = null },
                width = 760.dp,
                description = "Your profile: in front of ${ai.name} in every conversation, and built on each time it learns about you.",
                scrolls = false,
                footer = {
                    MuButton("Edit in the brain", {
                        ai.docOpen = null
                        ai.memoryOpen = AiMemory.path(MemoryKind.USER)
                    }, variant = BtnVariant.GHOST)
                    MuButton("Learn more about me", {
                        ai.docOpen = null
                        ai.profileAsk = true
                    }, variant = BtnVariant.PRIMARY, arrow = true, enabled = ai.configured, reason = "Set up ${ai.name} first")
                },
            ) {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections(doc, empty = "Nothing yet. Learn About You is an interview — your goals, your preferences, how you work — and what ${ai.name} learns is kept here.")
                }
            }
        }
    }
}

private fun sessionsWords(n: Int) = when (n) {
    0 -> "No sessions scored yet"
    1 -> "One session so far"
    else -> "$n sessions so far"
}

private const val MIRROR = "Mirror match"

/** The three scores side by side: a number, ten cells of meter, and how it moved since last time. */
@Composable
internal fun ScoresRow(r: SessionReport, log: List<SessionReport>) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    // A phone's column is a third of 360 dp: the short words, or "Understanding" is cut.
    val phone = LocalPhone.current
    Row(horizontalArrangement = Arrangement.spacedBy(if (phone) 14.dp else 20.dp)) {
        listOf(
            Triple(if (phone) "Knows it" else "Understanding", r.understanding, "What the deck is for, and how its cards fit") to ReportLog.change(log, r) { it.understanding },
            Triple(if (phone) "Plays it" else "Playing it", r.playing, "Piloting it, turn by turn") to ReportLog.change(log, r) { it.playing },
            Triple(MIRROR, r.mirror, "Best-of-three wins it expects against the same deck") to ReportLog.change(log, r) { it.mirror },
        ).forEach { (t, delta) ->
            val (label, value, caption) = t
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(c.ink))
                Micro(label, color = c.ink70)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MuText(if (label == MIRROR) "$value%" else value.toString(), style = MuType.mono(f, 34.sp), color = c.ink)
                    if (label != MIRROR) Mono("/100", Modifier.padding(bottom = 6.dp), color = c.ink45)
                    Box(Modifier.weight(1f))
                    delta?.let { Mono(if (it == 0) "±0" else if (it > 0) "+$it" else "−${-it}", Modifier.padding(bottom = 6.dp), color = if (it < 0) c.ink45 else c.ink) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(10) { k ->
                        Box(Modifier.weight(1f).height(7.dp).let { if (k < (value + 5) / 10) it.background(c.ink) else it.border(1.dp, c.ink25) })
                    }
                }
                Small(caption, color = c.ink45)
            }
        }
    }
}

/** The scores session by session, three bars a session in the ink ramp. */
@Composable
private fun HistoryBars(reports: List<SessionReport>) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Session by session", color = c.ink70)
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val slot = size.width / reports.size
            val bar = minOf(14.dp.toPx(), (slot - 16.dp.toPx()) / 3)
            listOf(0f, 0.5f, 1f).forEach { v ->
                val y = size.height * (1 - v)
                drawLine(if (v == 0f) c.ink else c.ink12, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            reports.forEachIndexed { i, r ->
                val x0 = i * slot + (slot - 3 * bar - 4.dp.toPx()) / 2
                listOf(r.understanding to c.ink, r.playing to c.ink45, r.mirror to c.ink25).forEachIndexed { k, (v, g) ->
                    val hgt = size.height * v / 100f
                    drawRect(g, Offset(x0 + k * (bar + 2.dp.toPx()), size.height - hgt), Size(bar, hgt))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Understanding" to c.ink, "Playing it" to c.ink45, "Mirror match" to c.ink25).forEach { (name, g) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(g))
                    Small(name, color = c.ink70)
                }
            }
            Box(Modifier.weight(1f))
            Mono("${reports.size} sessions", color = c.ink45)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCards(ai: AiState, cards: List<ReportPdf.KeyCard>) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Key cards", color = c.ink70)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { k ->
                val card = ai.h.builder.index.byId(k.id) ?: return@forEach
                Column(Modifier.width(84.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NeueCard(card, Modifier.fillMaxWidth().aspectRatio(CARD_RATIO), foil = "off")
                    Small(card.name, color = c.ink, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Each section numbered over a rule, its entries as hanging lines with their cards in bold — each heading and each entry
 * an item of the lazy list it is put in (1.1.9), so a guide of any length is shown whole and scrolls lightly.
 */
private fun LazyListScope.sections(doc: GuideDoc, empty: String, proofOf: (String) -> Proven? = { null }) {
    if (doc.isEmpty) {
        item { Help(empty) }
        return
    }
    doc.sections.forEachIndexed { i, s ->
        item(key = "section-$i") {
            val c = Mu.colors
            val f = LocalMuFonts.current
            Column(Modifier.padding(top = if (i == 0) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mono((i + 1).toString().padStart(2, '0'), color = c.ink45)
                    MuText(s.name, Modifier.weight(1f), style = MuType.body(f).copy(fontWeight = FontWeight.Bold, fontSize = 17.sp), color = c.ink)
                    Mono(s.entries.size.toString(), color = c.ink45)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink))
            }
        }
        items(s.entries.size) { k ->
            val e = s.entries[k]
            val c = Mu.colors
            val f = LocalMuFonts.current
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono("–", color = c.ink45)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MuText(styled(ChatMarkdown.inline(e)), Modifier.fillMaxWidth(), style = MuType.body(f).copy(fontSize = 14.sp), color = c.ink)
                    proofOf(e)?.let { p -> ProofLine(p) }
                }
            }
        }
    }
}

/**
 * A deck's guide as the living document shows it (1.1.9), read off the frame thread: the guide sorted by its labels,
 * the reports, and each entry's proof found by its words in one lookup, never a search of the ledger per entry.
 */
private class GuideRead(val doc: GuideDoc, val reports: List<SessionReport>, private val proofs: Map<String, Proven>) {
    fun proofOf(entry: String): Proven? = proofs[entry]

    companion object {
        fun of(ai: AiState, deckId: String, print: String?, also: Set<String> = emptySet()): GuideRead {
            val doc = GuideDoc.parse(ai.files.read(AiMemory.path(MemoryKind.GUIDE, deckId)))
            val ledger = Ledger.read(ai.files.read(Ledger.path(deckId))).let { l -> print?.let { Ledger.staleAgainst(l, it, also = also) } ?: l }
            // A section shows an entry without its label: a proof is found by the whole entry or by what follows the label.
            val proofs = HashMap<String, Proven>()
            ledger.forEach { p ->
                proofs.putIfAbsent(p.entry, p)
                proofs.putIfAbsent(GuideDoc.split(p.entry).second, p)
            }
            return GuideRead(doc, ai.files.reports(deckId), proofs)
        }
    }
}

/**
 * Where a guide entry's number came from (1.0.98): what checked it and on what, or why it no longer holds. Ink only:
 * a contradicted number is set in bold, never in colour.
 */
@Composable
private fun ProofLine(p: Proven) {
    val c = Mu.colors
    val words = when (p.status) {
        Proven.Status.CHECKED -> "Checked · " + p.proofs.joinToString(" · ") { proof -> proof.tool + proof.input.takeIf { it.length in 3..80 }?.let { " $it" }.orEmpty() }
        else -> Ledger.mark(p).orEmpty().replaceFirstChar { it.uppercase() }
    }
    val style = MuType.small(LocalMuFonts.current).let { if (p.status == Proven.Status.CONTRADICTED) it.copy(fontWeight = FontWeight.Bold) else it }
    MuText(words, style = style, color = if (p.status == Proven.Status.CHECKED) c.ink45 else c.ink)
}

/**
 * How a Fine Tuning session ended (1.0.54), for the end dialog: the report Ai filed, with its PDF
 * and the guide's. Empty when it filed none.
 */
@Composable
internal fun EndReport(ai: AiState, report: SessionReport) {
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    val log = remember(report) { ai.files.reports(report.deckId).ifEmpty { listOf(report) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (report.summary.isNotBlank()) MuText(styled(ChatMarkdown.inline(report.summary)), style = MuType.body(LocalMuFonts.current).copy(fontSize = 16.sp), color = c.ink)
        ScoresRow(report, log)
        if (report.why.isNotBlank()) Small(report.why, color = c.ink70)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton(if (making) "Making the PDF…" else "Report · PDF", {
                making = true
                scope.launch {
                    try {
                        AiDocs.deliverReport(ai.h, report)
                    } finally {
                        making = false
                    }
                }
            }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Export, enabled = !making, reason = "The PDF is being made")
            MuButton("Open the guide", { ai.docOpen = LivingDoc.Guide(report.deckId, report.deckName) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
}
