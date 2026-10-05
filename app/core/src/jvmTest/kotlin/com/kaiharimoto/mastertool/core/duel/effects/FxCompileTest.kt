package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.world.JsRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compiling a card's effect on the real Rhino (Phase D step 2, D.md §3.2): the builder's output decodes into the script it
 * means; a function placed in the data is refused, never dropped in silence; the 5-second budget holds; a builder's mistake
 * names the script's own line. The cards are FxRef's fictional ones (900000000–900000999).
 */
class FxCompileTest {
    private val host = FxHost.of(FxRef.cards)

    private fun compile(source: String, passcode: Int = FxRef.SCOUT, files: Map<String, String> = emptyMap()): FxCompile.Outcome =
        FxCompile.compile(passcode, source, FxHost.of(FxRef.cards) { files[it] })

    private fun compiled(source: String, passcode: Int = FxRef.SCOUT, files: Map<String, String> = emptyMap()): FxCompile.Outcome.Compiled {
        val o = compile(source, passcode, files)
        return assertIs<FxCompile.Outcome.Compiled>(o, (o as? FxCompile.Outcome.Failed)?.why)
    }

    /** D.md §2.5½'s example, as the doc writes it, labels added. */
    private val example = """
        fx.card(900000001, {
          effects: [
            fx.trigger('e1', { label: 'Search', on: fx.on.summoned('normal', 'special'), optional: true, opt: fx.opt.byName(),
              does: [ fx.add({ from: 'your deck', where: fx.all(fx.nameHas('Example'), fx.monster(), fx.level(1, 4)) }) ] }),
            fx.quick('e2', { label: 'Bounce', from: ['gy'], opt: fx.opt.byName(), condition: fx.cond.theirTurn(),
              cost: [ fx.banish({ ref: 'self' }) ],
              targets: [ fx.pick({ from: 'their monsters', where: fx.faceUp(), bind: 't' }) ],
              does: [ fx.returnTo('hand', { ref: 't' }) ] }),
          ],
        })
    """.trimIndent()

    @Test
    fun theDocsExampleCompilesToTheReferenceScript() {
        val c = compiled(example)
        val ref = FxRef.script(FxRef.SCOUT)
        assertEquals(ref.effects, c.script.effects, "the builder writes what the reference card says")
        // Made whole from the pool and the source: the name, the printed text's hash, the source's.
        assertEquals("Example Scout", c.script.name)
        assertEquals(FxCodec.textOf(FxRef.card(FxRef.SCOUT).description), c.script.text)
        assertEquals(FxCodec.short(example), c.script.source)
        // The file keeps its vocabulary written out, reads back, and the source's hash is no part of the script's own.
        assertTrue(c.json.startsWith("{\"card\":900000001,\"vocab\":1"), c.json)
        val back = assertNotNull(FxCodec.decode(c.json))
        assertEquals(c.script, back)
        assertEquals(FxCodec.hash(c.script.copy(source = "")), FxCodec.hash(back))
        // And it checks clean against its card.
        assertTrue(FxCheck.check(back, FxRef.card(FxRef.SCOUT), FxRef.facts::canonical, FxRef.SCOUT).errors.isEmpty())
    }

    @Test
    fun aFunctionPlacedInTheDataIsRefused() {
        val planted = "var c = $example;\nc.effects[0].does.push({ op: function () { return 1; } });\nc"
        val f = assertIs<FxCompile.Outcome.Failed>(compile(planted))
        assertTrue("function" in f.why && "effects[0].does[1].op" in f.why, f.why)
        // A getter is code too.
        val getter = "var c = $example;\nObject.defineProperty(c, 'notes', { get: function () { return 'x'; }, enumerable: true });\nc"
        val g = assertIs<FxCompile.Outcome.Failed>(compile(getter))
        assertTrue("getter" in g.why && "notes" in g.why, g.why)
        // A builder handed a function where it wants data fails too, at its own line.
        val handed = assertIs<FxCompile.Outcome.Failed>(compile("var e = 1;\nfx.card(900000001, {\n  effects: [ fx.ignition('e1', { from: 'monsters', does: [ function () {} ] }) ]\n})"))
        assertTrue("fx.ignition e1 does[0]" in handed.why && "line 2" in handed.why, handed.why)
        // A cycle never hangs the walk.
        val cycle = assertIs<FxCompile.Outcome.Failed>(compile("var c = $example;\nc.self = c;\nc"))
        assertTrue("cycle" in cycle.why, cycle.why)
    }

