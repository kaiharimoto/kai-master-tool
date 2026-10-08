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
    private val vocab = MapperVocab(main)

    private fun records(hand: List<Int>): Pair<MapSearch.Mapped, List<String>> {
        val deal = MapDeal(hand)
        val m = MapSearch(kit, trace = true).map(deal.table(main, emptyList(), kit))
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
    fun everyRecordHasTheContractsShape() {
        val (_, lines) = records(listOf(CALLER, WALL))
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            assertEquals(1, o.getValue("v").jsonPrimitive.int)
            val moves = o.getValue("moves").jsonArray
            val policy = o.getValue("policy").jsonArray.map { it.jsonPrimitive.double }
            assertEquals(moves.size, policy.size)
            assertTrue(moves.size >= 2, "a record is a decision")
            assertEquals(1.0, policy.sum(), 1e-9)
            moves.forEach { m ->
                val (kind, card, _) = m.jsonArray.map { it.jsonPrimitive.int }
                assertTrue(kind in TrainingExport.MoveKind.entries.indices)
                assertTrue(card in 0 until vocab.cards.size + 2)
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
    fun aMoveThatReachesMoreKindsOfBoardWeighsMore() {
        val (_, lines) = records(listOf(CALLER))
        val root = Json.parseToJsonElement(lines.first()).jsonObject
        val moves = root.getValue("moves").jsonArray.map { m -> m.jsonArray.map { it.jsonPrimitive.int } }
        val policy = root.getValue("policy").jsonArray.map { it.jsonPrimitive.double }
        val activate = moves.indexOfFirst { it[0] == TrainingExport.MoveKind.ACTIVATE.ordinal }
        val end = moves.indexOfFirst { it[0] == TrainingExport.MoveKind.PHASE_END.ordinal }
        // Activating the Caller reaches the Frog board and the Sage board (two cells); ending now reaches one.
        assertTrue(policy[activate] > policy[end])
    }
}
