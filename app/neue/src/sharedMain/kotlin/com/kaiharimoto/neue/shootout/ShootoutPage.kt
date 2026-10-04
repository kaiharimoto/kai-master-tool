package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.border
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
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
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
 * web. A session shows one trial at a time, chosen by the picker for what it teaches: your hand (and theirs) as large
 * card art, who goes first, game one or sided, and five answers under keys 1 to 5 (a click, or a swipe on a phone); a
 * comparison is two hands, ← or →. Its progress is the stop rule's line; it stops at any moment with every answer
 * kept. The results stand the strata side by side, and every number opens its trials.
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
            PageHeader(numeral = 9, title = "Shootout", subtitle = subtitle(s)) { HeaderActions(h) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.view) {
                    Shootouts.View.SETUP -> SetupView(h)
                    Shootouts.View.TRIAL -> TrialView(h, phone)
                    Shootouts.View.RESULTS -> ResultsView(h, phone)
                }
            }
        }
        s.behind?.let { TrialsDialog(h, it) }
    }
}

private fun subtitle(s: Shootouts): String {
    val target = s.bench?.opponentName?.let { "Against $it" } ?: "The deck alone"
    val kept = s.log?.trials?.size ?: 0
    return listOf(s.deckName.ifBlank { "No deck" }, target, "$kept trial${if (kept == 1) "" else "s"} kept").joinToString(" · ")
}

@Composable
private fun HeaderActions(h: NeueHolders) {
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
    MuButton(if (s.view == Shootouts.View.RESULTS) "Trials" else "Results", s::toggleResults, size = BtnSize.SM, enabled = s.bench != null, reason = s.problem)
    if (s.running) {
        MuButton("Stop", s::stop, size = BtnSize.SM)
    } else {
        MuButton("Begin", s::start, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = s.bench != null && !s.thinking, reason = s.problem)
    }
}

// ---- before a session -------------------------------------------------------------------------------------------

@Composable
private fun SetupView(h: NeueHolders) {
    val s = h.shootout
    val c = Mu.colors
    val bench = s.bench
    val problem = s.problem
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
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (LocalPhone.current) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            H2(bench.opponentName?.let { "Against $it" } ?: "The deck alone")
            Body(
                if (bench.alone) {
                    "You are shown an opening hand and who goes first, and say how often a hand like it does what the deck wants: from plays through to bricks. The picker chooses each hand for what it would teach, so the ratings settle in far fewer hands than a shuffle would take."
                } else {
                    "You are shown your hand and theirs, who goes first and which game, and say how the game goes: from a clear win to a clear loss. Each card is rated per turn and per game, game one and after siding, pooled so a few sided hands borrow from many game-one ones."
                },
                color = c.ink70,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("Which hands", color = c.ink45)
            val options = listOf<Stratum?>(null) + bench.strata
            Segmented(s.pinned, options, { it?.let(ShootoutWords::stratum) ?: "Let the picker choose" }, { s.pinned = it }, small = true)
        }
        if (bench.waiting.isNotEmpty()) {
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Waiting", color = c.ink45)
                bench.waiting.forEach { (stratum, why) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono(ShootoutWords.stratum(stratum), color = c.ink)
                        Small(why, Modifier.weight(1f, fill = false))
                    }
                }
                val deck = s.deckId ?: h.builder.deckId
                if (deck != null) MicroLink("Write the plans on Siding", { h.webs.side(deck, s.opponentId) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("Begin a session", s::start, variant = BtnVariant.PRIMARY, arrow = true, enabled = !s.thinking)
            Kbd(DeskShortcuts.chordFor(DeskAction.SHOOTOUT_START)?.let(DeskShortcuts::kbd) ?: "Enter")
            if ((s.log?.trials?.size ?: 0) > 0) MuButton("Results", s::showResults, variant = BtnVariant.GHOST)
        }
        Help("About ten minutes is a session. Stop whenever you like: every answer is kept, and the ratings carry over to the next one. The cards are rated per copy, against the card the deck would have dealt instead.")
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
            Small("Choosing a hand")
        }
        return
    }
    var hint by remember { mutableStateOf<Answer?>(null) }
    val pad = if (phone) 12.dp else 32.dp
    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = if (phone) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 14.dp)) {
        // The situation, and the session's progress.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            H2(ShootoutWords.situation(p.stratum, bench.opponentName), Modifier.weight(1f), maxLines = 2)
            if (!phone) Mono(progressWords(s), color = c.ink45)
        }
        if (phone) Small(progressWords(s), color = c.ink45, maxLines = 2)
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
        ReadingStrip(s.reading)
        when (p) {
            is Proposal.Rate -> AnswerScale(s, bench.alone, phone)
            is Proposal.Compare -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Help("Which would you rather open with?", Modifier.weight(1f))
                MuButton("← This one", { s.prefer(true) }, enabled = !s.thinking)
                MuButton("This one →", { s.prefer(false) }, enabled = !s.thinking)
            }
        }
    }
}

