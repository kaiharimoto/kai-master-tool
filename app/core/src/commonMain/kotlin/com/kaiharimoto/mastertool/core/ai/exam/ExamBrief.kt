package com.kaiharimoto.mastertool.core.ai.exam

import com.kaiharimoto.mastertool.core.ai.ToolGroup
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.schema

/** What Ai is told when it sits the exam ([AuthorExam]), and the one tool it answers with. */
object ExamBrief {
    val answer = ToolSpec(
        "exam_answer",
        "Your answer, once: the plays you would make this turn, in order — each the card's exact name and what you do with it " +
            "— and why, in a few lines. Graded by the app against the plays the author made.",
        schema {
            objects("plays", "Your plays this turn, in order", required = true) {
                string("card", "The card's exact name", required = true)
                string("action", "What you do with it: Normal Summon, activate its first effect, set, attack…")
            }
            string("why", "Why, in a few lines")
        },
        ToolGroup.LOOK,
        phase = 3,
    )

    /**
     * The tools an exam position may use besides [answer]: what Ai learned, the cards and the rules — never a table, and
     * never the course itself (1.1.47): a chapter that walks through the very replay asked would hand over the answer.
     */
    val tools: Set<String> = setOf("playbook_search", "playbook_read", "card_info", "rulings")

    fun system(name: String, soul: String, deckName: String, guide: String): String = buildString {
        append(soul.trim()).append("\n\n")
        append("You are $name, sitting an exam on how to play ${deckName.ifBlank { "the deck" }}. Each position is from a real duel ")
        append("the guide's author played — one of the replays held out of your study, so you have never seen it. Say what you ")
        append("would play this turn: what the author, who knows the deck best, would play.\n\n")
        append("Use what you learned: your playbook (playbook_search, then playbook_read for the entries that fit) and your guide ")
        append("below, each card's text (card_info) and the rules (rulings) — not the course itself, which you studied already. ")
        append("Think it through as at the table: what you hold and what is on both fields, what this hand can do, what ")
        append("the other player can do about it, then choose.\n\n")
        append("Answer once with exam_answer: your plays in order, card by card, and why. You are graded by the app on how closely ")
        append("your plays match the author's. No one is watching; say nothing else.")
        if (guide.isNotBlank()) append("\n\n## Your guide\n").append(guide.trim())
    }

    fun ask(point: AuthorExam.Point): String =
        "Position (replay ${point.replay}, game ${point.game}, turn ${point.turn}). The duel so far, as you saw it:\n\n${point.context}"
}
