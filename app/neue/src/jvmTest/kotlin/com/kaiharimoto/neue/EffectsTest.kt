package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.duel.effects.FxPlayedUse
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCond
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxOffers
import com.kaiharimoto.mastertool.core.duel.effects.FxRequest
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.duel.effects.FxStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.neue.effects.Effects
import com.kaiharimoto.neue.world.Worlds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The effects library end to end (Phase D step 2, D.md §3.2–§3.3, §6): a source written through a world lands in
 * `<data>/effects/`, compiles, is checked and joins the book every printing reads; the app's own files under the mount are
 * never a world's to write; a source synced in is compiled and checked again; a newer build's script is never compiled
 * over; only the person accepts a warning. The cards are fictional (900000200–900000299).
 */
class EffectsTest {
    private val herald = Card(
        CardId(HERALD), "Example Herald", "Effect Monster", "effect",
        "If this card is Normal Summoned: You can add 1 \"Example\" monster from your Deck to your hand. You can only use this effect of \"Example Herald\" once per turn.",
        race = "Warrior", attribute = CardAttribute.LIGHT, atk = 1500, def = 1000, level = 4, alternateIds = listOf(CardId(HERALD_ALT)),
    )
    private val squire = Card(CardId(SQUIRE), "Example Squire", "Normal Monster", "normal", "", race = "Warrior", attribute = CardAttribute.EARTH, atk = 1000, def = 1000, level = 3)
    private val index = CardIndex.build(listOf(herald, squire))

    private val heraldJs = """
        fx.card($HERALD, {
          effects: [
            fx.trigger('e1', { label: 'Search', on: fx.on.normalSummoned(), opt: fx.opt.byName(),
              does: [ fx.add({ from: 'your deck', where: fx.all(fx.nameHas('Example'), fx.monster()) }) ] }),
          ],
        })
    """.trimIndent()

    private val host = object : WorldHost {
        override fun cardById(id: Int) = index.byId(CardId(id))
        override fun cardNamed(name: String) = index.byName(name)
        override fun search(query: String, limit: Int) = emptyList<Card>()
        override fun deck(id: String?) = DeckEntry("d1", "Test", Deck.EMPTY, 0, 0)
        override fun decks() = emptyList<DeckEntry>()
    }

    private class Setup(val data: File, val effects: Effects, val worlds: Worlds)

    private fun setup(): Setup {
        val data = Files.createTempDirectory("fx").toFile()
        val effects = Effects.under(data).also { it.pool = { index } }
        val worlds = Worlds(File(data, "world")).also { w ->
            w.host = { host }
            w.prefs = { WorldPrefs(typing = 0, follow = false) }
            w.mounts += effects.mount
        }
        return Setup(data, effects, worlds)
    }

