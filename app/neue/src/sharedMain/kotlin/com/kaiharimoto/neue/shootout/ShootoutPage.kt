package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.bench.TrialDraws
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.teach.TeachGate
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.pages.PageHeader
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 09 Shootout (1.1.2, Phase S stage 2; kai: "a data proven rating for each card/card pair in a deck, gathered by
 * comparing hands"): the deck — the builder's unless another is chosen — judged alone or against an opponent of its
 * web. A session shows one hand at a time, each chosen for what it teaches: your hand (and theirs) as large card art,
 * who goes first, game 1 or sided, the one question the hand asks and five answers under keys 1 to 5 (a click, or a
 * swipe on a phone); a comparison is two hands, ← or →. Its progress is the stop rule's line; it stops at any moment
 * with every answer kept. The results stand the situations side by side, and every number opens its hands.
 *
 * While a session runs the page header gives its room to the cards (design review, 1.1.6): the deck, Results, Trust
 * and Stop stand in the window's bar ([ShootoutBarItems]), or a slim row on a phone ([PhoneSessionRow]), as on Duel.
 *
 * Master UI throughout: ink on paper, the cards the only colour, and nothing moves but them.
 */
@Composable
fun ShootoutPage(h: NeueHolders) {
    val s = h.shootout
    // The builder's deck, read afresh while the page follows it — never under a session's feet.
    LaunchedEffect(h.builder.deckId, h.webs.loaded, h.decksReload) {
        if (!s.running && (s.deckId == null || s.bench == null && s.problem == null)) s.prepare()
    }
    val phone = LocalPhone.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                !s.running -> PageHeader(numeral = 9, title = "Shootout", subtitle = subtitle(s)) { HeaderActions(h, phone) }
                phone -> PhoneSessionRow(h)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.view) {
                    Shootouts.View.SETUP -> SetupView(h)
                    Shootouts.View.TRIAL -> TrialView(h, phone)
                    Shootouts.View.RESULTS -> ResultsView(h, phone)
                    Shootouts.View.EXAM -> ExamView(h)
                    Shootouts.View.VERSUS -> VersusView(h, phone)
                }
            }
        }
        if (s.teach.trustOpen) TrustDialog(h)
        if (s.teach.rubricOpen) RubricDialog(h)
        if (s.view == Shootouts.View.VERSUS && s.versus.choosing) SubstituteChooser(h)
        s.behind?.let { TrialsDialog(h, it) }
    }
}

/** The deck, the target and the hands judged: the header's line, and the bar's while a session runs. */
internal fun subtitle(s: Shootouts): String {
    val target = s.bench?.opponentName?.let { "Against $it" } ?: "The deck alone"
    return listOf(s.deckName.ifBlank { "No deck" }, target, "${ShootoutWords.hands(s.handsJudged)} judged").joinToString(" · ")
}

/** A key's chord as the table writes it, else [fallback]. */
internal fun keyOf(action: DeskAction, fallback: String) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd) ?: fallback

/** A key cap on the desk only: a phone has no keys to press (design review, 1.1.6), and [Kbd] hides it without a keyboard. */
@Composable
internal fun KeyCap(text: String) {
    if (!LocalPhone.current) Kbd(text)
}

/**
 * The header's actions on Setup, Results and the exam (design review, 1.1.6: each action once): the deck and opponent,
 * Results and Trust with their keys. Begin is the body's; Stop is the bar's while a session runs.
 */
