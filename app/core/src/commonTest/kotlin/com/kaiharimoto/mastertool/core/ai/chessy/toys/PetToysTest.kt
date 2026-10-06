package com.kaiharimoto.mastertool.core.ai.chessy.toys

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetToysTest {
    private fun room() = PetRoom().apply {
        left = 0f; right = 2000f; top = 0f; floor = 800f
        headRise = 380f; headR = 220f; mouthRise = 240f; halfW = 300f; reach = 120f
        unit = 1f
    }

    private fun run(toys: PetToys, seconds: Float, every: (List<ToyEvent>) -> Unit = {}) {
        repeat((seconds * 60).toInt()) { every(toys.step(1f / 60f)) }
    }

    /** She sits still for [seconds] (a hand on her), so the toys can be watched alone. */
    private fun holdHer(toys: PetToys, seconds: Float) {
        repeat((seconds * 60).toInt()) { toys.her.touched(); toys.step(1f / 60f) }
    }

    @Test
    fun aThrownBallBouncesRollsAndRestsOnTheFloorInsideTheRoom() {
        val toys = PetToys(room())
        val y = toys.yarn
        y.out = true
        y.place(150f, 200f)
        y.release(2600f, -900f, toys.room, toys.random())
        var lowest = 0f
        val bounces = ArrayList<Float>()
        repeat(25 * 60) {
            toys.her.touched()
            toys.step(1f / 60f).filter { it.hit == ToyHit.BOUNCE }.forEach { bounces += it.strength }
            assertTrue(y.x >= y.radius - .01f && y.x <= 2000f - y.radius + .01f, "inside the walls: ${y.x}")
            lowest = maxOf(lowest, y.y)
        }
        assertTrue(lowest <= 800f - y.radius + .01f, "never through the floor")
        assertEquals(800f - y.radius, y.y, .5f)
        assertTrue(abs(y.vx) < 1f && abs(y.vy) < 1f, "at rest: ${y.vx}, ${y.vy}")
        assertTrue(y.w.length < .2, "spin ${y.w}")
        // its bounces are heard
        assertTrue(bounces.isNotEmpty() && bounces.all { it in .1f..1f }, "bounces $bounces")
    }

    @Test
    fun aBallRollsInFrontOfHerNotOffHer() {
        val toys = PetToys(room())
        holdHer(toys, .1f)
        val herX = toys.room.herX
        val y = toys.yarn
        y.out = true
        y.place(herX - 600f, 800f - y.radius)
        y.release(1500f, 0f, toys.room, toys.random())
        holdHer(toys, 1.2f)
        assertTrue(y.x > herX + 100f, "rolled on past her: ${y.x} against $herX")
    }

    @Test
    fun sheGoesToTheYarnAndBitesItAway() {
        val toys = PetToys(room())
        holdHer(toys, .1f)
        val y = toys.yarn
        y.out = true
        y.place(toys.room.herX + 450f, 800f - y.radius)
        val hits = ArrayList<ToyHit>()
        run(toys, 4f) { e -> e.forEach { hits += it.hit } }
        assertTrue(ToyHit.BIT in hits, "hits: $hits")
    }

    @Test
    fun sheChasesTheMousePouncesAndNowAndThenCatchesIt() {
        val toys = PetToys(room(), seed = 4)
        holdHer(toys, .1f)
        val m = toys.mouse
        m.out = true
        val hits = ArrayList<ToyHit>()
        var bounced = false
        repeat(8) {
            m.facing = 1f
            m.place(200f, 800f - m.height / 2f)
            m.wind()
            run(toys, 6f) { e ->
                e.forEach { hits += it.hit }
                if (m.vy < -500f) bounced = true
            }
        }
        assertTrue(ToyHit.NEAR in hits, "she spots it: $hits")
        assertTrue(ToyHit.POUNCE in hits, "she pounces: $hits")
        assertTrue(ToyHit.CAUGHT in hits, "she catches it now and then: $hits")
        assertTrue(bounced, "a catch bounces it")
        assertTrue(hits.count { it == ToyHit.CAUGHT } < hits.count { it == ToyHit.POUNCE }, "not every time")
    }

    @Test
    fun sheBitesAtAFeatherWavedInFrontOfHer() {
        val toys = PetToys(room(), seed = 2)
        holdHer(toys, .1f)
        val w = toys.wand
        val herX = toys.room.herX
        w.take(herX + 200f, 700f, toys.room)
        val hits = ArrayList<ToyHit>()
        var t = 0f
        repeat(8 * 60) {
            t += 1f / 60f
            w.hx = herX + 140f + kotlin.math.sin(t * 3f) * 60f
            w.hy = 760f
            toys.step(1f / 60f).forEach { hits += it.hit }
        }
        assertTrue(ToyHit.BIT in hits, "hits: $hits")
    }

    @Test
    fun leftAloneSheWandersAboutTheRoomAndStaysInIt() {
        val toys = PetToys(room(), seed = 9)
        toys.step(1f / 60f)
        val seen = HashSet<Int>()
        repeat(40 * 60) {
            toys.step(1f / 60f)
            val x = toys.room.herX
            assertTrue(x in 300f..1700f, "inside the room: $x")
            seen += (x / 100f).toInt()
        }
        assertTrue(seen.size >= 3, "she moved about: $seen")
    }

    @Test
    fun aHandOnHerHoldsHerStill() {
        val toys = PetToys(room())
        toys.step(1f / 60f)
        val x = toys.room.herX
        val y = toys.yarn
        y.out = true
        y.place(x + 300f, 800f - y.radius)
        holdHer(toys, 3f)
        assertEquals(x, toys.room.herX, .01f)
        assertEquals(PlayState.SIT, toys.her.state)
    }

    @Test
    fun sheWatchesTheToyInPlay() {
        val toys = PetToys(room())
        holdHer(toys, .1f)
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
        val again = amie.nip(20.0)
        assertTrue(again.mood != Expression.LOVE && amie.high(21.0) == 0f)
        assertTrue(amie.nipRefill(20.0) > 0.0)
        assertEquals(0.0, amie.nipRefill(1.0 + ChessyAmie.NIP_AGAIN))
    }

    @Test
    fun catnipSetsHerBouncingAbout() {
        val toys = PetToys(room())
        toys.step(1f / 60f)
        toys.her.silly = 1f
        var up = 0f
        run(toys, 4f) { up = maxOf(up, toys.her.hop) }
        assertEquals(PlayState.SILLY, toys.her.state)
        assertTrue(up > 20f, "she bounced: $up")
        toys.her.silly = 0f
        run(toys, 2f)
        assertTrue(toys.her.state != PlayState.SILLY)
    }

    @Test
    fun sheAnswersWhatSheDoesButNotTheSounds() {
        val amie = ChessyAmie(seed = 3)
        amie.greet(0.0)
        assertNotNull(amie.toy(ToyKind.YARN, ToyHit.BIT, 5.0))
        assertNotNull(amie.toy(ToyKind.MOUSE, ToyHit.CAUGHT, 10.0))
        assertNull(amie.toy(ToyKind.YARN, ToyHit.BOUNCE, 15.0))
        assertNull(amie.toy(null, ToyHit.LAND, 16.0))
        assertTrue(amie.fondness > 0f)
    }
}
