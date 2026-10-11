package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.proposals.ProposalScore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.kaiharimoto.mastertool.core.duel.mapper.train.Elo
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderPlayers
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderQuestion
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderRating
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderRoster
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderSeeds
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderVerdict
import com.kaiharimoto.neue.duel.Ladders
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.eval.EvalRun
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.TrustWords
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Test scores (1.0.99, Phase A, named Trust until kai's pick in the design review, finding 8 — the code keeps that name;
 * Shootout's Trust is another thing): how each connection scores on questions with known answers. It opens on a table — a
 * row per set, the connection's last score with its verdict against fixed bars (`TrustWords.verdict`: Rely on it, Check
 * it, Do it yourself; finding 7), when, and Run — and a row opens out to what the score means, the bars, what it cost in
 * tokens and at list prices (`Prices`), and the items it missed, each led by its question (findings 7, 12–15). For the
 * fact-checker, how many planted mistakes it caught and how often it cried wolf; for the puzzles, a scale from doing
 * nothing to solved. A run is started here and asks first what it will spend, in tokens and in money (finding 4). Ink
 * only, as every other score in the app.
 */
@Composable
fun TrustDialog(ai: AiState) {
    if (!ai.trustOpen) return
    val phone = LocalPhone.current
    val connections = ai.prefs.connections
    var chosen by remember { mutableStateOf(ai.prefs.connection ?: connections.firstOrNull()) }
    var tries by remember { mutableStateOf(1) }
    val running = ai.evalProgress
    MuDialog(
        title = "How ${ai.name} scores on questions with known answers",
        onDismiss = { ai.trustOpen = false; ai.trustAsking = null },
        width = 760.dp,
        // The title says what is asked; the description, how it is graded and whose score it is.
        description = "Graded by the app, never by a model. A score is a connection's: the same questions, asked of " +
            "another model, give another score.",
        footer = {
            MuButton("Close", { ai.trustOpen = false; ai.trustAsking = null }, variant = BtnVariant.SECONDARY)
        },
    ) {
        val c = Mu.colors
        val connection = chosen
        if (connections.isEmpty() || connection == null) {
            Help("Set up a connection first: test scores measure one.")
        } else {
            Help(
                "Right first time: the share it got right on its first try. A score is one connection's; each run is a fresh " +
                    "sample, so a few points either way is noise.",
            )
            // The connection whole on a phone — the model is what is being scored — and Tries under it.
            val pick: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    FieldLabel("Connection")
                    MuSelect(connection, connections, { label(it) }, { chosen = it }, Modifier.fillMaxWidth(), small = true)
                }
            }
            val times: @Composable () -> Unit = {
                // As wide as its control: a field label fills what it is given, and took the connection's room.
                Column(Modifier.width(IntrinsicSize.Max)) {
                    FieldLabel("Tries")
                    Segmented(tries, listOf(1, 3), { if (it == 1) "Once" else "3 times" }, { tries = it }, small = true)
                }
            }
            if (phone) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pick(Modifier.fillMaxWidth())
                    times()
                }
            } else {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pick(Modifier.weight(1f))
                    times()
                }
            }
            ai.evalNote?.let { Small(it, Modifier.padding(top = 8.dp), color = c.ink) }
            // Read afresh when a run is kept.
            val runs = remember(connection.id, ai.evalVersion) { ai.evalRuns(connection.id) }
            val latest = runs.groupBy { it.set }.mapValues { (_, r) -> r.maxBy { it.at } }
            Column(Modifier.padding(top = 20.dp)) {
                HRule(strong = true)
                EvalSets.all.forEachIndexed { n, set ->
                    SetRow(
                        ai,
                        connection,
                        n + 1,
                        set,
                        latest[set.id],
                        running = running?.takeIf { it.first == set.id }?.let { it.second to it.third },
                        busy = running != null,
                        confirming = ai.trustAsking == set.id,
                        tries = tries,
                        open = set.id in ai.trustExpanded,
                        onOpen = { ai.trustExpanded = if (set.id in ai.trustExpanded) ai.trustExpanded - set.id else ai.trustExpanded + set.id },
                        onRun = { ai.trustAsking = set.id },
                        onConfirm = {
                            ai.trustAsking = null
                            ai.startEval(set, connection, tries)
                        },
                        onCancel = { ai.trustAsking = null },
                        onStop = { ai.stopEval() },
                    )
                    HRule()
                }
            }
        }
        // The app's own search needs no connection: its ladder stands whether one is set up or not.
        LadderSection(ai.h.ladder, EvalSets.all.size + 1)
        // Ai's suggestions held to the person's games since (G2.2's proposals, G2.3's score): needs no connection either.
        SuggestionsSection(ai, EvalSets.all.size + 2)
        // What helps Ai judge Shootout hands (S4.4): the ablation, on the deck and target Shootout is on.
        ShootoutAblationSection(ai, EvalSets.all.size + 3)
    }
}