@Composable
private fun HeaderActions(h: NeueHolders, phone: Boolean) {
    val s = h.shootout
    val chosen = s.deckId ?: h.builder.deckId
    if (s.decks.isNotEmpty()) {
        MuSelect(chosen, s.decks.map { it.id }, { id -> s.decks.firstOrNull { it.id == id }?.name ?: "Choose a deck" }, { id ->
            s.chooseDeck(if (id == h.builder.deckId) null else id)
        }, Modifier.width(220.dp), small = true)
    }
    if (s.bench != null || s.opponents.isNotEmpty()) {
        val options = listOf<String?>(null) + s.opponents.map { it.id }
        MuSelect(s.opponentId, options, { id -> if (id == null) "The deck alone" else "Against " + (s.opponents.firstOrNull { it.id == id }?.name ?: "?") }, s::chooseOpponent, Modifier.width(240.dp), small = true)
    }
    // Card against card has its own results and stop: the header only leads back to the Shootout.
    if (s.view == Shootouts.View.VERSUS) {
        MuButton("Shootout", s.versus::leave, size = BtnSize.SM, variant = BtnVariant.GHOST)
        return
    }
    val onResults = s.view == Shootouts.View.RESULTS
    val none = s.handsJudged == 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MuButton(
            if (onResults) "Setup" else "Results", s::toggleResults, size = BtnSize.SM,
            enabled = s.bench != null && (onResults || !none),
            reason = s.problem ?: "Judge a few hands first",
        )
        if (!phone) KeyCap(keyOf(DeskAction.SHOOTOUT_RESULTS, "R"))
    }
    // Trust is a teaching control: offered with the steps, not before (kai's choice, 1.1.8); the key still opens it.
    if (h.ai.enabled && s.teachShown) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MuButton("Trust", s.teach::openTrust, size = BtnSize.SM, variant = BtnVariant.GHOST, enabled = s.bench != null, reason = s.problem)
            if (!phone) KeyCap(keyOf(DeskAction.SHOOTOUT_TRUST, "T"))
        }
    }
}

// ---- before a session -------------------------------------------------------------------------------------------

@Composable
private fun SetupView(h: NeueHolders) {
    val s = h.shootout
    val c = Mu.colors
    val bench = s.bench
    val problem = s.problem
    val phone = LocalPhone.current
    if (bench == null) {
        if (problem != null) {
            EmptyState("Nothing to deal yet.", problem) {
                if (h.builder.deckId == null) MuButton("Save the deck", { h.builder.save { h.decksReload++ } }, variant = BtnVariant.PRIMARY, arrow = true)
            }
        } else {
            Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathe()
                Small("Reading the deck")
            }
        }
        return
    }
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            H2(bench.opponentName?.let { "Against $it" } ?: "The deck alone")
            Body(
                if (bench.alone) {
                    "You are shown an opening hand and who goes first, and say how often a hand like it does what the deck wants: from plays through to bricks. Each hand is chosen for what it would teach, so the ratings settle in far fewer hands than a shuffle would take."
                } else {
                    "You are shown your hand and theirs, which game and who goes first, and say how the game goes: from a clear win to a clear loss. Each card is rated per turn and per game, game 1 and after siding, pooled so a few sided hands borrow from many game 1 ones."
                },
                color = c.ink70,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("Which hands", color = c.ink45)
            val options = listOf<Stratum?>(null) + bench.strata
            val words: (Stratum?) -> String = { st -> st?.let { ShootoutWords.situation(it, bench.opponentName) } ?: "Mixed (recommended)" }
            // Five choices across a phone do not fit, and the sided ones fell off its edge: there it is a menu.
            if (phone) {
                MuSelect(s.pinned, options, words, { s.pinned = it }, Modifier.fillMaxWidth(), small = true)
            } else {
                Segmented(s.pinned, options, { it?.let(ShootoutWords::stratum) ?: "Mixed (recommended)" }, { s.pinned = it }, small = true)
            }
        }
        if (bench.waiting.isNotEmpty()) {
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Waiting", color = c.ink45)
                bench.waiting.forEach { (stratum, why) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Small(ShootoutWords.situation(stratum, bench.opponentName), color = c.ink)
                        Small(why, Modifier.weight(1f, fill = false))
                    }
                }
                val deck = s.deckId ?: h.builder.deckId
                if (deck != null) MicroLink("Write the plans on Siding", { h.webs.side(deck, s.opponentId) })
            }
        }
        // Teaching Ai (Phase S stage 3) as numbered steps, offered once the person has judged a session's hands for the deck
        // or has taught Ai before (kai's choice, 1.1.8); a first visit is only for rating hands.
        val teaching = h.ai.enabled && s.teachShown
        LaunchedEffect(teaching, s.log?.trials?.size, s.log?.notes?.size, s.log?.trust, bench, h.ai.name) {
            if (teaching) s.teach.readProgress()
        }
        if (teaching) TeachSetup(h)
        SetupActions(h, teaching)
        VersusEntry(h)
        Help("About ten minutes is a session. Stop whenever you like: every answer is kept, and the ratings carry over to the next one. The cards are rated per copy, against the card the deck would have dealt instead.")
        if (h.ai.enabled && !teaching) Small(TeachGate.line(s.deckTally, h.ai.name), color = c.ink70)
    }
}

