package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.duel.effects.FxCodec
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

    companion object {
        const val HERALD = 900_000_201
        const val HERALD_ALT = 900_000_202
        const val SQUIRE = 900_000_203
        const val BROKEN = 900_000_204
        const val NEWER = 900_000_205
    }
}
