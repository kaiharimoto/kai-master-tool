package com.kaiharimoto.mastertool.core.duel.effects

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The red team's speed-ups keep the same answers (the memo-against-old rule of 1.0.92, D.md §5.7): on every table of the
 * seeded walks, what is worked out once — a table's refusals and restrictions, a book's unread effects, a material search
 * that stops at the first set it needs — equals what is worked out afresh.
 */
class FxMemoTest {
    private fun tables(): List<FxTable> {
        val out = ArrayList<FxTable>()
        for (seed in 1L..24L) FxWalker.walk(seed, steps = 60) { st -> out += st.before }
        return out
    }

    @Test
    fun theMovesAndRefusalsWorkedOutOnceAreTheOnesWorkedOutAfresh() {
        for (t in tables()) {
            for (seat in 0..1) {
                val fresh = { t.copy() } // a copy starts its memos afresh
                val once = FxEngine.moves(t, seat)
                assertEquals(FxEngine.moves(fresh(), seat), once)
                assertEquals(once, FxEngine.moves(t, seat), "asked again on the same table")
                t.state.cards.keys.sorted().forEach { uid ->
                    t.script(uid)?.effects?.forEach { e ->
                        assertEquals(FxEngine.refusal(fresh(), seat, uid, e.id), FxEngine.refusal(t, seat, uid, e.id), "$uid ${e.id}")
                    }
                }
            }
            assertEquals(FxRules.inForceNow(t.copy()), FxRules.inForce(t))
        }
    }

    @Test
    fun aMaterialSearchThatStopsAtTheFirstSetAnswersTheSame() {
        val ops = (FxRef.book.cards.mapNotNull { FxRef.book.script(it) }).flatMap { s -> s.effects.flatMap { FxWalk.steps(it) } }.map { it.op }
        val fusions = ops.filterIsInstance<Op.FusionSummon>()
        val rituals = ops.filterIsInstance<Op.RitualSummon>()
        for (t in tables()) {
            t.state.cards.keys.filter { t.script(it) != null }.forEach { uid ->
                val act = FxAct(t.state.active, uid, t.code(uid)!!, "e1", FxTag.RESOLVE)
                fusions.forEach { op -> assertEquals(FxSteps.fusions(t, act, op).isNotEmpty(), FxSteps.fusions(t, act, op, any = true).isNotEmpty()) }
                rituals.forEach { op -> assertEquals(FxSteps.rituals(t, act, op).isNotEmpty(), FxSteps.rituals(t, act, op, any = true).isNotEmpty()) }
            }
        }
    }

    @Test
    fun theProceduresListedWithoutEverySetAreTheOnesWithThem() {
        var open = 0
        for (t in tables()) {
            for (seat in 0..1) {
                t.state.cards.keys.sorted().forEach { uid ->
                    val listed = FxProcs.open(t, seat, uid)
                    assertEquals(FxProcs.options(t, seat, uid).map { it.index }, listed, "$uid")
                    open += listed.size
                }
            }
        }
        kotlin.test.assertTrue(open > 0, "the walks reach procedures to compare")
    }

    @Test
    fun aBooksUnreadEffectsAreTheWalksOwn() {
        FxRef.book.cards.forEach { code ->
            val s = FxRef.book.script(code)!!
            s.effects.forEach { e -> assertEquals(FxWalk.unread(e), FxRef.book.unread(code, e.id), "$code ${e.id}") }
            s.summon?.procs.orEmpty().forEachIndexed { i, p -> assertEquals(FxWalk.unread(p), FxRef.book.unreadProc(code, i)) }
        }
        val newer = CardScript(
            FxRef.CROSSROADS, name = "Example Crossroads",
            effects = listOf(Effect("e1", kind = Kind.ACTIVATION, does = listOf(Step(Op.Unknown()))), Effect("e2", kind = Kind.ACTIVATION)),
            summon = SummonRule(procs = listOf(Proc.Unknown(), Proc.Ritual)),
        )
        val b = FxRef.bookWith(newer)
        assertEquals(listOf(true, false), listOf(b.unread(FxRef.CROSSROADS, "e1"), b.unread(FxRef.CROSSROADS, "e2")))
        assertEquals(listOf(true, false), listOf(b.unreadProc(FxRef.CROSSROADS, 0), b.unreadProc(FxRef.CROSSROADS, 1)))
    }
}