// ---- a trial ----------------------------------------------------------------------------------------------------

@Composable
private fun TrialView(h: NeueHolders, phone: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    val p = s.proposal
    val bench = s.bench
    if (p == null || bench == null) {
        Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Breathe()
            Small(if (s.teach.routing) "${h.ai.name} is judging a hand of a kind it has earned" else "Choosing a hand")
        }
        return
    }
    var hint by remember { mutableStateOf<Answer?>(null) }
    val pad = if (phone) 12.dp else 32.dp
    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = if (phone) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp)) {
        // The situation, and the session's progress.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            H2(ShootoutWords.situation(p.stratum, bench.opponentName), Modifier.weight(1f), maxLines = 2)
            if (!phone) Small(progressWords(s), color = c.ink45, maxLines = 1)
        }
        if (phone) Small(progressWords(s), color = c.ink45, maxLines = 2)
        TeachBanner(h)
        if (s.sessionMs >= ShootoutRun.SESSION_MS) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Small("Ten minutes in: a good place to stop. Every answer is kept.")
                MicroLink("Stop and see the results", s::stop)
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { m -> if (p is Proposal.Rate) m.swipeToAnswer(onHint = { hint = it }, onAnswer = s::answer) else m },
        ) {
            when (p) {
                is Proposal.Rate -> RateHands(h, p, maxWidth, maxHeight, phone)
                is Proposal.Compare -> CompareHands(h, p, maxWidth, maxHeight, phone)
            }
            hint?.let { a ->
                Box(Modifier.align(Alignment.TopCenter).background(c.ink).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Micro(ShootoutWords.label(a, bench.alone), color = c.paper)
                }
            }
        }
        AskCard(h)
        if (h.shootout.teach.ask == null) ReadingStrip(s.reading)
        VerdictBox(h, bench.alone)
        when (p) {
            is Proposal.Rate -> {
                // The one question every rating asks, over its answers (design review, 1.1.6).
                Body(ShootoutWords.question(bench.alone), color = c.ink)
                AnswerScale(s.thinking, s::answer, bench.alone, phone, aiSaid = h.shootout.teach.shownVerdict(h.ai.enabled), aiName = h.ai.name)
            }
            is Proposal.Compare -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Body("Which would you rather open with?", Modifier.weight(1f), color = c.ink)
                MuButton("← This one", { s.prefer(true) }, enabled = !s.thinking)
                MuButton("This one →", { s.prefer(false) }, enabled = !s.thinking)
            }
        }
        if (!phone) KeysLine(p is Proposal.Compare, bench.alone)
    }
}

/**
 * The trial's keys in one line under the scale, read from the table (design review, 1.1.6; Duel's KEYS idiom):
 * answer, draw, results, stop.
 */
@Composable
private fun KeysLine(compare: Boolean, alone: Boolean) {
    val c = Mu.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compare) {
            KeyCap("${keyOf(DeskAction.SHOOTOUT_LEFT, "←")} ${keyOf(DeskAction.SHOOTOUT_RIGHT, "→")}")
            Help("choose", color = c.ink45)
        } else {
            KeyCap("${keyOf(DeskAction.SHOOTOUT_ANSWER_1, "1")}–${keyOf(DeskAction.SHOOTOUT_ANSWER_5, "5")}")
            Help("answer", color = c.ink45)
        }
        KeyCap(keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"))
        Help(if (alone) "draw" else "draw for you", color = c.ink45)
        if (!alone) {
            KeyCap(keyOf(DeskAction.SHOOTOUT_DRAW_THEIRS, "Shift D"))
            Help("for them", color = c.ink45)
        }
        KeyCap(keyOf(DeskAction.SHOOTOUT_RESULTS, "R"))
        Help("results", color = c.ink45)
        KeyCap("Esc")
        Help("stop", color = c.ink45)
    }
}

