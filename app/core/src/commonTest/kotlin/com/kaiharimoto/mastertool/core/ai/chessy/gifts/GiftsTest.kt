package com.kaiharimoto.mastertool.core.ai.chessy.gifts

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.AmieZone
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie
import com.kaiharimoto.mastertool.core.ai.chessy.toys.PetRoom
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyEvent
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyHit
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GiftsTest {
    private val maliss = listOf(
        GiftCatalog.cardItem(1, "Maliss <P> Dormouse", "Effect Monster", "Cyberse", 0),
        GiftCatalog.cardItem(2, "Maliss <P> White Rabbit", "Effect Monster", "Cyberse", 1),
        GiftCatalog.cardItem(3, "Maliss <P> Chessy Cat", "Effect Monster", "Cyberse", 2),
        GiftCatalog.cardItem(4, "Maliss in Underground", "Spell Card", "Field", 3),
    )

    private fun room() = PetRoom().apply {
        left = 0f; right = 2000f; top = 0f; floor = 800f
        headRise = 380f; headR = 220f; mouthRise = 240f; halfW = 300f; reach = 120f
        unit = 1f
    }

    @Test
    fun theCatalogHoldsEverythingKaiAskedFor() {
        val c = GiftCatalog(maliss)
        assertTrue(GiftCatalog.NOTES.size >= 20, "notes: ${GiftCatalog.NOTES.size}")
        assertEquals(c.all.size, c.all.map { it.id }.toSet().size, "ids are unique")
        for (k in GiftKind.entries) assertTrue(c.ofKind(k).isNotEmpty(), "$k")
        for (i in c.all) {
            assertTrue(i.give.isNotBlank() && i.description.isNotBlank() && i.name.isNotBlank(), i.id)
            assertFalse(i.give.trimEnd().endsWith("!"), "her lines end in a kaomoji: ${i.give}")
        }
        // every note says something different, in at least three tiers
        assertEquals(GiftCatalog.NOTES.size, GiftCatalog.NOTES.map { it.words }.toSet().size)
        assertTrue(GiftCatalog.NOTES.map { it.tier }.toSet().size >= 3)
        // every gift line is its own
        assertEquals(c.all.size, c.all.map { it.give }.toSet().size)
    }

    @Test
    fun theDrawFollowsTheWeights() {
        val c = GiftCatalog(maliss)
        val r = Random(5)
        val n = 60_000
        val counts = HashMap<GiftKind, Int>()
        repeat(n) { c.roll(r, owned = { true }).kind.let { k -> counts[k] = (counts[k] ?: 0) + 1 } }
        val total = GiftCatalog.KIND_WEIGHT.values.sum()
        for ((k, w) in GiftCatalog.KIND_WEIGHT) {
            val share = counts.getValue(k).toDouble() / n
            assertEquals(w / total, share, .012, "$k")
        }
        // the rarest note is rarer than a common one
        val notes = HashMap<String, Int>()
        repeat(n) { val g = c.roll(r, owned = { true }); if (g.kind == GiftKind.NOTE) notes[g.id] = (notes[g.id] ?: 0) + 1 }
        assertTrue((notes["note:petting"] ?: 0) > (notes["note:forever"] ?: 0) * 4)
    }

    @Test
    fun whatYouDoNotHaveYetComesTwiceAsOftenAndHerOwnCardIsRarer() {
        val c = GiftCatalog(maliss)
        val r = Random(9)
        var heartOwned = 0
        var heartNew = 0
        repeat(40_000) { if (c.roll(r, owned = { true }).id == "heart") heartOwned++ }
        repeat(40_000) { if (c.roll(r, owned = { it != "heart" }).id == "heart") heartNew++ }
        assertTrue(heartNew > heartOwned * 1.4, "$heartOwned → $heartNew")
        val cards = HashMap<String, Int>()
        repeat(40_000) { val g = c.roll(r, owned = { true }); if (g.kind == GiftKind.CARD) cards[g.name] = (cards[g.name] ?: 0) + 1 }
        assertTrue(cards.getValue("Maliss <P> Chessy Cat") < cards.getValue("Maliss <P> Dormouse"))
        assertTrue("ME" in c.byId("card:3")!!.give)
    }

    @Test
    fun withNoMalissInThePoolNoCardIsDrawn() {
        val c = GiftCatalog()
        val r = Random(2)
        repeat(5_000) { assertTrue(c.roll(r).kind != GiftKind.CARD) }
    }

    @Test
    fun theCollectionCountsAndKeeps() {
        val c = GiftCatalog(maliss)
        var col = GiftCollection()
        assertEquals(0, col.progress(c).have)
        col = col.record("heart").record("heart").record("note:visits")
        assertEquals(2, col.times("heart"))
        val p = col.progress(c)
        assertEquals(2, p.have)
        assertEquals(c.all.size, p.of)
        assertEquals(3, p.received)
        assertEquals(1 to 1, p.perKind[GiftKind.HEART])
        assertEquals(mapOf("ok" to 2), GiftCollection.sanitised(mapOf("ok" to 2, "" to 3, "none" to 0)))
        // stored in Ai's settings, made safe on read
        assertEquals(mapOf("heart" to 1), AiPrefs(chessyGifts = mapOf("heart" to 1, "bad" to -2)).sanitised().chessyGifts)
    }

    @Test
    fun everySolidWindsOutward() {
        // the closed ones enclose a positive volume
        for ((name, m) in listOf("box" to GiftMeshes.boxBody, "card" to GiftMeshes.card.parts[0], "photo" to GiftMeshes.photo.parts[0],
            "heart" to GiftMeshes.heart.parts[0], "chest" to GiftMeshes.chestBody, "drawer" to GiftMeshes.chestDrawer)) {
            assertTrue(GiftMeshes.volume(m) > 0.0, "$name: ${GiftMeshes.volume(m)}")
        }
        // the cupcake's wrapper and frosting face away from its middle
        val cup = GiftMeshes.cupcake.parts[0]
        val sides = cup.faces.filter { it.mat == GiftMat.WRAPPER || it.mat == GiftMat.FROSTING }
        val outward = sides.count { f ->
            val n = cup.normal(f)
            val c = cup.centroid(f)
            n.x * c.x + n.z * c.z > -1e-9 || n.y < 0
        }
        assertTrue(outward >= sides.size * .95, "$outward of ${sides.size}")
        // the heart's front facets face the person, its back ones away
        val h = GiftMeshes.heart.parts[0]
        assertTrue(h.faces.filter { f -> h.centroid(f).z > .1 }.all { h.normal(it).z > 0 })
        assertTrue(h.faces.filter { f -> h.centroid(f).z < -.1 }.all { h.normal(it).z < 0 })
        // the card's picture is on its front, the right way up
        val card = GiftMeshes.card.parts[0]
        val front = card.faces.first { it.tex == GiftTex.CARD_FRONT }
        assertTrue(card.normal(front).z > 0)
        val (tl, tr) = card.v[front.idx[0]] to card.v[front.idx[1]]
        assertTrue(tl.x < tr.x && tl.y < card.v[front.idx[3]].y)
        // every solid is about a unit across, about its middle
        for (k in GiftKind.entries) {
            val m = GiftMeshes.of(k)
            assertTrue(m.hi.y - m.lo.y in .5..1.5 && abs(m.hi.y + m.lo.y) < .5, "$k ${m.lo} ${m.hi}")
        }
    }

    @Test
    fun aThrownGiftLandsInTheRoomAndSettlesFacingYou() {
        val room = room()
        val events = ArrayList<ToyEvent>()
        for (kind in GiftKind.entries) {
            val item = GiftCatalog(maliss).ofKind(kind).first()
            val g = GiftBody(item, GiftMeshes.of(kind), GiftMeshes.size(kind), GiftBody.restOf(kind))
            g.place(300f, 200f)
            g.release(2400f, -800f, room, Random(1))
            repeat(8 * 120) {
                g.step(1f / 120f, room, Random(1), events)
                assertTrue(g.x in 0f..2000f, "$kind inside the walls")
                assertTrue(g.y + g.lowest() <= 800.5f, "$kind never through the floor")
            }
            assertTrue(g.onFloor(room), "$kind on the floor")
            assertTrue(abs(g.vx) < 1f && abs(g.vy) < 1f, "$kind still")
            val d = g.q.w * g.restOrient().w + g.q.x * g.restOrient().x + g.q.y * g.restOrient().y + g.q.z * g.restOrient().z
            assertTrue(abs(d) > .97, "$kind settles to its rest: $d")
        }
        assertTrue(events.any { it.hit == ToyHit.BOUNCE })
    }

    private fun GiftBody.restOrient(): Quat = rest

    @Test
    fun theBoxIsMadeOpenedAndItsGiftGoesBackIntoTheChest() {
        val room = room()
        val play = GiftPlay()
        val r = Random(3)
        val events = ArrayList<ToyEvent>()
        play.chest.x = 1800f; play.chest.w = 170f; play.chest.h = 150f
        play.make(900f, room)
        assertTrue(play.making)
        val item = GiftCatalog(maliss).byId("cupcake")!!
        assertNull(play.open(item, room, r, events), "not while it is still being made")
        repeat(240) { play.step(1f / 120f, room, r, events) }
        assertFalse(play.making)
        val g = assertNotNull(play.open(item, room, r, events))
        assertNull(play.box)
        assertNotNull(play.lid)
        assertTrue(g.vy < 0f, "it jumps out")
        repeat(600) { play.step(1f / 120f, room, r, events) }
        assertNull(play.lid)
        assertNull(play.opened)
        // carried over the chest and let go: in it goes
        g.held = true
        g.x = 1800f; g.y = 700f
        repeat(30) { play.step(1f / 120f, room, r, events) }
        assertTrue(play.chest.drawer > .5f, "the drawer comes out under it")
        assertTrue(play.release(g, 0f, 0f, room, r, events))
        assertTrue(play.out.isEmpty())
        assertTrue(events.any { it.hit == ToyHit.STORED })
        // never more than three out: the oldest goes back by itself
        repeat(4) { play.takeOut(item, room, r, events); repeat(10) { play.step(1f / 120f, room, r, events) } }
        assertEquals(GiftPlay.MAX_OUT, play.out.size)
    }

    @Test
    fun fullHeartsMakeOneGiftThenSheRestsBeforeTheNext() {
        val amie = ChessyAmie(seed = 4)
        amie.greet(0.0)
        assertFalse(amie.giftDue(1.0))
        // a long petting fills her hearts
        var t = 1.0
        while (amie.fondness < 1f && t < 400) { amie.hold(AmieZone.HEAD, t); t += 1.0 }
        assertEquals(1f, amie.fondness)
        assertTrue(amie.giftDue(t))
        assertEquals(Expression.FOUND, amie.makeGift(t).mood)
        assertFalse(amie.giftDue(t + 1), "one at a time")
        val r = amie.gave(GiftCatalog.KEEPSAKES[0], t + 3)
        assertEquals(GiftCatalog.KEEPSAKES[0].give, r.line)
        assertEquals(ChessyAmie.GIFT_AFTER, amie.fondness)
        assertFalse(amie.giftDue(t + 10))
    }

    @Test
    fun playingWithToysFillsTheHeartsAndMakesAGift() {
        val amie = ChessyAmie(seed = 5)
        amie.greet(0.0)
        var t = 1.0
        // throws, waves and winds alone, once a second
        while (amie.fondness < 1f && t < 200) { amie.played(t); t += 1.0 }
        assertEquals(1f, amie.fondness)
        assertTrue(amie.giftDue(t), "toy play alone earns her gift")
    }

    @Test
    fun aLongWaveIsThrottledAndCatnipDoesNotStopPlayCounting() {
        val amie = ChessyAmie(seed = 5)
        amie.greet(0.0)
        // sixty calls in half a second count once
        repeat(60) { amie.played(1.0 + it / 120.0) }
        assertEquals(ChessyAmie.PLAY_WARM, amie.fondness, 1e-6f)
        amie.nip(3.0)
        val before = amie.fondness
        assertTrue(amie.high(5.0) > 0f)
        assertTrue(amie.played(5.0))
        assertNull(amie.toy(com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyKind.YARN, ToyHit.BIT, 5.5), "silly, no words")
        assertTrue(amie.fondness > before + ChessyAmie.PLAY_WARM, "a bite still warms her while catnip has her")
    }

    @Test
    fun aGiftInHandIsTalkedAboutAndBroughtOutAlwaysIs() {
        val amie = ChessyAmie(seed = 6)
        amie.greet(0.0)
        val heart = GiftCatalog.KEEPSAKES.first { it.kind == GiftKind.HEART }
        val held = assertNotNull(amie.admired(heart, 5.0))
        assertTrue(held.line in ChessyAmie.LINES.getValue("gift-held:heart"))
        assertNull(amie.admired(heart, 9.0), "not the same gift again so soon")
        val out = assertNotNull(amie.admired(heart, 9.5, broughtOut = true), "brought out of the drawer, always answered")
        assertTrue(out.line in ChessyAmie.LINES.getValue("gift-out:heart"))
        // her own card is about her; another card names itself; a note can quote itself
        val self = assertNotNull(amie.admired(maliss[2], 30.0))
        assertTrue(self.line in ChessyAmie.LINES.getValue("gift-held:self"))
        val card = assertNotNull(amie.admired(maliss[0], 50.0, broughtOut = true))
        assertFalse("{name}" in card.line)
        val note = GiftCatalog.NOTES.first()
        repeat(8) { assertFalse("{words}" in assertNotNull(amie.admired(note, 100.0 + it * 20, broughtOut = true)).line) }
        for (kind in GiftKind.entries) for (how in listOf("held", "out")) assertTrue(ChessyAmie.LINES.getValue("gift-$how:${kind.name.lowercase()}").size >= 3)
    }
}