private fun progressWords(s: Shootouts): String {
    val parts = mutableListOf<String>()
    s.settled?.let { parts += "${it.known} of ${it.of} cards known within ±${it.halfWidth.toInt()} points" + if (it.enough) " · enough to stop" else "" }
    parts += "${s.sessionAnswers} this session"
    if (s.sessionMs >= 60_000) parts += "${s.sessionMs / 60_000} min"
    return parts.joinToString(" · ")
}

/** A rating's hands: theirs above (smaller), yours below, each as large as the room allows. */
@Composable
private fun RateHands(h: NeueHolders, p: Proposal.Rate, width: Dp, height: Dp, phone: Boolean) {
    val s = h.shootout
    val bench = s.bench ?: return
    val mine = bench.ids(p.hand)
    val theirs = p.opponent?.let(bench::opponentIds)
    val gap = if (phone) 6.dp else 12.dp
    val label = 22.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (theirs != null) {
            val rowH = (height - label * 2 - gap * 3) * 0.38f
            Micro("Their hand · ${theirs.size} cards", color = Mu.colors.ink45)
            Hand(h, theirs, cardWidth(width, rowH, theirs.size, gap, phone), gap, phone)
            Micro("Your hand · ${mine.size} cards", color = Mu.colors.ink45)
            Hand(h, mine, cardWidth(width, (height - label * 2 - gap * 3) * 0.62f, mine.size, gap, phone), gap, phone)
        } else {
            Micro("Your hand · ${mine.size} cards", color = Mu.colors.ink45)
            Hand(h, mine, cardWidth(width, height - label - gap, mine.size, gap, phone), gap, phone)
        }
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
            Micro("Their hand · ${theirs.size} cards", color = Mu.colors.ink45)
            Hand(h, theirs, cardWidth(width, height * 0.22f, theirs.size, gap, phone), gap, phone)
        }
        val stacked = phone || width < 900.dp
        val pairs = listOf(true to bench.ids(p.left), false to bench.ids(p.right))
        if (stacked) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                pairs.forEach { (left, ids) ->
                    key(left) { Choice(h, left, ids, cardWidth(width - 24.dp, room / 2 - 48.dp, ids.size, gap, phone), gap, phone, Modifier.fillMaxWidth().weight(1f)) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap * 2)) {
                pairs.forEach { (left, ids) ->
                    key(left) { Choice(h, left, ids, cardWidth(width / 2 - gap * 2 - 24.dp, room - 64.dp, ids.size, gap / 2, phone), gap / 2, phone, Modifier.weight(1f).fillMaxSize()) }
                }
            }
        }
    }
}

@Composable
private fun Choice(h: NeueHolders, left: Boolean, ids: List<Int>, cardW: Dp, gap: Dp, phone: Boolean, modifier: Modifier) {
    val s = h.shootout
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Column(
        modifier
            .border(if (hovered) 2.dp else 1.dp, if (hovered) c.ink else c.ink25)
            .hoverable(source)
            .cursorPointer(caption = if (left) "Open with this" else "Open with this", showsWords = true)
            .muClickable(enabled = !s.thinking, interactionSource = source) { s.prefer(left) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (left) "This hand" else "Or this hand", Modifier.weight(1f), color = c.ink70)
            Kbd(if (left) "←" else "→")
        }
        Hand(h, ids, cardW, gap, phone)
    }
}

/** How wide a card can be: [n] across [width] with [gap]s, and no taller than [height]; on a phone, three to a row. */
private fun cardWidth(width: Dp, height: Dp, n: Int, gap: Dp, phone: Boolean): Dp {
    val across = if (phone && n > 3) 3 else n.coerceAtLeast(1)
    val rows = (n + across - 1) / across
    val byWidth = (width - gap * (across - 1)) / across
    val byHeight = ((height - gap * (rows - 1)) / rows) * CARD_RATIO
    return min(min(byWidth, byHeight), 260.dp).coerceAtLeast(24.dp)
}

