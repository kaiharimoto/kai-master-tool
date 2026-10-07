package com.kaiharimoto.mastertool.core.ai.playbook

/**
 * How the playbook is written: one entry at a time or many at once, each checked before it is kept. A line is steps with
 * the hand it needs and the board it ends on; a decision is a situation, a choice and why; a matchup names its deck —
 * an entry that says less is refused with what it lacks, so what is kept is something a player could use. The same
 * entry twice is refused with the one it repeats, to be updated instead: one entry, its sources gathered.
 */
object PlaybookEdits {
    /** An entry as written, every field optional: an add fills what is missing with nothing, an update keeps it. */
    data class Draft(
        val kind: Play.Kind? = null,
        val title: String? = null,
        val body: String? = null,
        val cards: List<String>? = null,
        val needs: List<String>? = null,
        val steps: List<Step>? = null,
        val endBoard: String? = null,
        val through: List<String>? = null,
        val weakTo: List<String>? = null,
        val situation: String? = null,
        val choice: String? = null,
        val why: String? = null,
        val against: String? = null,
        val going: Play.Going? = null,
        val sources: List<Source>? = null,
        val confidence: Play.Confidence? = null,
    )

    data class Outcome(val book: Playbook, val said: List<String>, val changed: List<String>) {
        val message: String get() = said.joinToString("\n")
    }

    /**
     * The most one entry's words may hold, and one field: thorough, never a whole chapter pasted. What goes past them is
     * said in the answer (1.1.47: it was cut silently, and the skills ask for the author's reasoning whole).
     */
    const val BODY_CAP = 20_000
    const val FIELD_CAP = 4_000

    /** The fields of [d] longer than they may be, in words for the answer; empty when nothing is cut. */
    fun overflow(d: Draft): String {
        val long = listOfNotNull(
            d.body?.takeIf { it.trim().length > BODY_CAP }?.let { "body" },
            d.title?.takeIf { it.trim().length > FIELD_CAP }?.let { "title" },
            d.endBoard?.takeIf { it.trim().length > FIELD_CAP }?.let { "end_board" },
            d.situation?.takeIf { it.trim().length > FIELD_CAP }?.let { "situation" },
            d.choice?.takeIf { it.trim().length > FIELD_CAP }?.let { "choice" },
            d.why?.takeIf { it.trim().length > FIELD_CAP }?.let { "why" },
            d.against?.takeIf { it.trim().length > FIELD_CAP }?.let { "against" },
        )
        return if (long.isEmpty()) "" else " (cut: ${long.joinToString()} past ${FIELD_CAP} characters, body past $BODY_CAP — " +
            "say the rest in a second entry, or tighten it)"
    }
    const val STEPS_CAP = 60

    /** [drafts] added to [book]: each kept or refused with why. [sourced]: an entry must say where it was learned. */
    fun add(book: Playbook, drafts: List<Draft>, now: Long, sourced: Boolean = true): Outcome {
        var b = book
        val said = ArrayList<String>()
        val changed = ArrayList<String>()
        drafts.forEachIndexed { i, d ->
            val play = build(d, "pending", now)
            val why = refusal(play, sourced)
            val twin = play.title.takeIf { it.isNotBlank() }?.let { t -> b.entries.firstOrNull { it.kind == play.kind && same(it.title, t) } }
            when {
                why != null -> said += "Entry ${i + 1} (“${play.title.take(60)}”) not kept: $why"
                twin != null -> said += "Entry ${i + 1} (“${play.title.take(60)}”) is already ${twin.id}: update ${twin.id} instead, adding your source."
                else -> {
                    val id = "${play.kind.word}-${b.next}"
                    b = b.copy(entries = b.entries + play.copy(id = id), next = b.next + 1)
                    changed += id
                    said += "Kept $id: ${play.title.take(80)}" + overflow(d)
                }
            }
        }
        return Outcome(b, said, changed)
    }

