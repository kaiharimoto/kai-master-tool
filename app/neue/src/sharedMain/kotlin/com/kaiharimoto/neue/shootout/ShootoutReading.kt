package com.kaiharimoto.neue.shootout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWriteUp
import com.kaiharimoto.mastertool.core.shootout.bench.WriteUpEntry
import com.kaiharimoto.mastertool.core.shootout.store.RoleLean
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriorsCodec
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.DeckReading
import com.kaiharimoto.neue.ai.readDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ai reads the deck first, and the results go into the guide (Phase S, S4.3; `docs/phases/S.md` §6 1, §6 4) — a part of
 * [Shootouts]: Ai's reading of the deck and target on the page ([priors], kept beside the trials and handed to the bench as
 * where the ratings start), every line of it the person's to turn off or change, and the results written into the deck's
 * guide from the computed numbers only, each with its proof.
 */
class ShootoutReading internal constructor(private val s: Shootouts, private val h: NeueHolders) {

    /** Ai's reading of the deck and target on the page, as kept; null when none. */
    var priors by mutableStateOf<ShootoutPriors?>(null)
        internal set

    /** Ai is reading the deck now. */
    var asking by mutableStateOf(false)
        private set

    /** The write-up offered (S4.3), each line with its proof, before any of it is kept; null when none is open. */
    var writeUp by mutableStateOf<List<WriteUpEntry>?>(null)

    private fun target(): Pair<String, String?>? {
        val deck = s.deckId ?: h.builder.deckId ?: return null
        val b = s.bench ?: return null
        return deck to s.opponentId.takeIf { !b.alone }
    }

    /** The reading kept for [deck] and [opponent], read from disk (off the frame thread). */
    internal suspend fun read(deck: String, opponent: String?): ShootoutPriors? = withContext(Dispatchers.IO) {
        File(s.dataDir, ShootoutPaths.priors(deck, opponent)).takeIf { it.isFile }?.readText()
    }.let(ShootoutPriorsCodec::decode)

    /** Ai reads the deck on the page: its answer kept and the bench built again from it. */
    fun ask() {
        val b = s.bench ?: return
        val (deck, opponent) = target() ?: return
        if (asking) return
        asking = true
        s.scope.launch {
            try {
                when (val got = h.ai.readDeck(b, s.deckName, { s.card(it)?.name ?: "#$it" }, { s.card(it)?.description })) {
                    is DeckReading.Read -> {
                        save(deck, opponent, got.priors)
                        h.neue.note = Note("${h.ai.name} read the deck: ${got.priors.leans.size} roles leaned, ${got.priors.pairs.size} pairs named. Change any line, or turn it off.")
                    }
                    is DeckReading.Failed -> h.neue.note = Note(got.why)
                }
            } finally {
                asking = false
            }
        }
    }

    /** Role [role]'s lean set to [step] (−2…2) by the person. */
    fun setStep(role: String, step: Int) = edit { p ->
        val clamped = step.coerceIn(ShootoutPriors.STEPS)
        val has = p.leans.any { it.role == role }
        p.copy(leans = if (has) p.leans.map { if (it.role == role) it.copy(step = clamped, on = true) else it } else p.leans + RoleLean(role, clamped))
    }

    /** Role [role]'s lean turned off, or back on. */
    fun toggleLean(role: String) = edit { p -> p.copy(leans = p.leans.map { if (it.role == role) it.copy(on = !it.on) else it }) }

    /** The pair of [a] and [b] turned off, or back on. */
    fun togglePair(a: Int, b: Int) = edit { p -> p.copy(pairs = p.pairs.map { if (it.a == a && it.b == b) it.copy(on = !it.on) else it }) }

    /** The reading put away: every role starts level again, and the commonest pairs are fitted. */
    fun clear() {
        val (deck, opponent) = target() ?: return
        s.scope.launch {
            withContext(Dispatchers.IO) { File(s.dataDir, ShootoutPaths.priors(deck, opponent)).delete() }
            priors = null
            s.prepare()
        }
    }