/** The progress line: the calibration set's while one runs (design review, 1.1.6), else the stop rule's. */
private fun progressWords(s: Shootouts): String {
    val parts = mutableListOf<String>()
    val set = s.teach.set
    if (set != null) {
        parts += "Hand ${(s.teach.setAt + 1).coerceAtMost(set.size)} of ${set.size} · calibration"
    } else {
        s.settled?.let { parts += "${it.known} of ${it.of} cards known within ±${it.halfWidth.toInt()} points" + if (it.enough) " · enough to stop" else "" }
        parts += "${ShootoutWords.hands(s.sessionAnswers)} this session"
    }
    if (s.sessionMs >= 60_000) parts += "${s.sessionMs / 60_000} min"
    return parts.joinToString(" · ")
}

/**
 * A rating's hands: theirs above (smaller), yours below, each as large as the room allows. A hand of six is the player
 * going second's, and its turn's draw stands sixth, marked; cards turned up for draws by effects stand apart after a
 * hairline, marked +1, +2… (1.1.5, kai: "The sixth card should be marked as their top deck"; design review, 1.1.6). The
 * marked sixth is the top of the deck, so an effect's draw takes it and the turn's draw moves to the next card, which
 * stands after the drawn ones (1.1.7, kai).
 */
@Composable
private fun RateHands(h: NeueHolders, p: Proposal.Rate, width: Dp, height: Dp, phone: Boolean) {
    val s = h.shootout
    val mine = s.myShown(p)
    val theirs = s.theirShown(p)
    val gap = if (phone) 6.dp else 12.dp
    val label = 28.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (theirs != null) {
            val room = height - label * 2 - gap * 3
            HandHead(handWords("Their hand", theirs), "Draw for them", keyOf(DeskAction.SHOOTOUT_DRAW_THEIRS, "Shift D"), phone) { s.drawTheirs() }
            Hand(h, theirs, width, room * 0.38f, gap, phone)
            HandHead(handWords("Your hand", mine), "Draw for you", keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"), phone) { s.drawMine() }
            Hand(h, mine, width, room * 0.62f, gap, phone)
        } else {
            HandHead(handWords("Your hand", mine), "Draw a card", keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"), phone) { s.drawMine() }
            Hand(h, mine, width, height - label - gap, gap, phone)
        }
    }
}

/**
 * "Their hand · 6 cards · the 6th is their draw": the draw named by its place (design review, 1.1.6). Once an effect has
 * drawn the top card the hand is the five, and the words say the turn's draw comes after: "they drew 2 by effects before
 * their draw" (1.1.7). Cards drawn by effects carry their own words, over them.
 */
internal fun handWords(whose: String, hand: TrialDraws.Shown): String = buildList {
    val yours = whose == "Your hand"
    if (hand.shifted) {
        add("$whose · ${hand.opening.size} cards")
        val k = hand.drawn.size
        val drew = "${if (yours) "you" else "they"} drew $k by effects"
        add(if (hand.draw != null) "$drew before ${if (yours) "your" else "their"} draw" else "$drew; no card is left to draw")
    } else {
        val n = hand.opening.size + (if (hand.draw != null) 1 else 0)
        add("$whose · $n cards")
        if (hand.draw != null) add(if (yours) "the ${ordinal(n)} is your draw" else "the ${ordinal(n)} is their draw")
    }
}.joinToString(" · ")

private fun ordinal(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}

/**
 * A hand's label and its draw button: a card turned up off that side's deck for an effect that draws — a look ahead
 * only, the opening hand is still what is rated.
 */
@Composable
internal fun HandHead(words: String, draw: String, key: String, phone: Boolean, onDraw: () -> Unit) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro(words, Modifier.weight(1f), color = c.ink45, maxLines = if (phone) 2 else 1)
        MuButton(draw, onDraw, variant = BtnVariant.GHOST, size = BtnSize.SM)
        if (!phone) KeyCap(key)
    }
}