    /**
     * Entry [id] with [draft]'s fields in place of its own: text fields replaced when given, lists replaced when given,
     * sources always added to (never lost), the result checked as an add is.
     */
    fun update(book: Playbook, id: String, draft: Draft, now: Long, sourced: Boolean = true): Outcome {
        val old = book.entry(id) ?: return Outcome(book, listOf("No entry $id: playbook_search finds them."), emptyList())
        val merged = old.copy(
            kind = draft.kind ?: old.kind,
            title = draft.title?.trim()?.take(FIELD_CAP) ?: old.title,
            body = draft.body?.trim()?.take(BODY_CAP) ?: old.body,
            cards = draft.cards?.let(::names) ?: old.cards,
            needs = draft.needs?.let(::names) ?: old.needs,
            steps = draft.steps?.let(::steps) ?: old.steps,
            endBoard = draft.endBoard?.trim()?.take(FIELD_CAP) ?: old.endBoard,
            through = draft.through?.let(::lines) ?: old.through,
            weakTo = draft.weakTo?.let(::lines) ?: old.weakTo,
            situation = draft.situation?.trim()?.take(FIELD_CAP) ?: old.situation,
            choice = draft.choice?.trim()?.take(FIELD_CAP) ?: old.choice,
            why = draft.why?.trim()?.take(FIELD_CAP) ?: old.why,
            against = draft.against?.trim()?.take(FIELD_CAP) ?: old.against,
            going = draft.going ?: old.going,
            sources = (old.sources + draft.sources.orEmpty().let(::sources)).distinctBy { it.ref.lowercase() },
            confidence = draft.confidence?.let { stronger(old.confidence, it) } ?: old.confidence,
            updatedAt = now,
        )
        refusal(merged, sourced)?.let { return Outcome(book, listOf("$id not updated: $it"), emptyList()) }
        return Outcome(book.with(merged), listOf("Updated $id: ${merged.title.take(80)} (${merged.sources.size} source${if (merged.sources.size == 1) "" else "s"})" + overflow(draft)), listOf(id))
    }

    /**
     * [fold] folded into [keep]: their sources, cards and words kept on [keep], the folded entries gone. Nothing a folded
     * entry said is lost (1.1.47: its situation, choice, why, steps and end board were): a field [keep] lacks is taken from
     * it, and whatever else it said is kept in [keep]'s body, written whole, under "Also (was id)". Entries of different
     * kinds are never folded together.
     */
    fun merge(book: Playbook, keep: String, fold: List<String>, now: Long): Outcome {
        val k = book.entry(keep) ?: return Outcome(book, listOf("No entry $keep."), emptyList())
        val others = fold.filter { !it.equals(keep, ignoreCase = true) }.map { book.entry(it) ?: return Outcome(book, listOf("No entry $it."), emptyList()) }
        if (others.isEmpty()) return Outcome(book, listOf("Name the entries to fold into $keep."), emptyList())
        others.firstOrNull { it.kind != k.kind }?.let { o ->
            return Outcome(book, listOf("${o.id} is a ${o.kind.word} and ${k.id} a ${k.kind.word}: only entries of one kind are folded together. Link them in their words instead."), emptyList())
        }
        fun pick(own: String, of: (Play) -> String) = own.ifBlank { others.map(of).firstOrNull { it.isNotBlank() }.orEmpty() }
        val filled = k.copy(
            situation = pick(k.situation) { it.situation },
            choice = pick(k.choice) { it.choice },
            why = pick(k.why) { it.why },
            endBoard = pick(k.endBoard) { it.endBoard },
            against = pick(k.against) { it.against },
            steps = k.steps.ifEmpty { others.firstOrNull { it.steps.isNotEmpty() }?.steps.orEmpty() },
            needs = (k.needs + others.flatMap { it.needs }).distinctBy { it.lowercase() },
        )
        // What each folded entry said that the kept one does not already say, whole.
        val also = others.mapNotNull { o ->
            val said = PlaybookSearch.render(o.copy(sources = emptyList(), cards = emptyList()), compact = false).lines().drop(1)
                .filter { line -> line.isNotBlank() && line.trim() !in PlaybookSearch.render(filled).lines().map { it.trim() } }
            if (said.isEmpty()) null else "Also (was ${o.id}, “${o.title.take(80)}”):\n" + said.joinToString("\n")
        }
        val whole = (listOf(k.body) + also).filter { it.isNotBlank() }.joinToString("\n\n")
        val merged = filled.copy(
            body = whole.take(BODY_CAP),
            cards = (k.cards + others.flatMap { it.cards }).distinctBy { it.lowercase() },
            through = (k.through + others.flatMap { it.through }).distinct(),
            weakTo = (k.weakTo + others.flatMap { it.weakTo }).distinct(),
            sources = (k.sources + others.flatMap { it.sources }).distinctBy { it.ref.lowercase() },
            confidence = others.fold(k.confidence) { c, o -> stronger(c, o.confidence) },
            updatedAt = now,
        )
        val gone = others.map { it.id }.toSet()
        val b = book.copy(entries = book.entries.filter { it.id !in gone }.map { if (it.id == k.id) merged else it })
        val cut = if (whole.length > BODY_CAP) " Its body is past $BODY_CAP characters and was cut: tighten it with an update." else ""
        return Outcome(b, listOf("Folded ${gone.joinToString()} into ${k.id} (${merged.sources.size} sources).$cut"), listOf(k.id) + gone)
    }

