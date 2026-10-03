package com.kaiharimoto.mastertool.core.duel.dice

import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.layout.FormFactor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The dice on the drawn table (1.0.87): each seat's arena on its own field, the far one turned, the table drawn true. */
class DiceStageTest {
    private val sizes = listOf(1920f to 1080f, 1280f to 800f, 2560f to 1440f, 412f to 915f, 915f to 412f)

    @Test
    fun eachArenaLiesOnItsSeatsField() {
        sizes.forEach { (w, h) ->
            val form = if (minOf(w, h) < 600f) FormFactor.PHONE else FormFactor.DESK
            val l = DuelLayouter.solve(w, h, twoSided = true, form = form)
            val stage = DiceStage(l)
            for (seat in 0..1) {
                val a = assertNotNull(stage.arena(seat))
                assertEquals(seat != l.bottom, a.turned)
                val corners = listOf(V3(0.0, 0.0), V3(DiceSim.ARENA_W, 0.0), V3(0.0, DiceSim.ARENA_D), V3(DiceSim.ARENA_W, DiceSim.ARENA_D)).map { stage.toTable(seat, it) }
                // Inside the table's field block, on the seat's own side of the shared row.
                val mid = l.field.top + l.field.height / 2f
                corners.forEach { p ->
                    assertTrue(p.x >= l.field.left - 1 && p.x <= l.field.right + 1, "x ${p.x} at $w×$h")
                    if (seat == l.bottom) assertTrue(p.y >= mid - 1, "near y ${p.y} above the middle at $w×$h")
                    else assertTrue(p.y <= mid + 1, "far y ${p.y} below the middle at $w×$h")
                }
                // The dice rest in front of the field: past its near edge, toward the seat's player.
                val rest = stage.toTable(seat, a.rest.first())
                val edge = stage.toTable(seat, V3(DiceSim.ARENA_W / 2, DiceSim.ARENA_D))
                if (seat == l.bottom) assertTrue(rest.y > edge.y) else assertTrue(rest.y < edge.y)
            }
        }
    }

    @Test
    fun theTableIsDrawnWhereItIsAndWhatIsAboveItLeansAway() {
        val l = DuelLayouter.solve(1920f, 1080f, twoSided = true)
        val stage = DiceStage(l)
        val ground = stage.toTable(0, V3(10.0, 4.0, 0.0))
        val seen = stage.project(ground)
        assertEquals(ground.x.toFloat(), seen.x, 1e-3f)
        assertEquals(ground.y.toFloat(), seen.y, 1e-3f)
        assertEquals(1f, seen.k, 1e-6f)
        // Lifted, it is drawn larger, and farther from the eye's foot.
        val up = stage.project(stage.toTable(0, V3(10.0, 4.0, 2.0)))
        assertTrue(up.k > 1f)
        assertTrue(abs(up.y - stage.eye.y.toFloat()) > abs(seen.y - stage.eye.y.toFloat()))
        // A die's side toward the person shows; the far one does not.
        assertTrue(stage.faces(stage.toTable(0, V3(10.0, 4.5, 0.5)), V3(0.0, 1.0, 0.0)))
        assertTrue(!stage.faces(stage.toTable(0, V3(10.0, 3.5, 0.5)), V3(0.0, -1.0, 0.0)))
    }

    @Test
    fun thePointerFindsTheArenaPointUnderIt() {
        val l = DuelLayouter.solve(1920f, 1080f, twoSided = true)
        val stage = DiceStage(l)
        for (seat in 0..1) {
            val p = V3(7.5, 3.25, DiceThrow.HELD)
            val drawn = stage.project(stage.toTable(seat, p))
            val back = stage.under(seat, drawn.x, drawn.y, DiceThrow.HELD)
            assertEquals(p.x, back.x, 1e-3)
            assertEquals(p.y, back.y, 1e-3)
            // A fling up the screen goes away from the near seat, toward the far one's player for the far seat.
            val v = stage.velocity(seat, 0f, -1000f, DiceThrow.HELD)
            if (seat == l.bottom) assertTrue(v.y < 0) else assertTrue(v.y > 0)
        }
    }
}