/** A comparison: two of your hands, the same opponent and turn; a press on either chooses it. */
@Composable
private fun CompareHands(h: NeueHolders, p: Proposal.Compare, width: Dp, height: Dp, phone: Boolean) {
    val s = h.shootout
    val bench = s.bench ?: return
    val gap = if (phone) 6.dp else 12.dp
    val theirs = p.opponent?.let(bench::opponentIds)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        val room = if (theirs != null) height * 0.72f else height
        if (theirs != null) {
            val shown = s.theirShown(p) ?: TrialDraws.Shown(theirs, emptyList(), null, false)
            HandHead(handWords("Their hand", shown), "Draw for them", keyOf(DeskAction.SHOOTOUT_DRAW_THEIRS, "Shift D"), phone) { s.drawTheirs() }
            Hand(h, shown, width, height * 0.22f, gap, phone)
        }
        val stacked = phone || width < 900.dp
        val (leftHand, rightHand) = s.myPair(p)
        val pairs = listOf(true to leftHand, false to rightHand)
        if (stacked) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                pairs.forEach { (left, hand) ->
                    key(left) { Choice(h, left, hand, width - 24.dp, room / 2 - 48.dp, gap, phone, Modifier.fillMaxWidth().weight(1f)) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap * 2)) {
                pairs.forEach { (left, hand) ->
                    key(left) { Choice(h, left, hand, width / 2 - gap * 2 - 24.dp, room - 64.dp, gap / 2, phone, Modifier.weight(1f).fillMaxSize()) }
                }
            }
        }
    }
}

@Composable
private fun Choice(h: NeueHolders, left: Boolean, hand: TrialDraws.Ordered, width: Dp, height: Dp, gap: Dp, phone: Boolean, modifier: Modifier) {
    val s = h.shootout
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Column(
        modifier
            .border(if (hovered) 2.dp else 1.dp, if (hovered) c.ink else c.ink25)
            .hoverable(source)
            .cursorPointer(caption = "Open with this", showsWords = true)
            .muClickable(enabled = !s.thinking, interactionSource = source) { s.prefer(left) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (left) "This hand" else "Or this hand", Modifier.weight(1f), color = c.ink70)
            KeyCap(if (left) "←" else "→")
        }
        Hand(h, TrialDraws.Shown(hand.opening, emptyList(), hand.draw, false), width, height, gap, phone)
    }
}

/** One row of a hand: the cards that are rated, then — apart — cards drawn by effects; [labelled] says the drawn ones' words go over them. */
private class HandRow(val rated: List<Pair<Int, String?>>, val drawn: List<Pair<Int, String?>>, val labelled: Boolean)

/**
 * The micro words over cards drawn by effects. Off the top of the second player's deck the first is their marked sixth,
 * which is rated, and the turn's draw follows the drawn ones, so those words say what the cards are instead (1.1.7).
 */
private const val DRAWN_WORDS = "Drawn by effects · not rated"
private const val DRAWN_SHORT = "Drawn · not rated"
private const val TOP_WORDS = "Off the top · drawn by effects, then the turn's draw"
private const val TOP_SHORT = "Off the top"

/** The drawn cards' label's height, and the hairline's room either side. */
private val DRAWN_LABEL = 18.dp

/**
 * How a hand's cards stand in rows. On the desk, one row: the rated cards, a hairline, the drawn ones. On a phone, up to
 * three in a row, else rows of up to four — and with draws by effects, rows of four, the drawn cards after the rated ones
 * in the last row, apart, so a hand of six with two draws is still two rows, never three of thumbnails (1.1.5).
 */
private fun handRows(rated: List<Pair<Int, String?>>, drawn: List<Pair<Int, String?>>, phone: Boolean): List<HandRow> {
    if (!phone) return listOf(HandRow(rated, drawn, drawn.isNotEmpty()))
    val total = rated.size + drawn.size
    val across = if (drawn.isEmpty()) phoneAcross(total) else 4
    val rows = mutableListOf<HandRow>()
    val ratedRows = rated.chunked(across)
    var left = drawn
    ratedRows.forEachIndexed { i, r ->
        if (i == ratedRows.lastIndex && left.isNotEmpty()) {
            val room = (across - r.size).coerceAtLeast(0)
            val fits = left.take(room)
            rows += HandRow(r, fits, fits.isNotEmpty())
            left = left.drop(fits.size)
        } else {
            rows += HandRow(r, emptyList(), false)
        }
    }
    left.chunked(across).forEachIndexed { i, d -> rows += HandRow(emptyList(), d, i == 0 && rows.none { it.labelled }) }
    return rows
}