    private fun edit(change: (ShootoutPriors) -> ShootoutPriors) {
        val p = priors ?: ShootoutPriors(by = ShootoutPriors.BY_PERSON)
        val (deck, opponent) = target() ?: return
        s.scope.launch { save(deck, opponent, change(p).copy(by = ShootoutPriors.BY_PERSON)) }
    }

    private suspend fun save(deck: String, opponent: String?, p: ShootoutPriors) {
        withContext(Dispatchers.IO) {
            val f = File(s.dataDir, ShootoutPaths.priors(deck, opponent))
            f.parentFile?.mkdirs()
            f.writeText(ShootoutPriorsCodec.encode(p))
        }
        priors = p
        // The bench is built again: the leans and pairs are where the fit starts.
        s.prepare()
    }

    /** The studio's reading (`--shootout-priors=demo`): written as Ai's would be, before the demo deals. Never in the app. */
    fun studioWrite(deck: String, opponent: String?, p: ShootoutPriors) {
        val f = File(s.dataDir, ShootoutPaths.priors(deck, opponent))
        f.parentFile?.mkdirs()
        f.writeText(ShootoutPriorsCodec.encode(p))
    }

    // ---- the write-up into the guide -----------------------------------------------------------------------------

    /** Why the results cannot go into the guide now, in words; null when they can. */
    fun writeUpProblem(): String? = when {
        target() == null -> "Choose a saved deck: the guide is the deck's."
        s.results == null -> "Read the results first."
        else -> null
    }

    /** The lines the results would write, offered to keep or leave. */
    fun offerWriteUp() {
        val r = s.results ?: return
        val b = s.bench ?: return
        val (deck, opponent) = target() ?: return
        val lines = ShootoutWriteUp.entries(r, b.opponentName, { s.card(it)?.name ?: "#$it" }, deck, opponent, b.deckPrint.orEmpty(), System.currentTimeMillis())
        if (lines.isEmpty()) {
            h.neue.note = Note("Nothing is called yet: the guide is written from what the hands have called.")
            return
        }
        writeUp = lines
    }

    /**
     * [chosen] written into the deck's guide, in the place of the lines this target wrote before, each with its proof in the
     * evidence ledger; the note that says so puts the guide back as it was.
     */
    fun keepWriteUp(chosen: List<WriteUpEntry>) {
        writeUp = null
        val b = s.bench ?: return
        val (deck, _) = target() ?: return
        if (chosen.isEmpty()) return
        val files = h.ai.files
        val guidePath = AiMemory.path(MemoryKind.GUIDE, deck)
        val ledgerPath = Ledger.path(deck)
        s.scope.launch {
            val (oldGuide, oldLedger) = withContext(Dispatchers.IO) {
                val guide = files.read(guidePath)
                val ledger = files.read(ledgerPath)
                val doc = files.memory(MemoryKind.GUIDE, deck, s.deckName)
                val kept = doc.entries.filterNot { ShootoutWriteUp.isOurs(it, b.opponentName) }
                val next = doc.copy(entries = kept + chosen.map { it.text })
                files.write(guidePath, next.render())
                val now = System.currentTimeMillis()
                var proven = Ledger.prune(Ledger.read(ledger), next.entries)
                chosen.forEach { proven = Ledger.put(proven, it.proven(now)) }
                files.write(ledgerPath, Ledger.write(proven))
                guide to ledger
            }
            h.neue.note = Note("${chosen.size} line${if (chosen.size == 1) "" else "s"} written into the guide of ${s.deckName}, each with its proof.", action = "Undo", lastsMs = 10_000) {
                s.scope.launch {
                    withContext(Dispatchers.IO) {
                        if (oldGuide == null) files.delete(guidePath) else files.write(guidePath, oldGuide)
                        if (oldLedger == null) files.delete(ledgerPath) else files.write(ledgerPath, oldLedger)
                    }
                    h.neue.note = Note("The guide is as it was.")
                }
            }
        }
    }
}