    fun remove(book: Playbook, id: String): Outcome {
        val p = book.entry(id) ?: return Outcome(book, listOf("No entry $id."), emptyList())
        return Outcome(book.copy(entries = book.entries.filter { it.id != p.id }), listOf("Removed ${p.id}: ${p.title.take(80)}"), listOf(p.id))
    }

    /** What [play] lacks to be kept, or null. */
    fun refusal(play: Play, sourced: Boolean): String? = when {
        play.title.isBlank() -> "it needs a title."
        sourced && play.sources.isEmpty() -> "say where it was learned (sources: a chapter's section, a replay's game and turn)."
        play.kind == Play.Kind.LINE && play.steps.size < 2 -> "a line is its steps, at least two, card by card."
        play.kind == Play.Kind.LINE && play.needs.isEmpty() -> "a line names the cards the hand needs to start it (needs)."
        play.kind == Play.Kind.LINE && play.endBoard.isBlank() -> "a line says the board it ends on (end_board)."
        play.kind == Play.Kind.DECISION && (play.situation.isBlank() || play.choice.isBlank() || play.why.isBlank()) ->
            "a decision is its situation, its choice and why — all three."
        play.kind == Play.Kind.MATCHUP && play.against.isBlank() -> "a matchup names the deck it is against."
        play.kind != Play.Kind.LINE && play.kind != Play.Kind.DECISION && play.body.isBlank() -> "say it in the body."
        else -> null
    }

    private fun build(d: Draft, id: String, now: Long) = Play(
        id = id,
        kind = d.kind ?: Play.Kind.PRINCIPLE,
        title = d.title.orEmpty().trim().take(FIELD_CAP),
        body = d.body.orEmpty().trim().take(BODY_CAP),
        cards = names(d.cards.orEmpty()),
        needs = names(d.needs.orEmpty()),
        steps = steps(d.steps.orEmpty()),
        endBoard = d.endBoard.orEmpty().trim().take(FIELD_CAP),
        through = lines(d.through.orEmpty()),
        weakTo = lines(d.weakTo.orEmpty()),
        situation = d.situation.orEmpty().trim().take(FIELD_CAP),
        choice = d.choice.orEmpty().trim().take(FIELD_CAP),
        why = d.why.orEmpty().trim().take(FIELD_CAP),
        against = d.against.orEmpty().trim().take(FIELD_CAP),
        going = d.going ?: Play.Going.EITHER,
        sources = sources(d.sources.orEmpty()),
        confidence = d.confidence ?: Play.Confidence.STATED,
        updatedAt = now,
    )

    private fun names(list: List<String>) = list.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(60)
    private fun lines(list: List<String>) = list.map { it.trim().take(FIELD_CAP) }.filter { it.isNotBlank() }.distinct().take(30)
    private fun steps(list: List<Step>) = list.filter { it.card.isNotBlank() || it.action.isNotBlank() }
        .map { Step(it.card.trim().take(120), it.action.trim().take(FIELD_CAP), it.result.trim().take(FIELD_CAP)) }.take(STEPS_CAP)
    private fun sources(list: List<Source>) = list.filter { it.ref.isNotBlank() }.map { Source(it.ref.trim().take(200), it.note.trim().take(400)) }

    /** The surer of two: verified over shown over stated over inferred. */
    private fun stronger(a: Play.Confidence, b: Play.Confidence): Play.Confidence {
        val order = listOf(Play.Confidence.INFERRED, Play.Confidence.STATED, Play.Confidence.SHOWN, Play.Confidence.VERIFIED)
        return if (order.indexOf(b) > order.indexOf(a)) b else a
    }

    /** Two titles that name the same thing: the same words, case and punctuation aside. */
    fun same(a: String, b: String): Boolean = key(a) == key(b)

    private fun key(t: String) = t.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }.joinToString(" ")
}
