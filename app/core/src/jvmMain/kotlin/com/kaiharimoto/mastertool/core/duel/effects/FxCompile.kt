package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.world.JsRuntime
import com.kaiharimoto.mastertool.core.world.WorldApi
import com.kaiharimoto.mastertool.core.world.WorldHost
import kotlinx.serialization.json.JsonObject

/**
 * Compiling a card's effect (Phase D step 2, `docs/phases/D.md` §3.2): `lib/effects/<passcode>.js` run once in Rhino, shut
 * in ([JsRuntime]: nothing of Java, one door, a budget), on a small budget of its own — [MILLIS] and [Limits] — and its last
 * value read **as data** (`JsData`: a function, a getter or a cycle in it is refused, never dropped in silence). The data
 * is decoded leniently ([FxCodec.read]) into a [CardScript] and made whole ([FxShelf.fill]: the pool's name, the hash of the
 * printed text and of the source). The legality pass ([FxCheck]) and the writing of `<passcode>.json` are the library's.
 *
 * Ai's code runs only here, to build data: play, tests and the goldfish read the data through Kotlin (§7). Python never
 * writes effects: the library must compile on the phone, and this runs on both.
 */
object FxCompile {
    /** The compile budget (D.md §3.2). */
    const val MILLIS = 5_000L

    /** A source at most this many bytes, as a compiled script (D.md §3.4). */
    const val MAX_SOURCE = FxCodec.MAX_BYTES

    /** Steps, time, printed output and memory: a card's script builds a few kilobytes of data. */
    val LIMITS = JsRuntime.Limits(instructions = 400_000_000L, millis = MILLIS, output = 8_000, heapMb = 64L)

    sealed interface Outcome {
        /** [script] compiled and made whole; [json] the file to keep; [printed] what it printed (for the person's eyes). */
        data class Compiled(val script: CardScript, val json: String, val printed: String, val ms: Long) : Outcome

        /** Why not, in words, with the script's own line where Rhino knows it. */
        data class Failed(val why: String, val printed: String = "", val ms: Long = 0L) : Outcome
    }

    /**
     * Compiles [source] as the script of [passcode]: [host] is what the script may read (the pool, the library's helpers:
     * [FxHost]); [card] is the pool's card for it. [limits] stand in for [LIMITS] only in tests of the budget.
     */
    fun compile(passcode: Int, source: String, host: WorldHost, card: Card? = host.cardById(passcode), limits: JsRuntime.Limits = LIMITS): Outcome {
        val bytes = source.encodeToByteArray().size
        if (bytes > MAX_SOURCE) return Outcome.Failed("The source is ${bytes / 1024} KB; a card's script is at most 64 KB.")
        val name = "lib/effects/$passcode.js"
        val r = JsRuntime(limits).run(source, name, WorldApi(host), data = true)
        val printed = r.out.take(limits.output)
        if (!r.ok) return Outcome.Failed(r.err.ifBlank { "The script failed." }, printed, r.ms)
        val data = r.data as? JsonObject
            ?: return Outcome.Failed("$name must end with fx.card($passcode, {…}): its last value is the card's script, and it was ${describe(r.data)}.", printed, r.ms)
        val text = data.toString()
        if (text.encodeToByteArray().size > FxCodec.MAX_BYTES) return Outcome.Failed("The script it builds is larger than 64 KB.", printed, r.ms)
        return when (val read = FxCodec.read(text)) {
            is FxRead.Bad -> Outcome.Failed("What it built is not a card's script: ${read.why}", printed, r.ms)
            is FxRead.Newer -> Outcome.Failed("It says vocabulary ${read.vocab}; this build writes vocabulary ${FxVocab.VERSION}.", printed, r.ms)
            is FxRead.Script -> {
                val whole = FxShelf.fill(read.script, card, source)
                Outcome.Compiled(whole, FxCodec.file(whole), printed, r.ms)
            }
        }
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "nothing"
        is kotlinx.serialization.json.JsonArray -> "a list"
        is kotlinx.serialization.json.JsonPrimitive -> if (v.isString) "words" else "“$v”"
        else -> "not an object"
    }
}
