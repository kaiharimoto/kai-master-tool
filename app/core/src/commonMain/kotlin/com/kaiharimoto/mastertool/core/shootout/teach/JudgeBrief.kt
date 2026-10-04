package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlin.math.round

/**
 * The model's own prediction for a hand (S.md §6½ "The model's own prediction"): its win chance and how sure the fit is
 * ([sd], in log-odds), the five answers' chances on the person's scale (worst to best) and the likeliest; for a
 * comparison, the chance the left hand is preferred and which.
 */
class Prediction(
    val win: Double,
    val sd: Double,
    val likeliest: Answer?,
    val chances: List<Double>,
    val prefersLeft: Boolean?,
)

/**
 * What Ai is handed to judge one hand (S.md §6½): the situation and the hands by name, the model's prediction, the
 * rubric, and the person's judged hands most like this one with their notes — never the person's answer to this hand.
 * [text] is the message; the rest is the record kept with Ai's answer ([verdict]) so its agreement can be measured on
 * what it never learned from.
 */
class JudgeBrief(
    val text: String,
    val examples: List<String>,
    val rubric: String?,
    val predicted: Double?,
    val kind: HandKind,
    val print: String,
    val asked: Long,
    val alone: Boolean,
    val compare: Boolean,
) {
    /** Ai's answer as it is kept: what it said, and what it had been shown. */
    fun verdict(answer: Answer?, prefersLeft: Boolean?, sure: Double?, why: String?, model: String?, question: String? = null): AiVerdict = AiVerdict(
        answer = answer?.name,
        prefer = prefersLeft?.let { if (it) StoredTrial.LEFT else StoredTrial.RIGHT },
        sure = sure?.coerceIn(0.0, 1.0),
        why = why?.trim()?.take(WHY)?.takeIf { it.isNotEmpty() },
        model = model,
        examples = examples,
        rubric = rubric,
        predicted = predicted,
        kind = kind.key,
        print = print,
        asked = asked,
        question = question?.trim()?.take(WHY)?.takeIf { it.isNotEmpty() },
    )

    companion object {
        /** A card's text as handed with a hand, in characters. */
        const val CARD_TEXT = 600

        /** Ai's reason and question are one line each. */
        const val WHY = 240

        /**
         * The brief for [proposal] on [bench]: [rubricText] as the file holds it, [examples] from the bank (taken before
         * the person answered), [prediction] from the fit. [name] names a card by its passcode.
         */
        fun of(
            bench: Bench,
            proposal: Proposal,
            prediction: Prediction?,
            rubricText: String?,
            examples: List<Example>,
            deckName: String,
            name: (Int) -> String,
            asked: Long,
            fitted: Int,
            /** A card's printed text by its passcode, handed with the hand so Ai never guesses what a card does. */
            cardText: (Int) -> String? = { null },
        ): JudgeBrief {
            val kind = bench.kindOf(proposal)
            val alone = bench.alone
            val shown = when (proposal) {
                is Proposal.Rate -> bench.ids(proposal.hand)
                is Proposal.Compare -> bench.ids(proposal.left) + bench.ids(proposal.right)
            } + proposal.opponent?.let(bench::opponentIds).orEmpty()
            val text = buildString {
                appendLine("Judge this hand as the person would, then answer with shootout_judge (once).")
                appendLine()
                appendLine("## The hand")
                appendLine("${ShootoutWords.situation(proposal.stratum, bench.opponentName)} · $deckName${bench.opponentName?.let { " against $it" } ?: " on its own"}.")
                appendLine("Kind of hand: ${kind.words}.")
                when (proposal) {
                    is Proposal.Rate -> {
                        proposal.opponent?.let { appendLine("Their hand (${it.size}): ${names(bench.opponentIds(it), name)}") }
                        appendLine("Your hand (${proposal.hand.size}): ${names(bench.ids(proposal.hand), name)}")
                        appendLine()
                        appendLine(
                            if (alone) "Answer 1 to 5: 1 plays through (80–100 %), 2 likely does (60–80 %), 3 coin flip, 4 likely not (20–40 %), 5 bricks (0–20 %)."
                            else "Answer 1 to 5: 1 clear win (80–100 %), 2 lean win (60–80 %), 3 coin flip (40–60 %), 4 lean loss (20–40 %), 5 clear loss (0–20 %).",
                        )
                    }
                    is Proposal.Compare -> {
                        proposal.opponent?.let { appendLine("Their hand (${it.size}): ${names(bench.opponentIds(it), name)}") }
                        appendLine("Left hand: ${names(bench.ids(proposal.left), name)}")
                        appendLine("Right hand: ${names(bench.ids(proposal.right), name)}")
                        appendLine()
                        appendLine("Answer prefer: left or right — the hand the person would rather open with.")
                    }
                }
                val texts = shown.distinct().mapNotNull { id -> cardText(id)?.takeIf { it.isNotBlank() }?.let { name(id) to it } }
                if (texts.isNotEmpty()) {
                    appendLine()
                    appendLine("## The cards")
                    texts.forEach { (n, t) -> appendLine("- $n: ${t.replace('\n', ' ').take(CARD_TEXT)}") }
                }
                appendLine()
                appendLine("## The model's prediction (one input among the others)")
                if (prediction == null || fitted == 0) {
                    appendLine("None yet: no hands judged.")
                } else if (prediction.prefersLeft != null) {
                    appendLine("From $fitted answers: the ${if (prediction.prefersLeft) "left" else "right"} hand, ${pct(if (prediction.prefersLeft) prediction.win else 1 - prediction.win)} likely.")
                } else {
                    appendLine("From $fitted answers: a ${pct(prediction.win)} win chance, likeliest answer ${prediction.likeliest?.let { "${ShootoutWords.keyOf(it)} (${ShootoutWords.label(it, alone)})" } ?: "?"}" + if (prediction.sd > 0.8) "; the fit is unsure of this hand." else ".")
                }
                appendLine()
                appendLine("## How this person judges this matchup (the rubric)")
                appendLine(Rubric.forPrompt(rubricText))
                appendLine()
                appendLine("## The person's judged hands most like this one")
                if (examples.isEmpty()) appendLine("(None yet.)")
                examples.forEachIndexed { i, e ->
                    val t = e.trial
                    val situation = Situation.of(t)?.stratum?.let(ShootoutWords::stratum) ?: t.stratum
                    val opp = t.opponent?.let { " · theirs: ${names(it, name)}" }.orEmpty()
                    val what = if (t.kind == StoredTrial.COMPARE) {
                        "left: ${names(t.left, name)} · right: ${names(t.right, name)}$opp → preferred ${t.prefer}"
                    } else {
                        val a = Answer.entries.firstOrNull { it.name == t.answer }
                        "yours: ${names(t.hand, name)}$opp → ${a?.let { "${ShootoutWords.keyOf(it)} ${ShootoutWords.label(it, alone)}" } ?: t.answer}"
                    }
                    append("${i + 1}. [$situation${if (t.sawAi) ", after seeing your answer" else ""}] $what")
                    e.notes.forEach { n -> append(" · their note: “${n.text.take(200)}”") }
                    appendLine()
                }
                appendLine()
                append("Say how sure you are (0 to 1): how likely the person's own answer is within one step of yours. Reason in one line.")
            }
            return JudgeBrief(
                text = text.trim(),
                examples = examples.map { it.trial.id },
                rubric = Rubric.hash(rubricText),
                predicted = prediction?.win,
                kind = kind,
                print = bench.print,
                asked = asked,
                alone = alone,
                compare = proposal is Proposal.Compare,
            )
        }

        /** Copies as players write them: "2 Ash Blossom, Nibiru". */
        fun names(ids: List<Int>, name: (Int) -> String): String =
            ids.groupingBy { it }.eachCount().entries.joinToString(", ") { (id, n) -> (if (n > 1) "$n " else "") + name(id) }

        /**
         * An answer as Ai gives it: a key 1 to 5, or the scale's words in either reading ("lean win", "likely not",
         * "clear_loss"); null when it is neither.
         */
        fun parseAnswer(raw: String?): Answer? {
            val s = raw?.trim()?.lowercase()?.replace('_', ' ')?.replace('-', ' ') ?: return null
            s.toIntOrNull()?.let { return ShootoutWords.byKey(it) }
            return ShootoutWords.SCALE.firstOrNull { a ->
                s == ShootoutWords.label(a, alone = false).lowercase() || s == ShootoutWords.label(a, alone = true).lowercase() ||
                    s == a.name.lowercase().replace('_', ' ')
            }
        }

        private fun pct(x: Double) = "${round(x * 100).toInt()} %"
    }
}