/** Cards to a row on a phone without draws: up to three in one row, else two rows of up to four. */
private fun phoneAcross(n: Int): Int = if (n <= 3) n.coerceAtLeast(1) else minOf(4, (n + 1) / 2)

/** The hairline between the rated cards and the drawn ones, with its room either side. */
private fun separator(gap: Dp): Dp = gap * 2 + 1.dp

/**
 * A hand as card art in [handRows], each card as large as [width] × [height] allows; the turn's draw wears "Draw", cards
 * drawn by effects "+1", "+2"… after a hairline, under "Drawn by effects · not rated" (design review, 1.1.6). Once an
 * effect has taken the top card, the turn's draw stands after the drawn ones, in the order the cards came off the deck,
 * under "Off the top" (1.1.7).
 */
@Composable
internal fun Hand(h: NeueHolders, hand: TrialDraws.Shown, width: Dp, height: Dp, gap: Dp, phone: Boolean) {
    val c = Mu.colors
    val turn = hand.draw?.let { it to "Draw" }
    val rated = hand.opening.map { it to null } + listOfNotNull(turn.takeUnless { hand.shifted })
    val extra = hand.drawn.mapIndexed { i, id -> id to "+${i + 1}" } + listOfNotNull(turn.takeIf { hand.shifted })
    val rows = handRows(rated, extra, phone)
    val across = rows.maxOf { it.rated.size + it.drawn.size }.coerceAtLeast(1)
    val sep = if (rows.any { it.rated.isNotEmpty() && it.drawn.isNotEmpty() }) separator(gap) else 0.dp
    val labels = if (rows.any { it.labelled }) DRAWN_LABEL else 0.dp
    val byWidth = (width - sep - gap * (across - 1)) / across
    val byHeight = ((height - labels - gap * (rows.size - 1)) / rows.size) * CARD_RATIO
    val cardW = min(byWidth, byHeight).coerceAtLeast(24.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { r, row ->
            key(r) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
                    row.rated.forEachIndexed { i, (id, mark) -> key("r", i, id) { HandCard(h, id, cardW, mark) } }
                    if (row.rated.isNotEmpty() && row.drawn.isNotEmpty()) {
                        VRule(Modifier.height(cardW / CARD_RATIO), color = c.ink45)
                    }
                    if (row.drawn.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (row.labelled) {
                                val words = if (hand.shifted) (if (phone) TOP_SHORT else TOP_WORDS) else if (phone) DRAWN_SHORT else DRAWN_WORDS
                                Micro(words, Modifier.height(DRAWN_LABEL - 2.dp), color = c.ink45)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.drawn.forEachIndexed { i, (id, mark) -> key("d", i, id) { HandCard(h, id, cardW, mark) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One card, with its mark when it was drawn rather than dealt: ink on paper, the page's own tag. The card's limit mark
 * is left off here (design review, 1.1.6): the deck's legality is not the question, and Forbidden's "0" in the same
 * corner read as one more draw tag.
 */
@Composable
internal fun HandCard(h: NeueHolders, id: Int, width: Dp, mark: String? = null) {
    val s = h.shootout
    val c = Mu.colors
    val card = s.card(id)
    val size = Modifier.size(width, width / CARD_RATIO)
    Box {
        if (card == null) {
            Box(size.border(1.dp, c.ink25), contentAlignment = Alignment.Center) { Mono(id.toString()) }
        } else {
            val unmarked = remember(card) { card.copy(tcgBanStatus = BanStatus.UNLIMITED, ocgBanStatus = BanStatus.UNLIMITED) }
            NeueCard(
                unmarked,
                size.reads(s, card),
                format = h.builder.format,
                foil = h.neue.prefs.foil,
            )
        }
        if (mark != null) {
            // A small card wears a short tag, or the tag outgrows the card: D for the turn's draw.
            val small = width < 72.dp
            Box(Modifier.align(Alignment.TopStart).background(c.ink).padding(horizontal = if (small) 3.dp else 6.dp, vertical = if (small) 1.dp else 2.dp)) {
                Micro(if (small && mark == "Draw") "D" else mark, color = c.paper)
            }
        }
    }
}

/**
 * A card read without leaving the trial: hovered with a mouse, held with a finger (`ShootoutMouse`, `ShootoutTouch`).
 * The hold spends nothing: a hand being chosen still hears a tap.
 */
private fun Modifier.reads(s: Shootouts, card: Card): Modifier = this
    .cursor(CursorMode.DEFAULT, caption = "Read", emphasis = true)
    .onPointer(PointerEventType.Enter) { e -> if (e.changes.none { it.byFinger }) s.reading = card }
    .onPointer(PointerEventType.Exit) { e -> if (e.changes.none { it.byFinger } && s.reading == card) s.reading = null }
    .pointerInput(card) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.byFinger) {
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) }
                if (up == null) s.reading = card
            }
        }
    }

/**
 * A phone's swipe on a rating (`ShootoutWords.swipe`): right a win, left a loss, long the clear one, up a coin flip.
 * Only a finger swipes; while it moves, the answer it would give is named over the hands.
 */
internal fun Modifier.swipeToAnswer(onHint: (Answer?) -> Unit, onAnswer: (Answer) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.byFinger) {
            var travel = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                travel = change.position - down.position
                onHint(ShootoutWords.swipe(travel.x / size.width, travel.y / size.height))
                if (!change.pressed) break
            }
            onHint(null)
            ShootoutWords.swipe(travel.x / size.width, travel.y / size.height)?.let(onAnswer)
        }
    }
}