    @Test
    fun aSourceWrittenInAWorldCompilesChecksAndJoinsTheBook() = runBlocking {
        val s = setup()
        withContext(Dispatchers.Main) { s.worlds.create("Effects", null, WorldEvent.YOU) }
        val said = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.YOU) }.getOrThrow()
        assertTrue("Compiled Example Herald" in said && "0 errors, 0 warnings" in said, said)
        // It lands in the library, never in the world's own files, and the world lists and reads it there.
        assertTrue(File(s.data, "effects/$HERALD.js").isFile && File(s.data, "effects/$HERALD.json").isFile)
        assertTrue(File(s.data, "world").walkTopDown().none { it.name == "$HERALD.js" })
        assertTrue("lib/effects/$HERALD.js" in s.worlds.files, s.worlds.files.toString())
        assertEquals(heraldJs, s.worlds.read("lib/effects/$HERALD.js"))
        // The compiled file carries its vocabulary and the source's hash; the book holds it, read by every printing.
        val json = File(s.data, "effects/$HERALD.json").readText()
        val script = assertNotNull(FxCodec.decode(json))
        assertEquals(FxCodec.short(heraldJs), script.source)
        assertTrue(json.contains("\"vocab\":1"))
        assertEquals(FxStatus.UNTESTED, s.effects.status(HERALD))
        assertSame(s.effects.book.script(HERALD), s.effects.book.script(HERALD_ALT), "an alternate artwork reads the same script")
        assertEquals("Example Herald", s.effects.book.script(HERALD_ALT)?.name)
        assertEquals(FxStatus.UNTESTED, s.effects.status(HERALD_ALT))
        // And the person reads it in words.
        assertTrue(s.effects.words(HERALD).single().text.startsWith("Trigger effect from the Monster Zone. If this card is Normal Summoned, you can add"), s.effects.words(HERALD).toString())
        // A Normal Monster has nothing to write; a card with no script is missing.
        assertEquals(FxStatus.NONE, s.effects.status(SQUIRE))
        // Deleting the source through a world takes its compiled script with it.
        withContext(Dispatchers.Main) { s.worlds.delete("lib/effects/$HERALD.js", WorldEvent.YOU) }.getOrThrow()
        assertFalse(File(s.data, "effects/$HERALD.json").exists())
        assertNull(s.effects.book.script(HERALD))
    }

    @Test
    fun theGoldfishsFilesAreKeptTheMarksFollowAndADeletedDeckTakesItsOwn() = runBlocking {
        // Phase D step 4: what the goldfish trusts, the "played by you" marks and a deck's targets, in `<data>/effects/`.
        val s = setup()
        withContext(Dispatchers.Main) { s.worlds.create("Effects", null, WorldEvent.YOU) }
        withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.YOU) }.getOrThrow()
        val trust = withContext(Dispatchers.Main) { s.effects.trust() }
        assertTrue(trust.trusted(HERALD) && trust.trusted(HERALD_ALT), "an UNTESTED script is used, by any printing")
        assertFalse(trust.playedByYou(HERALD))
        val hash = assertNotNull(trust.hash(HERALD))
        withContext(Dispatchers.Main) { s.effects.played(listOf(FxPlayedUse(HERALD, hash, "e1")), kept = true) }
        assertTrue(withContext(Dispatchers.Main) { s.effects.trust() }.playedByYou(HERALD))
        var waited = 0
        while (!File(s.data, "effects/played.json").readTextOrNull().orEmpty().contains(hash) && waited++ < 100) Thread.sleep(20)
        assertTrue(File(s.data, "effects/played.json").readTextOrNull().orEmpty().contains(hash))
        // A reload (a sync, a restore) reads the marks back.
        val again = Effects.under(s.data).also { it.pool = { index } }
        again.reloadNow()
        assertTrue(withContext(Dispatchers.Main) { again.trust() }.playedByYou(HERALD))
        // The world never writes the marks or a goldfish file.
        listOf("lib/effects/played.json", "lib/effects/goldfish/d1.json").forEach { path ->
            assertTrue(withContext(Dispatchers.Main) { s.worlds.write(path, "{}") }.isFailure, path)
        }
        // A deck's targets kept, then gone with the deck.
        val target = EndBoard("t1", "A Herald", "d1", listOf(BoardCond.Controls(Filter.Name(HERALD), 1)), by = EndBoard.AI)
        s.effects.putTarget("d1", target)
        assertTrue(File(s.data, "effects/goldfish/d1.json").isFile)
        assertEquals(listOf(target), s.effects.goldfish("d1").targets)
        withContext(Dispatchers.Main) { s.effects.forgetDeck("d1") }
        waited = 0
        while (File(s.data, "effects/goldfish/d1.json").exists() && waited++ < 100) Thread.sleep(20)
        assertFalse(File(s.data, "effects/goldfish/d1.json").exists())
        // An undone use takes its mark back.
        withContext(Dispatchers.Main) { s.effects.played(listOf(FxPlayedUse(HERALD, hash, "e1")), kept = false) }
        assertFalse(withContext(Dispatchers.Main) { s.effects.trust() }.playedByYou(HERALD))
    }

    @Test
    fun theLibrarysOwnFilesAreNeverAWorldsToWrite() = runBlocking {
        val s = setup()
        withContext(Dispatchers.Main) { s.worlds.create("Effects", null) }
        listOf("lib/effects/$HERALD.json", "lib/effects/$HERALD.review.json", "lib/effects/asked.json").forEach { path ->
            val r = withContext(Dispatchers.Main) { s.worlds.write(path, "{}") }
            assertTrue(r.isFailure, path)
        }
        // An alternate artwork's passcode is not a card's file: every printing reads its card's one script.
        val alt = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD_ALT.js", heraldJs) }
        assertTrue(alt.exceptionOrNull()?.message.orEmpty().contains("$HERALD.js"), alt.toString())
        // The asked list's seam: a gate set refuses Ai's write and lets the person's through.
        s.effects.gate = { card, _, by -> if (by == WorldEvent.AI && card == HERALD) "not asked for; offer it with fx_request" else null }
        val ai = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.AI) }
        assertEquals("not asked for; offer it with fx_request", ai.exceptionOrNull()?.message)
        assertTrue(withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.YOU) }.isSuccess)
    }

    @Test
    fun aScriptSyncedInIsCompiledAndCheckedAgainAndANewerOneIsKeptUntouched() = runBlocking {
        val s = setup()
        val dir = File(s.data, FxPaths.FOLDER).also { it.mkdirs() }
        // A source and a compiled script arrive from another device; the script was built from another source.
        File(dir, "$HERALD.js").writeText(heraldJs)
        File(dir, "$HERALD.json").writeText("""{"card":$HERALD,"vocab":1,"name":"Example Herald","source":"000000000000","effects":[]}""")
        // A broken one: a ref nothing binds.
        File(dir, "$BROKEN.js").writeText("fx.card($BROKEN, { effects: [ fx.ignition('e1', { from: 'monsters', does: [ fx.destroy({ ref: 'nobody' }) ] }) ] })")
        // A newer build's script beside its source.
        val newer = """{"card":$NEWER,"vocab":${com.kaiharimoto.mastertool.core.duel.effects.FxVocab.VERSION + 1},"name":"Later","effects":[{"id":"e1","kind":"FUTURE"}]}"""
        File(dir, "$NEWER.js").writeText("fx.card($NEWER, {})")
        File(dir, "$NEWER.json").writeText(newer)
        s.effects.reloadNow()
        val h = assertNotNull(s.effects.entries[HERALD])
        assertEquals(FxCodec.short(heraldJs), h.script?.source, "compiled again from the source that came")
        assertEquals(1, h.script?.effects?.size)
        assertEquals(FxStatus.BROKEN, s.effects.status(BROKEN))
        assertNull(s.effects.book.script(BROKEN), "a broken script is never played")
        assertTrue("ref" in s.effects.entries[BROKEN]?.report?.errors.orEmpty().map { it.code })
        assertIs<FxRead.Newer>(s.effects.entries[NEWER]?.compiled)
        assertEquals(newer, File(dir, "$NEWER.json").readText(), "never compiled over")
        assertNull(s.effects.book.script(NEWER))
        assertTrue("To repair" in s.effects.describe(), s.effects.describe())
    }

    @Test
    fun aHelperIsUsedAndAChangeToItCompilesItsCardsAgain() = runBlocking {
        val s = setup()
        withContext(Dispatchers.Main) { s.worlds.create("Effects", null, WorldEvent.YOU) }
        val helper = "function heraldSearch() { return fx.add({ from: 'your deck', where: fx.nameHas('Example') }); }"
        withContext(Dispatchers.Main) { s.worlds.write("lib/effects/_herald.js", helper, WorldEvent.YOU) }.getOrThrow()
        val src = "ygo.use('lib/effects/_herald.js');\nfx.card($HERALD, { effects: [ fx.trigger('e1', { on: fx.on.normalSummoned(), opt: fx.opt.byName(), does: [ heraldSearch() ] }) ] })"
        val said = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", src, WorldEvent.YOU) }.getOrThrow()
        assertTrue("Compiled Example Herald" in said, said)
        assertEquals(setOf(HERALD), s.effects.entries.keys, "a helper is no card of its own")
        val before = s.effects.book.script(HERALD)
        val changed = withContext(Dispatchers.Main) {
            s.worlds.write("lib/effects/_herald.js", helper.replace("fx.nameHas('Example')", "fx.all(fx.nameHas('Example'), fx.monster())"), WorldEvent.YOU)
        }.getOrThrow()
        assertTrue("1 card that use" in changed, changed)
        assertTrue(before != s.effects.book.script(HERALD), "the data moved with its helper")
    }

    @Test
    fun onlyThePersonAcceptsAWarning() = runBlocking {
        val s = setup()
        val dir = File(s.data, FxPaths.FOLDER).also { it.mkdirs() }
        // No once-per-turn where the text has one: a lint, open until fixed or accepted.
        File(dir, "$HERALD.js").writeText(heraldJs.replace("opt: fx.opt.byName(),", ""))
        s.effects.reloadNow()
        assertEquals(FxStatus.WARNED, s.effects.status(HERALD))
        val key = s.effects.entries.getValue(HERALD).open.first().key
        assertFalse(withContext(Dispatchers.Main) { s.effects.accept(HERALD, key, "looks fine", FxReviews.AI) })
        assertTrue(withContext(Dispatchers.Main) { s.effects.accept(HERALD, key, "the limit is on another card in this printing", FxReviews.PERSON) })
        assertEquals(FxStatus.UNTESTED, s.effects.status(HERALD))
        // Kept beside the script, and read again as the library loads.
        var waited = 0
        while (!File(dir, "$HERALD.review.json").isFile && waited++ < 50) Thread.sleep(20)
        s.effects.reloadNow()
        assertEquals(FxStatus.UNTESTED, s.effects.status(HERALD))
    }

    @Test
    fun askThenGoThenWriteThenCompileThenTheLibraryAndThePane() = runBlocking {
        // Phase D step 2, asking (D.md §3.1): nothing asked, Ai's write is refused; the person's go puts the card on the list;
        // Ai writes it, it compiles and joins the library; what it cost is kept; another deck asking for it is told it is
        // reused, at no cost, by any printing.
        val s = setup()
        withContext(Dispatchers.Main) { s.worlds.create("Effects", null, WorldEvent.YOU) }
        val refused = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.AI) }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty().contains(FxAsks.REFUSED), refused.toString())
        assertFalse(File(s.data, "effects/$HERALD.js").exists())
        // fx_request's offer adds nothing; Ai cannot make the go.
        val offer = withContext(Dispatchers.Main) { s.effects.offer("r1", "Example Herald's effect", "d1", listOf(HERALD_ALT, SQUIRE), null, null) }
        assertEquals(listOf(HERALD), offer.write, "an alternate artwork asks for its card")
        assertEquals(listOf(SQUIRE), offer.nothing)
        assertTrue(s.effects.asked.asks.isEmpty())
        assertNull(withContext(Dispatchers.Main) { s.effects.go(FxOffers.request(offer, 1), FxReviews.AI) })
        assertTrue(s.effects.asked.asks.isEmpty())
        // The person's Write: on the list, kept on disk.
        val go = assertNotNull(withContext(Dispatchers.Main) { s.effects.go(FxOffers.request(offer, 1).copy(from = FxFrom.VIEWER), FxReviews.PERSON) })
        assertEquals(listOf(HERALD), go.cards)
        assertEquals(FxFrom.VIEWER, s.effects.asked.of(HERALD)?.from)
        val price = Prices.of("anthropic", "claude-sonnet-5-5")
        withContext(Dispatchers.Main) { s.effects.begin("session-1", go, Usage(input = 1_000), "anthropic/claude-sonnet-5-5", price) }
        // Now Ai's write goes through, compiles, and the card is begun.
        val said = withContext(Dispatchers.Main) { s.worlds.write("lib/effects/$HERALD.js", heraldJs, WorldEvent.AI) }.getOrThrow()
        assertTrue("Compiled Example Herald" in said, said)
        assertEquals(FxStatus.UNTESTED, s.effects.status(HERALD))
        // Its check, in the session: what the rounds since the go cost is kept on the card.
        withContext(Dispatchers.Main) { s.effects.checked(HERALD, "session-1", Usage(input = 21_000, output = 4_000)) }
        val ask = assertNotNull(s.effects.asked.of(HERALD))
        assertEquals(FxAsks.WRITTEN, ask.state)
        assertEquals(24_000L, ask.tokens)
        assertEquals("written for 24,000 tokens, ≈ \$0.08", FxCost.spentWords(ask))
        // Another session's check, or a card not asked, costs nothing.
        withContext(Dispatchers.Main) { s.effects.checked(HERALD, "other", Usage(input = 99_000)) }
        assertEquals(24_000L, s.effects.asked.of(HERALD)?.tokens)
        // The pane: another deck asking for it (by its other printing) is told it is reused, at no cost.
        val again = withContext(Dispatchers.Main) { s.effects.offer("r2", "deck two's engine", "d2", listOf(HERALD_ALT), "anthropic/claude-sonnet-5-5", price) }
        assertEquals(listOf(HERALD), again.reused)
        assertTrue(again.toWrite.isEmpty())
        assertEquals(0.0, again.estimate.usd)
        // And the next estimate on this connection is its own measured figure.
        assertTrue(again.measured && again.perCard == 24_000L, again.toString())
        // The list is on disk, and read again with the library.
        var waited = 0
        while (!File(s.data, "effects/${FxPaths.ASKED}").readTextOrNull().orEmpty().contains("24000") && waited++ < 100) Thread.sleep(20)
        val fresh = Effects.under(s.data).also { it.pool = { index } }
        fresh.reloadNow()
        assertEquals(24_000L, fresh.asked.of(HERALD)?.tokens)
        assertNull(FxAsks.gate(fresh.asked, HERALD, WorldEvent.AI), "asked once, writable for its repairs")
    }

    @Test
    fun aSessionThatStopsLeavesItsCardsAskedNotStarted() = runBlocking {
        val s = setup()
        val r = FxRequest("r1", at = 1, from = FxFrom.PANE, what = "two cards", cards = listOf(HERALD, BROKEN))
        val go = assertNotNull(withContext(Dispatchers.Main) { s.effects.go(r, FxReviews.PERSON) })
        withContext(Dispatchers.Main) {
            s.effects.begin("session-2", go, Usage(), null, null)
            s.effects.ended("session-2")
        }
        assertEquals(FxAsks.NOT_STARTED, s.effects.asked.of(HERALD)?.state)
        assertEquals(FxAsks.NOT_STARTED, s.effects.asked.of(BROKEN)?.state)
        assertNull(s.effects.authoring)
    }

    private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()

    companion object {
        const val HERALD = 900_000_201
        const val HERALD_ALT = 900_000_202
        const val SQUIRE = 900_000_203
        const val BROKEN = 900_000_204
        const val NEWER = 900_000_205
    }
}
