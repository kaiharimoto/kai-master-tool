package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The training data's contract (M.md §4.3, `tools/mapper-train/README.md`): every record has the shape the trainer reads, its
 * policy sums to one over its moves, and its value is what the map measured — the most of each trait still reachable.
 */
class TrainingExportTest {
    private val kit = GoldfishFixtures.kit()
    private val main = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val vocab = MapperVocab.of(main)

    private fun records(hand: List<Int>, budget: Int = MapSearch.DEFAULT_BUDGET): Pair<MapSearch.Mapped, List<String>> {
        val deal = MapDeal(hand)
        val m = MapSearch(kit, budget = budget, trace = true).map(deal.table(main, emptyList(), kit))
        return m to TrainingExport.records(deal, m, vocab)
    }

    @Test
    fun theVocabularyPadsThenUnknownsThenTheDeck() {
        val v = Json.parseToJsonElement(vocab.json()).jsonObject
        val cards = v.getValue("cards").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(0, -1) + main.distinct().sorted(), cards)
        assertEquals(cards.indexOf(SAGE), vocab.of(SAGE))
        assertEquals(MapperVocab.UNKNOWN, vocab.of(123))
        assertEquals(MapperVocab.UNKNOWN, vocab.of(null))
        assertEquals(16, vocab.hash().length)
    }

    @Test
    fun theVocabularyOnlyGrows() {
        // The deck lost its Elders and gained a Pond Net: every old index stays, the Net takes the next one.
        val next = MapperVocab.of(main.filter { it != ELDER } + GoldfishFixtures.NET, previous = vocab.cards)
        vocab.cards.forEach { c -> assertEquals(vocab.of(c), next.of(c), "card $c kept its index") }
        assertEquals(vocab.cards.size + 2, next.of(GoldfishFixtures.NET))
        assertEquals(next.cards, MapperVocab.read(next.json()))
        assertEquals(listOf("interruptions", "bodies", "through:ash"), TrainingExport.grownHeads(listOf("bodies", "through:ash", "interruptions"), listOf("interruptions", "bodies")))
    }

    @Test
    fun everyRecordHasTheContractsShape() {
        val (_, lines) = records(listOf(CALLER, WALL))
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            assertEquals(1, o.getValue("v").jsonPrimitive.int)
            assertEquals("${listOf(CALLER, WALL).sorted().joinToString(".")}/1st", o.getValue("hand").jsonPrimitive.content)
            assertEquals("none", o.getValue("prior").jsonPrimitive.content)
            assertTrue(o.getValue("pos").jsonPrimitive.content.isNotEmpty())
            val moves = o.getValue("moves").jsonArray
            val policy = o.getValue("policy").jsonArray.map { it.jsonPrimitive.double }
            assertEquals(moves.size, policy.size)
            assertTrue(moves.size >= 2, "a record is a decision")
            assertEquals(1.0, policy.sum(), 1e-9)
            moves.forEach { m ->
                val row = m.jsonArray.map { it.jsonPrimitive.int }
                assertEquals(4, row.size, "a move is [kind, card, effect, zone]")
                val (kind, card, _, zone) = row
                assertTrue(kind in TrainingExport.MoveKind.entries.indices)
                assertTrue(card in 0 until vocab.cards.size + 2)
                assertTrue(zone in TrainingExport.Zone.entries.indices)
            }
            o.getValue("tokens").jsonArray.forEach { t ->
                val (card, zone, owner, face, pos) = t.jsonArray.map { it.jsonPrimitive.int }
                assertTrue(card in 1 until vocab.cards.size + 2)
                assertTrue(zone in TrainingExport.Zone.entries.indices)
                assertTrue(owner in 0..1 && face in 0..1 && pos in 0..2)
            }
            assertTrue(o.getValue("value").jsonObject.keys.containsAll(BoardTraits.HEADS))
        }
    }

    @Test
    fun theRootsValueIsTheMostOfEachTraitAnyEndHas() {
        val (m, lines) = records(listOf(CALLER, WALL))
        val root = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals(0, root.getValue("step").jsonPrimitive.int)
        BoardTraits.HEADS.forEach { h ->
            val best = m.ends.maxOf { it.traits[h]!! }
            assertEquals(best, root.getValue("value").jsonObject.getValue(h).jsonPrimitive.double, "head $h")
        }
    }

    @Test
    fun theSameMapWritesTheSameLines() {
        assertEquals(records(listOf(CALLER, WALL)).second, records(listOf(CALLER, WALL)).second)
    }

    @Test
    fun thePolicyPointsAtTheFrontAndNothingElse() {
        val (_, lines) = records(listOf(CALLER))
        val root = Json.parseToJsonElement(lines.first()).jsonObject
        val moves = root.getValue("moves").jsonArray.map { m -> m.jsonArray.map { it.jsonPrimitive.int } }
        val policy = root.getValue("policy").jsonArray.map { it.jsonPrimitive.double }
        val activate = moves.indexOfFirst { it[0] == TrainingExport.MoveKind.ACTIVATE.ordinal }
        val end = moves.indexOfFirst { it[0] == TrainingExport.MoveKind.PHASE_END.ordinal }
        // Calling the Sage is the one board nothing beats (a negate); ending with the Caller in hand is beaten by it, so it
        // gets nothing however many boards it reaches.
        assertEquals(1.0, policy[activate], 1e-9)
        assertEquals(0.0, policy[end], 1e-9)
    }

    @Test
    fun anIncompleteMapWritesNothing() {
        val (m, lines) = records(listOf(CALLER, WALL), budget = 3)
        assertTrue(!m.complete)
        assertTrue(lines.isEmpty())
    }
}