    @Test
    fun theBudgetHolds() {
        val f = assertIs<FxCompile.Outcome.Failed>(compile("fx.card(900000001, {});\nwhile (true) {}"))
        assertTrue(f.why.startsWith("Stopped"), f.why)
        assertTrue(f.ms < FxCompile.MILLIS + JsRuntime.GRACE + 1_000, "stopped in ${f.ms} ms")
        // Memory the count cannot see is guarded too.
        val big = assertIs<FxCompile.Outcome.Failed>(compile("var s = 'x'.repeat(100000000); fx.card(900000001, {})"))
        assertTrue("5,000,000" in big.why, big.why)
        // And a source over 64 KB is refused before it runs.
        val long = assertIs<FxCompile.Outcome.Failed>(compile("// " + "x".repeat(70_000) + "\nfx.card(900000001, {})"))
        assertTrue("64 KB" in long.why, long.why)
    }

    @Test
    fun aMistakeFailsAtTheLineThatMadeIt() {
        // Rhino counts lines by statement: a mistake is named by the statement's first line, and by the builder and the
        // effect it was building.
        val f = assertIs<FxCompile.Outcome.Failed>(compile("var search = fx.trigger('e1', { on: fx.on.summoned(), does: [] });\n\nvar bad = fx.add({ frm: 'your deck' });\nfx.card(900000001, { effects: [search] })"))
        assertTrue("fx.add" in f.why && "frm" in f.why && "line 3" in f.why, f.why)
        val word = assertIs<FxCompile.Outcome.Failed>(compile("var x = 1;\nfx.card(900000001, {\n  effects: [ fx.ignition('e1', { from: 'pocket' }) ],\n})"))
        assertTrue("pocket" in word.why && "fx.ignition from" in word.why && "line 2" in word.why, word.why)
    }

    @Test
    fun theLastValueMustBeTheCard() {
        val f = assertIs<FxCompile.Outcome.Failed>(compile("var c = fx.card(900000001, {}); 'done'"))
        assertTrue("must end with fx.card" in f.why, f.why)
        val newer = assertIs<FxCompile.Outcome.Failed>(compile("var c = fx.card(900000001, {}); c.vocab = 99; c"))
        assertTrue("vocabulary 99" in newer.why, newer.why)
    }

    @Test
    fun aHelperIsLoadedWithUseAndCompilesIntoNoCardOfItsOwn() {
        val helper = "function exampleSearch(id) { return fx.trigger(id, { label: 'Search', on: fx.on.summoned('normal', 'special'), optional: true, opt: fx.opt.byName(), " +
            "does: [ fx.add({ from: 'your deck', where: fx.all(fx.nameHas('Example'), fx.monster(), fx.level(1, 4)) }) ] }); }"
        val src = "ygo.use('lib/effects/_example.js');\nfx.card(900000001, { effects: [ exampleSearch('e1') ] })"
        val c = compiled(src, files = mapOf("lib/effects/_example.js" to helper))
        assertEquals(FxRef.script(FxRef.SCOUT).effects.first(), c.script.effects.single())
        assertTrue(FxPaths.helper("_example.js") && FxPaths.sourceOf("_example.js") == null)
    }

    @Test
    fun aSpellsActivationFromNowhereIsActivatedWhereItsKindIs() {
        val c = compiled("fx.card(${FxRef.CALL}, { effects: [ fx.activation('e1', { does: [ fx.draw(1) ] }) ] })", FxRef.CALL)
        assertEquals(setOf(Where.HAND, Where.SPELL_ZONE), c.script.effects.single().from)
        val trap = compiled("fx.card(${FxRef.SNARE}, { effects: [ fx.activation('e1', { does: [ fx.draw(1) ] }) ] })", FxRef.SNARE)
        assertEquals(setOf(Where.SPELL_ZONE), trap.script.effects.single().from)
        val field = compiled("fx.card(${FxRef.GROUNDS}, { effects: [ fx.activation('e1', { does: [ fx.lp('you', 100) ] }) ] })", FxRef.GROUNDS)
        assertEquals(FxRef.script(FxRef.GROUNDS).effects.single().copy(label = ""), field.script.effects.single())
    }

    @Test
    fun printedLinesAreKeptForThePerson() {
        val c = compiled("print('writing Scout');\n$example")
        assertEquals("writing Scout", c.printed.trim())
        assertTrue(host.cardById(FxRef.SCOUT_ALT)?.id?.value == FxRef.SCOUT, "an alternate artwork is its card")
    }
}