@Composable
internal fun ReadingStrip(card: Card?) {
    val c = Mu.colors
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        if (card != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Micro(card.name, color = c.ink)
                Small(card.description.replace('\n', ' '), maxLines = 2)
            }
        } else {
            Help(if (LocalPhone.current) "Hold a card to read it. Swipe right for a win, left for a loss, up for a coin flip." else "Hover a card to read it.")
        }
    }
}

/**
 * The five answers, best to worst, each a word and its band in tens ("Clear win · 8+ in 10"); on the desk each under its
 * key. In supervised mode Ai's answer is marked on its own box, [aiSaid], with its name inset (design review, 1.1.6).
 */
@Composable
internal fun AnswerScale(thinking: Boolean, onAnswer: (Answer) -> Unit, alone: Boolean, phone: Boolean, aiSaid: Answer? = null, aiName: String = "") {
    Row(Modifier.fillMaxWidth().height(if (phone) 64.dp else 72.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 4.dp else 8.dp)) {
        ShootoutWords.SCALE.forEach { a ->
            key(a) { AnswerBox(thinking, onAnswer, a, alone, phone, if (a == aiSaid) aiName else null, Modifier.weight(1f).fillMaxSize()) }
        }
    }
}

@Composable
private fun AnswerBox(thinking: Boolean, onAnswer: (Answer) -> Unit, a: Answer, alone: Boolean, phone: Boolean, ai: String?, modifier: Modifier) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    val label = ShootoutWords.label(a, alone)
    Inverted(hovered) {
        val inner = Mu.colors
        Box(
            modifier
                .background(animatedColor(if (hovered) inner.paper else c.paper))
                .border(if (ai != null) 2.dp else 1.dp, c.ink)
                .hoverable(source)
                .cursorPointer(caption = label, showsWords = true, enabled = !thinking)
                .muClickable(enabled = !thinking, interactionSource = source) { onAnswer(a) },
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (phone) 6.dp else 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A phone has no keys: the band alone, which fits its width.
                    if (!phone) Mono(ShootoutWords.keyOf(a).toString(), color = inner.ink)
                    // A phone's box has room for the band or Ai's tag, not both side by side.
                    if (phone && ai != null) AiTag(ai) else Mono(ShootoutWords.band(a), color = inner.ink45)
                }
                Micro(label, color = inner.ink, maxLines = if (phone) 2 else 1)
            }
            if (ai != null && !phone) {
                AiTag(ai, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** Ai's name inset on the box it chose: ink, the page's own tag. */
@Composable
private fun AiTag(name: String, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Box(modifier.background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) { Micro(name, color = c.paper) }
}

/** The page's keys (`DeskScope.SHOOTOUT`), and the same actions from the palette and the menus. */
internal fun runShootout(h: NeueHolders, action: DeskAction) {
    val s = h.shootout
    if (s.view == Shootouts.View.VERSUS && h.neue.page == Page.SHOOTOUT && runVersus(h, action)) return
    if (s.behind != null && action != DeskAction.SHOOTOUT_RESULTS) return
    // The trust panel and the rubric stand over the trial: nothing under them answers (stage 3).
    if ((s.teach.trustOpen || s.teach.rubricOpen) && action != DeskAction.SHOOTOUT_TRUST) return
    when (action) {
        DeskAction.SHOOTOUT_ACCEPT -> if (s.running && h.ai.enabled) s.teach.accept()
        DeskAction.SHOOTOUT_DRAW_MINE -> if (s.running) s.drawMine()
        DeskAction.SHOOTOUT_DRAW_THEIRS -> if (s.running) s.drawTheirs()
        DeskAction.SHOOTOUT_TRUST -> if (h.ai.enabled) {
            h.neue.go(Page.SHOOTOUT)
            if (s.teach.trustOpen) s.teach.trustOpen = false else s.teach.openTrust()
        }
        DeskAction.GO_SHOOTOUT -> h.neue.go(Page.SHOOTOUT)
        DeskAction.SHOOTOUT_ANSWER_1, DeskAction.SHOOTOUT_ANSWER_2, DeskAction.SHOOTOUT_ANSWER_3,
        DeskAction.SHOOTOUT_ANSWER_4, DeskAction.SHOOTOUT_ANSWER_5,
        -> if (s.running) ShootoutWords.byKey(action.ordinal - DeskAction.SHOOTOUT_ANSWER_1.ordinal + 1)?.let(s::answer)
        DeskAction.SHOOTOUT_LEFT -> if (s.running) s.prefer(true)
        DeskAction.SHOOTOUT_RIGHT -> if (s.running) s.prefer(false)
        // Enter is always a session of the person's own (kai's choice, 1.1.8): a key never starts spending Ai's requests.
        DeskAction.SHOOTOUT_START -> {
            h.neue.go(Page.SHOOTOUT)
            if (!s.running) s.teach.begin(ShootoutTeach.Mode.JUDGE)
        }
        DeskAction.SHOOTOUT_STOP -> s.stop()
        DeskAction.SHOOTOUT_RESULTS -> {
            h.neue.go(Page.SHOOTOUT)
            s.behind = null
            // Nothing judged, nothing to show (design review, 1.1.6): the key does what the disabled button would.
            if (s.view == Shootouts.View.RESULTS || s.handsJudged > 0 || s.running) s.toggleResults()
        }
        else -> Unit
    }
}

/** Esc on Shootout: the trials list closes, then the card being read, then the session stops (every answer kept). */
internal fun dismissShootout(h: NeueHolders): Boolean {
    if (h.neue.page != Page.SHOOTOUT || h.neue.hasTop || h.overlays.isOpen || h.textFocus.any) return false
    if (!h.shootoutStarted) return false
    val s = h.shootout
    if (s.view == Shootouts.View.VERSUS) return dismissVersus(h)
    when {
        s.behind != null -> s.behind = null
        s.teach.trustOpen -> s.teach.trustOpen = false
        s.teach.rubricOpen -> s.teach.rubricOpen = false
        s.teach.ask != null -> s.teach.skipAsk()
        s.reading != null -> s.reading = null
        s.running -> s.stop()
        s.view == Shootouts.View.RESULTS || s.view == Shootouts.View.EXAM -> s.view = Shootouts.View.SETUP
        else -> return false
    }
    return true
}