/**
 * Ai's suggestions, against your games (Phase G part two, G2.3): every applied proposal that said what a win rate would do,
 * with a range, scored against the games played since — how often the gain seen lay in its range, and the Brier score of "it
 * helps" against a coin's 0.25 — each one's line, and those still waiting for games.
 */
@Composable
private fun SuggestionsSection(ai: AiState, n: Int) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val score by produceState<ProposalScore.Score?>(null, ai.proposalsRevision) { value = runCatching { ai.h.proposalScore() }.getOrNull() }
    Column(Modifier.padding(top = 24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HRule(strong = true)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numeral(n)
            MuText("${ai.name}'s suggestions, against your games", Modifier.weight(1f), style = MuType.h2(f), color = c.ink, maxLines = 2)
        }
        val s = score
        if (s == null) {
            Small("Reading the proposals…", color = c.ink45)
        } else {
            val lines = ProposalScore.words(s).lines()
            Small(lines.first(), color = c.ink)
            lines.drop(1).forEach { Small(it.removePrefix("- "), color = c.ink70) }
            Help("A suggestion is scored once its deck has ${ProposalScore.MIN_GAMES} games on each side of the change, at the versions it came from and made.")
        }
    }
}

private fun label(c: AiConnection?): String =
    (c?.label ?: "").ifBlank { c?.provider.orEmpty() } + (c?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

/** One set's row: its number, title, score and Run, when it last ran, and — opened out — what the score means and what it missed. */
@Composable
private fun SetRow(
    ai: AiState,
    connection: AiConnection,
    n: Int,
    set: EvalSet,
    last: EvalRun?,
    running: Pair<Int, Int>?,
    busy: Boolean,
    confirming: Boolean,
    tries: Int,
    open: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val phone = LocalPhone.current
    // The connection's model at its provider's list prices; null when the table does not hold it (`Prices`).
    val price = Prices.of(connection.provider, connection.model)
    val missed = last?.items?.count { !it.firstPass } ?: 0
    val verdict = last?.let { TrustWords.verdict(set, it, greedy = if (set.id == EvalSets.PUZZLES) PUZZLE_BOUNDS.second else null) }
    // The verdict in micro caps beside the score; "Do it yourself", the one that changes what the person does, inverted.
    val word: @Composable () -> Unit = { verdict?.let { Badge(it.words, inverted = it == TrustWords.Verdict.YOURSELF) } }
    val run: @Composable () -> Unit = {
        if (running == null && !confirming) {
            MuButton(
                if (last == null) "Run" else "Run again", onRun,
                variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !busy, reason = "Another set is running",
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numeral(n)
            MuText(set.title, Modifier.weight(1f), style = MuType.h2(f), color = c.ink, maxLines = 2)
            if (last == null) Small("Not run", color = c.ink45)
            else MuText(headline(set, last), style = MuType.mono(f, 20.sp), color = c.ink, maxLines = 1)
            if (!phone) {
                word()
                run()
            }
        }
        // The fact-checker is the one set whose name says nothing to a newcomer: said under its title.
        if (set.checker) Help("The pass that checks ${ai.name}'s answers after it replies (Settings › Ai › Fact-check).", Modifier.padding(start = 30.dp))
        // On a phone when and on which model is a line of its own, whole, and the verdict leads the line under it with
        // the details and Run — so the title and the model keep their room at 360 dp.
        if (phone && last != null) MuText(ranWhen(last), Modifier.padding(start = 30.dp), style = MuType.mono(f), color = c.ink45)
        Row(Modifier.padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phone) {
                word()
                Spacer(Modifier.weight(1f))
            } else {
                MuText(last?.let { ranWhen(it) }.orEmpty(), Modifier.weight(1f), style = MuType.mono(f), color = c.ink45)
            }
            MicroLink(
                when {
                    open -> "Less ▴"
                    missed > 0 -> "$missed missed ▸"
                    last != null -> "Details ▸"
                    else -> "About ▸"
                },
                onOpen,
            )
            if (phone) run()
        }
        when {
            running != null -> Row(Modifier.padding(start = 30.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Breathe()
                Small("Asking ${running.first} of ${running.second}…", Modifier.weight(1f), color = c.ink)
                MuButton("Stop", onStop, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            confirming -> {
                // What it will spend, in tokens and at list prices — or where to look when the model has none (finding 4).
                val q = set.items.size * tries
                val spend = q.toLong() * tokensEach(set)
                val words: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        MuText("$q question${if (q == 1) "" else "s"} · ≈ ${tokens(spend)} tokens on this connection", style = MuType.mono(f), color = c.ink)
                        MuText(Prices.words(price?.let { Prices.estimate(it, spend) }), style = MuType.mono(f), color = c.ink70)
                    }
                }
                val buttons: @Composable () -> Unit = {
                    MuButton("Run now", onConfirm, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                    MuButton("Cancel", onCancel, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
                if (phone) {
                    Column(Modifier.padding(start = 30.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        words(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
                    }
                } else {
                    Row(Modifier.padding(start = 30.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        words(Modifier.weight(1f))
                        buttons()
                    }
                }
            }
        }
        if (open) Details(ai, set, last, connection.provider)
    }
}

/** A set opened out: what it asks, what the score means, the puzzles' scale, what it cost, and what it missed. */
@Composable
private fun Details(ai: AiState, set: EvalSet, last: EvalRun?, provider: String) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Column(Modifier.padding(start = 30.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Small(set.about, color = c.ink70)
        if (last != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (set.checker) {
                    val (_, _, alarms, clean) = TrustWords.checker(set, last)
                    MuText("$alarms", style = MuType.mono(f, 20.sp), color = c.ink)
                    Small("false alarm${if (alarms == 1) "" else "s"} in $clean clean answers: how often it cries wolf", Modifier.padding(bottom = 3.dp), color = c.ink70)
                } else {
                    MuText("${(last.passAt1 * 100).toInt()}%", style = MuType.mono(f, 20.sp), color = c.ink)
                    Small(
                        "right first time" + if (last.tries > 1) " · right all ${last.tries} times ${(last.passAll * 100).toInt()}%" else "",
                        Modifier.padding(bottom = 3.dp), color = c.ink70,
                    )
                }
            }
        }
        if (set.id == EvalSets.PUZZLES) PuzzleScale(last?.items?.count { it.firstPass })
        // What the score means for you: the set's bars in words, under its own figure.
        Help(TrustWords.barsWords(set))
        if (last != null) MuText(costWords(set, last, provider), style = MuType.mono(f), color = c.ink45)
        if (last != null) Misses(ai, set, last)
    }
}

/**
 * The puzzles' frame of reference as a scale (finding 12): a 1px ink track from 0 to every puzzle, ticks where doing
 * nothing, only attacking and the known solutions land, and the score a 6px ink square on it.
 */
@Composable
private fun PuzzleScale(score: Int?) {
    val c = Mu.colors
    val (nothing, greedy, solved) = PUZZLE_BOUNDS
    val searched by produceState(PUZZLE_SEARCHED) { if (value == null) value = puzzleSearchBound() }
    val n = PUZZLE_COUNT.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val w = maxWidth
            fun x(v: Int) = w * (v.toFloat() / n)
            listOf(nothing, greedy, solved).distinct().forEach { v ->
                val left = (x(v) - 8.dp).coerceIn(0.dp, w - 16.dp)
                Mono("$v", Modifier.offset(x = left).width(16.dp), color = c.ink45, align = TextAlign.Center)
            }
            Canvas(Modifier.fillMaxWidth().height(14.dp).offset(y = 16.dp)) {
                val y = size.height / 2
                drawLine(c.ink, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                listOf(nothing, greedy, solved).forEach { v ->
                    val tx = (size.width * v / n).coerceIn(0.5f, size.width - 0.5f)
                    drawLine(c.ink, Offset(tx, y - 4.dp.toPx()), Offset(tx, y + 4.dp.toPx()), 1.dp.toPx())
                }
                if (score != null) {
                    val s = 6.dp.toPx()
                    val sx = (size.width * score / n).coerceIn(s / 2, size.width - s / 2)
                    drawRect(c.ink, Offset(sx - s / 2, y - s / 2), Size(s, s))
                }
            }
        }
        Help(
            "For scale: a player who does nothing scores $nothing; one who only attacks scores $greedy; the known solutions score $solved." +
                (searched?.let { " The app's own search, with no model, scores $it." } ?: "") +
                if (score != null) " The square is this connection's $score." else "",
        )
    }
}

/** The score that matters most, large and in mono: right first time of all, or for the checker, mistakes caught. */
private fun headline(set: EvalSet, run: EvalRun): String {
    if (set.checker) {
        val (caught, planted) = TrustWords.checker(set, run)
        return "caught $caught of $planted"
    }
    return "${run.items.count { it.firstPass }} of ${run.items.size}"
}

/** When it last ran, and on which model: "3 Oct · claude-opus-5-5". */
private fun ranWhen(run: EvalRun): String = listOfNotNull(
    DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(run.at).atZone(ZoneId.systemDefault())),
    run.model.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * What the run cost and when, whole: never cut (finding 14) — tokens, then at list prices when the run's model has one
 * (every token read priced as new, since a run keeps reads and cached reads together: "≈").
 */
private fun costWords(set: EvalSet, run: EvalRun, provider: String): String = buildList {
    add("${tokens(run.tokensIn)} in, ${tokens(run.tokensOut)} out")
    Prices.of(provider, run.model)?.let { p -> add(Prices.words(Prices.cost(p, Usage(input = run.tokensIn, output = run.tokensOut)))) }
    add(DateTimeFormatter.ofPattern("d MMM, HH:mm").format(Instant.ofEpochMilli(run.at).atZone(ZoneId.systemDefault())))
    if (run.model.isNotBlank()) add(run.model)
    if (run.stoppedEarly) add("stopped after ${run.items.size} of ${set.items.size}")
}.joinToString(" · ")

/**
 * The first few items it got wrong, each led by its question cut at a word, then what it answered against what is right,
 * and its id last in mono (finding 13); a card named in a question is the card, as in Ai's replies.
 */
@Composable
private fun Misses(ai: AiState, set: EvalSet, run: EvalRun) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val missed = run.items.filter { !it.firstPass }
    if (missed.isEmpty()) return
    val open: (String) -> Unit = remember(ai) { { name -> cardNamed(ai, name)?.let { ai.h.neue.viewing = Viewing(it, null, 0) } } }
    CompositionLocalProvider(LocalCardLink provides open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            missed.take(5).forEach { m ->
                val miss = TrustWords.miss(set.items.firstOrNull { it.id == m.id }, m)
                val question = styled(ChatMarkdown.inline(miss.question))
                val line = buildAnnotatedString {
                    if (question.isNotEmpty()) {
                        append(question)
                        append(" — ")
                    }
                    append(miss.verdict)
                    withStyle(SpanStyle(fontFamily = MuType.mono(f).fontFamily, color = c.ink45, fontSize = 11.sp)) { append("  ·  ${miss.id}") }
                }
                MuText(line, style = MuType.small(f), color = c.ink70)
            }
            if (missed.size > 5) Help("and ${missed.size - 5} more")
        }
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}

/**
 * The ladder (Phase E, E.4; E.md §4.5): the app's own players rated on the bench, no connection and nothing spent. A row
 * per player — its Elo, and its 95 % range drawn on one axis the rows share, Greedy's 1,000 marked — the gate's two
 * verdicts in words, what is owed, and Play. The puzzles stand beside it as a second number, never mixed in.
 */
@Composable
private fun LadderSection(ladder: Ladders, n: Int) {
    LaunchedEffect(Unit) { ladder.load() }
    val c = Mu.colors
    val f = LocalMuFonts.current
    val phone = LocalPhone.current
    val r = ladder.reading
    val progress = ladder.progress
    Column(Modifier.padding(top = 24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HRule(strong = true)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numeral(n)
            MuText("The app's own search, on its ladder", Modifier.weight(1f), style = MuType.h2(f), color = c.ink, maxLines = 2)
            when {
                progress != null -> MuButton("Stop", { ladder.stop() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                r != null && r.owed > 0 -> MuButton(
                    if (r.games == 0) "Play ${r.owed} games" else "Play ${r.owed} more", { ladder.run() },
                    variant = BtnVariant.SUBTLE, size = BtnSize.SM,
                )
                // Nothing owed: tuning makes the next step from the search in use and plays it (E.5b).
                r != null -> MuButton("Tune", { ladder.tune() }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
            }
        }
        Column(Modifier.padding(start = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small(
                "No connection, nothing spent: every player plays the same ${LadderSeeds.COUNT} deals from both seats, on three " +
                    "bench decks whose every card is written. Each rating is an Elo against Greedy at 1,000 with its 95 % range; " +
                    "a game is counted effort, never time, so it comes out the same on every device.",
                color = c.ink70,
            )
            Small(
                "The search in use is v${ladder.inUse.n}, at your table and in Ai vs Ai. A new version replaces it only when it beats " +
                    "it here: Tune tries one weight at a time, up and down, each step a version the gate judges — hours on the desk, " +
                    "and stopped and taken up again as you like.",
                color = c.ink70,
            )
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Breathe()
                    Small(
                        (if (ladder.tuning) "Tuning · " else "") + "Playing ${progress.first + 1} of ${progress.second}" + (ladder.playing?.let { " · $it" } ?: ""),
                        Modifier.weight(1f), color = c.ink, maxLines = 2,
                    )
                }
            }
            ladder.problem?.let { Small(it, color = c.ink) }
            when {
                r == null -> Help("Reading the ladder…")
                r.ratings.isEmpty() -> Help("Nothing played yet. A game takes seconds on the desk, longer on a phone; a run stopped goes on where it stood.")
                else -> {
                    // Tuning makes many versions: the fixed players, the one in use and the newest few are shown, the rest counted.
                    val newest = r.roster.candidates.filter { it.n >= 2 }.takeLast(SHOWN_CANDIDATES).map { LadderPlayers.fast(it.n) }.toSet()
                    val keep = { id: String -> !Regex("search-v(\\d+)/field/fast").matches(id) || id == LadderPlayers.FAST || id == LadderPlayers.V1_FAST || id == r.roster.inUse || id in newest }
                    val shown = r.ratings.filter { keep(it.player) }
                    LadderRows(shown, r.roster, phone)
                    val hidden = r.ratings.size - shown.size
                    if (hidden > 0) {
                        val best = r.ratings.filterNot { keep(it.player) }.maxBy { it.elo }
                        Help("$hidden more versions tried; the best of them, ${r.roster.of(best.player)?.name ?: best.player}, at ${elo(best.elo)} (${elo(best.low)}–${elo(best.high)}).")
                    }
                    val verdicts = r.verdicts.filter { (q, _) -> !q.gate || keep(q.candidate) }
                    verdicts.forEach { (q, v) -> Verdict(q, v, r.roster) }
                    val more = r.verdicts.size - verdicts.size
                    if (more > 0) Help("$more more candidates judged: ${r.verdicts.count { (q, v) -> q.gate && !keep(q.candidate) && v.pass }} passed.")
                    Mono(
                        "${r.games} games counted · ${r.owed} owed · cards written ${(r.coverage * 100).toInt()} %" +
                            if (r.older > 0) " · ${r.older} kept, not counted" else "",
                        color = c.ink45,
                    )
                }
            }
            val searched by produceState(PUZZLE_SEARCHED) { if (value == null) value = puzzleSearchBound() }
            Help(
                "A second number, never in the Elo: on the ${PUZZLE_COUNT} puzzles, Greedy (only attacking) solves ${PUZZLE_BOUNDS.second}" +
                    (searched?.let { " and the search $it" } ?: "") + ".",
            )
        }
    }
}

/** Each player's row — name, Elo, range, games — over a range bar on one axis the rows share. */
@Composable
private fun LadderRows(ratings: List<LadderRating>, roster: LadderRoster, phone: Boolean) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val lo = ratings.minOf { it.low }.coerceAtMost(Elo.BASE) - 25
    val hi = ratings.maxOf { it.high }.coerceAtLeast(Elo.BASE) + 25
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ratings.forEach { r ->
            val name = roster.of(r.player)?.name ?: r.player
            val bar: @Composable (Modifier) -> Unit = { m -> LadderBar(r, lo, hi, m) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MuText(name, Modifier.weight(1f), style = MuType.body(f), color = c.ink, maxLines = 1)
                    if (!phone) bar(Modifier.width(220.dp))
                    MuText(elo(r.elo), style = MuType.mono(f, 15.sp), color = c.ink, maxLines = 1)
                }
                Mono(
                    if (r.player == LadderPlayers.GREEDY) "the anchor · ${r.games} games"
                    else "${elo(r.low)}–${elo(r.high)} · ${r.games} games · ${(r.score * 100).roundToInt()} % of the points",
                    color = c.ink45,
                )
                if (phone) bar(Modifier.fillMaxWidth())
            }
        }
    }
}

/** One rating on the shared axis [lo]–[hi]: a 1px track, its range in 2px ink, a 6px square at the rating, 1,000 ticked. */
@Composable
private fun LadderBar(r: LadderRating, lo: Double, hi: Double, modifier: Modifier) {
    val c = Mu.colors
    Canvas(modifier.height(12.dp)) {
        val y = size.height / 2
        fun x(v: Double) = (size.width * ((v - lo) / (hi - lo))).toFloat().coerceIn(0f, size.width)
        drawLine(c.ink25, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        val base = x(Elo.BASE)
        drawLine(c.ink45, Offset(base, y - 5.dp.toPx()), Offset(base, y + 5.dp.toPx()), 1.dp.toPx())
        drawLine(c.ink, Offset(x(r.low), y), Offset(x(r.high), y), 2.dp.toPx())
        val s = 6.dp.toPx()
        val sx = x(r.elo).coerceIn(s / 2, size.width - s / 2)
        drawRect(c.ink, Offset(sx - s / 2, y - s / 2), Size(s, s))
    }
}

/**
 * One of the ladder's questions answered in a line: whether the candidate is clearly ahead, the deals and test behind it,
 * and for the gate's own question what it decides.
 */
@Composable
private fun Verdict(q: LadderQuestion, v: LadderVerdict, roster: LadderRoster) {
    val c = Mu.colors
    val parent = roster.of(q.parent)?.name ?: q.parent
    val decides = when {
        !q.gate -> ""
        v.pass -> " It replaces ${parent.substringBefore(" ·")}."
        else -> " ${parent.substringBefore(" ·")} stays the search in use."
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Badge(v.words.replaceFirstChar { it.uppercase() }, inverted = !v.pass)
        Small("${q.label}: ${v.why}.$decides", Modifier.weight(1f), color = c.ink70)
    }
}

/** How many of the newest candidates the ladder shows in full. */
private const val SHOWN_CANDIDATES = 3

private fun elo(x: Double): String {
    val n = x.roundToInt()
    return if (abs(n) >= 1000) "${n / 1000},${(abs(n) % 1000).toString().padStart(3, '0')}" else "$n"
}
