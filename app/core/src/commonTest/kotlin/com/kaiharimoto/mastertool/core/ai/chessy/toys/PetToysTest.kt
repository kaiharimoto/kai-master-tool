package com.kaiharimoto.mastertool.core.ai.chessy.toys

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.AmieLove
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetToysTest {
    private fun room() = PetRoom().apply {
        left = 0f; right = 1200f; top = 0f; floor = 800f
        headX = 600f; headY = 380f; headR = 220f
        bellX = 600f; bellY = 690f; bellR = 40f
        unit = 1f
    }

    private fun run(toys: PetToys, seconds: Float, every: (List<ToyEvent>) -> Unit = {}) {
        repeat((seconds * 60).toInt()) { every(toys.step(1f / 60f)) }
    }

    @Test
    fun aThrownBallBouncesRollsAndRestsOnTheFloorInsideTheRoom() {
        val toys = PetToys(room())
        val y = toys.yarn
        y.out = true
        y.place(150f, 200f)
        y.release(2600f, -900f, toys.room, toys.random())
        var lowest = 0f
        run(toys, 25f) {
            assertTrue(y.x >= y.radius - .01f && y.x <= 1200f - y.radius + .01f, "inside the walls: ${y.x}")
            lowest = maxOf(lowest, y.y)
        }
        assertTrue(lowest <= 800f - y.radius + .01f, "never through the floor")
        assertEquals(800f - y.radius, y.y, .5f)
        assertTrue(abs(y.vx) < 1f && abs(y.vy) < 1f, "at rest: ${y.vx}, ${y.vy}")
        // its spin is its roll's: nothing left over once it stopped
        assertTrue(y.w.length < .2, "spin ${y.w}")
    }

    @Test
    fun aBallThrownAtHerHeadBonksIt() {
        val toys = PetToys(room())
        val y = toys.yarn
        y.out = true
        y.place(150f, 380f)
        y.release(2400f, -40f, toys.room, toys.random())
        val hits = ArrayList<ToyHit>()
        run(toys, 1.5f) { e -> e.forEach { hits += it.hit } }
        assertTrue(ToyHit.HEAD in hits, "hits: $hits")
        assertTrue(y.x < 600f, "bounced back off her: ${y.x}")
    }

    @Test
    fun aBallLeftByHerPawsIsBattedAway() {
        val toys = PetToys(room())
        val y = toys.yarn
        y.out = true
        y.place(520f, 800f - y.radius)
        val hits = ArrayList<ToyHit>()
        run(toys, 3f) { e -> e.forEach { hits += it.hit } }
        assertTrue(ToyHit.BATTED in hits, "hits: $hits")
    }

    @Test
    fun aWoundMouseRunsUnderHerNoseIsPouncedOnAndRunsDown() {
        val toys = PetToys(room())
        val m = toys.mouse
        m.out = true
        m.facing = 1f
        m.place(200f, 800f - m.height / 2f)
        m.wind()
        val hits = ArrayList<ToyHit>()
        run(toys, 8f) { e -> e.forEach { hits += it.hit } }
        assertTrue(ToyHit.NEAR in hits && ToyHit.POUNCED in hits, "hits: $hits")
        assertEquals(0f, m.wound)
        assertTrue(!m.moving, "run down and still")
        // on its feet again
        assertTrue(abs(abs(m.q.w) + abs(m.q.y) - 1.0) < .05, "upright: ${m.q}")
    }

    @Test
    fun aFeatherWavedInHerFaceIsSwatted() {
        val toys = PetToys(room())
        val w = toys.wand
        w.take(600f, 700f, toys.room)
        val hits = ArrayList<ToyHit>()
        var t = 0f
        repeat(240) {
            t += 1f / 60f
            // a quick wiggle under her chin
            w.hx = 600f + kotlin.math.sin(t * 18f) * 160f
            w.hy = 640f
            toys.step(1f / 60f).forEach { hits += it.hit }
        }
        assertTrue(ToyHit.SWATTED in hits, "hits: $hits")
    }

    @Test
    fun sheWatchesTheToyInPlay() {
        val toys = PetToys(room())
        assertNull(toys.focus())
        toys.yarn.out = true
        toys.yarn.place(100f, 100f)
        toys.yarn.release(1500f, 0f, toys.room, toys.random())
        toys.step(1f / 60f)
        assertNotNull(toys.focus())
    }

    @Test
    fun catnipMakesHerSillyThenSleepyAndOnlyNowAndThen() {
        val amie = ChessyAmie(seed = 3)
        amie.greet(0.0)
        val r = amie.nip(1.0)
        assertEquals(Expression.LOVE, r.mood)
        assertTrue(amie.high(3.0) == 1f && amie.wobble(3.3) != 0f)
        val silly = (2..24).mapNotNull { amie.idle(1.0 + it * .5) }
        assertTrue(silly.size >= 3, "silly lines: ${silly.size}")
        assertEquals(Expression.SLEEPING, silly.last().mood)
        assertEquals(0f, amie.high(13.0))
        // again too soon: she asks for a break, and is not silly
        val again = amie.nip(20.0)
        assertTrue(again.mood != Expression.LOVE && amie.high(21.0) == 0f)
        assertTrue(amie.nipRefill(20.0) > 0.0)
        assertEquals(0.0, amie.nipRefill(1.0 + ChessyAmie.NIP_AGAIN))
        assertTrue(amie.loves[AmieLove.CATNIP.ordinal] >= 1)
    }

    @Test
    fun toysAreAmongHerFavouriteThings() {
        val amie = ChessyAmie(seed = 3)
        amie.greet(0.0)
        assertEquals(0, amie.found)
        assertNotNull(amie.toy(ToyKind.YARN, ToyHit.BATTED, 5.0))
        assertNotNull(amie.toy(ToyKind.MOUSE, ToyHit.POUNCED, 10.0))
        assertEquals(2, amie.found)
        assertTrue(amie.fondness > 0f)
    }
}