/** A hand as card art, in rows of three on a phone. */
@Composable
private fun Hand(h: NeueHolders, ids: List<Int>, cardW: Dp, gap: Dp, phone: Boolean) {
    val rows = if (phone && ids.size > 3) ids.chunked(3) else listOf(ids)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { r, row ->
            key(r) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { i, id -> key(i, id) { HandCard(h, id, cardW) } }
                }
            }
        }
    }
}

@Composable
private fun HandCard(h: NeueHolders, id: Int, width: Dp) {
    val s = h.shootout
    val card = s.card(id)
    val size = Modifier.size(width, width / CARD_RATIO)
    if (card == null) {
        Box(size.border(1.dp, Mu.colors.ink25), contentAlignment = Alignment.Center) { Mono(id.toString()) }
    } else {
        NeueCard(
            card,
            size.reads(s, card),
            format = h.builder.format,
            foil = h.neue.prefs.foil,
        )
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
private fun Modifier.swipeToAnswer(onHint: (Answer?) -> Unit, onAnswer: (Answer) -> Unit): Modifier = pointerInput(Unit) {
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
private fun ReadingStrip(card: Card?) {
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

/** The five answers, best to worst, each under its key. */
@Composable
private fun AnswerScale(s: Shootouts, alone: Boolean, phone: Boolean) {
    Row(Modifier.fillMaxWidth().height(if (phone) 64.dp else 72.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 4.dp else 8.dp)) {
        ShootoutWords.SCALE.forEach { a ->
            key(a) { AnswerBox(s, a, alone, phone, Modifier.weight(1f).fillMaxSize()) }
        }
    }
}

@Composable
private fun AnswerBox(s: Shootouts, a: Answer, alone: Boolean, phone: Boolean, modifier: Modifier) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    val label = ShootoutWords.label(a, alone)
    Inverted(hovered) {
        val inner = Mu.colors
        Column(
            modifier
                .background(animatedColor(if (hovered) inner.paper else c.paper))
                .border(1.dp, c.ink)
                .hoverable(source)
                .cursorPointer(caption = label, showsWords = true, enabled = !s.thinking)
                .muClickable(enabled = !s.thinking, interactionSource = source) { s.answer(a) }
                .padding(horizontal = if (phone) 6.dp else 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(ShootoutWords.keyOf(a).toString(), color = inner.ink)
                if (!phone) Mono(ShootoutWords.band(a), color = inner.ink45)
            }
            Micro(label, color = inner.ink, maxLines = if (phone) 2 else 1)
        }
    }
}

/** The page's keys (`DeskScope.SHOOTOUT`), and the same actions from the palette and the menus. */
internal fun runShootout(h: NeueHolders, action: DeskAction) {
    val s = h.shootout
    if (s.behind != null && action != DeskAction.SHOOTOUT_RESULTS) return
    when (action) {
        DeskAction.GO_SHOOTOUT -> h.neue.go(Page.SHOOTOUT)
        DeskAction.SHOOTOUT_ANSWER_1, DeskAction.SHOOTOUT_ANSWER_2, DeskAction.SHOOTOUT_ANSWER_3,
        DeskAction.SHOOTOUT_ANSWER_4, DeskAction.SHOOTOUT_ANSWER_5,
        -> if (s.running) ShootoutWords.byKey(action.ordinal - DeskAction.SHOOTOUT_ANSWER_1.ordinal + 1)?.let(s::answer)
        DeskAction.SHOOTOUT_LEFT -> if (s.running) s.prefer(true)
        DeskAction.SHOOTOUT_RIGHT -> if (s.running) s.prefer(false)
        DeskAction.SHOOTOUT_START -> {
            h.neue.go(Page.SHOOTOUT)
            s.start()
        }
        DeskAction.SHOOTOUT_STOP -> s.stop()
        DeskAction.SHOOTOUT_RESULTS -> {
            h.neue.go(Page.SHOOTOUT)
            s.behind = null
            s.toggleResults()
        }
        else -> Unit
    }
}

/** Esc on Shootout: the trials list closes, then the card being read, then the session stops (every answer kept). */
internal fun dismissShootout(h: NeueHolders): Boolean {
    if (h.neue.page != Page.SHOOTOUT || h.neue.hasTop || h.overlays.isOpen || h.textFocus.any) return false
    if (!h.shootoutStarted) return false
    val s = h.shootout
    when {
        s.behind != null -> s.behind = null
        s.reading != null -> s.reading = null
        s.running -> s.stop()
        s.view == Shootouts.View.RESULTS -> s.view = Shootouts.View.SETUP
        else -> return false
    }
    return true
}
