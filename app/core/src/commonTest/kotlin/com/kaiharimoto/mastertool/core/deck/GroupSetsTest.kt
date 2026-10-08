package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GroupSetsTest {

    private val ash = CardId(14558127)
    private val fiend = CardId(60764609)

    private val roles = DeckGroups.EMPTY
        .upsert(DeckGroup("g1", "Engine", color = 2, order = 0))
        .assign(fiend, "g1")
    private val combo = DeckGroups.EMPTY
        .upsert(DeckGroup("g2", "Handtraps", color = 3, order = 0))
        .assign(ash, "g2")

    @Test
    fun aDeckStartsPlainWithOneSetInUse() {
        val sets = GroupSets.PLAIN
        assertTrue(sets.isPlain)
        assertEquals(GroupSets.FIRST_ID, sets.current.id)
        assertFalse(sets.rename(sets.current.id, "Roles").isPlain)
    }

    @Test
    fun aNewSetStartsEmptyAndKeepsTheOneLeft() {
        val (sets, live) = GroupSets.PLAIN.add(roles, copy = false)
        assertEquals(DeckGroups.EMPTY, live)
        assertEquals("Set 2", sets.current.name)
        assertEquals(roles, sets.byId(GroupSets.FIRST_ID)!!.groups)
        assertEquals(2, sets.sets.size)
    }

    @Test
    fun aCopyStartsFromTheGroupsAsTheyStand() {
        val named = GroupSets.PLAIN.rename(GroupSets.FIRST_ID, "Roles")
        val (sets, live) = named.add(roles, copy = true)
        assertEquals(roles, live)
        assertEquals("Roles copy", sets.current.name)
        val (again, _) = sets.switchTo(GroupSets.FIRST_ID, live).first.add(roles, copy = true)
        assertEquals("Roles copy 2", again.current.name)
    }

    @Test
    fun switchingStashesTheLiveGroupsAndHandsBackTheChosen() {
        val (two, _) = GroupSets.PLAIN.add(roles, copy = false)
        // The second set gets its own groups, then the first is chosen again.
        val (back, live) = two.switchTo(GroupSets.FIRST_ID, combo)
        assertEquals(roles, live)
        assertEquals(GroupSets.FIRST_ID, back.current.id)
        val (forth, live2) = back.switchTo(two.current.id, live)
        assertEquals(combo, live2)
        assertEquals(two.current.id, forth.current.id)
        // Choosing the set in use changes nothing.
        assertEquals(forth to combo, forth.switchTo(forth.current.id, combo))
        // Nor does a set that is not there.
        assertEquals(forth to combo, forth.switchTo("nope", combo))
    }

    @Test
    fun theLastSetIsNeverRemovedAndRemovingTheOneInUsePutsItsNeighbourInUse() {
        assertEquals(GroupSets.PLAIN to roles, GroupSets.PLAIN.remove(GroupSets.FIRST_ID, roles))
        val (two, _) = GroupSets.PLAIN.add(roles, copy = false)
        val (one, live) = two.remove(two.current.id, combo)
        assertEquals(listOf(GroupSets.FIRST_ID), one.sets.map { it.id })
        assertEquals(roles, live)
        // Removing one not in use leaves the live groups alone.
        val (kept, still) = two.remove(GroupSets.FIRST_ID, combo)
        assertEquals(combo, still)
        assertEquals(two.current.id, kept.current.id)
    }

    @Test
    fun idsAndNamesStayUnique() {
        var sets = GroupSets.PLAIN
        var live = DeckGroups.EMPTY
        repeat(3) { sets.add(live, copy = false).let { (s, l) -> sets = s; live = l } }
        sets = sets.remove("s2", live).first
        val (more, _) = sets.add(live, copy = false)
        assertEquals(more.sets.size, more.sets.map { it.id }.toSet().size)
        assertEquals(more.sets.size, more.sets.map { it.name }.toSet().size)
        // A blank rename is refused.
        assertEquals(more, more.rename(more.current.id, "   "))
    }

    @Test
    fun moveReordersTheMenu() {
        val (two, _) = GroupSets.PLAIN.add(roles, copy = false)
        assertEquals(listOf("s2", "s1"), two.move("s2", 0).sets.map { it.id })
    }

    // ---- the payload ------------------------------------------------------

    @Test
    fun aDeckWithoutSetsWritesNoSetsKey() {
        val written = DeckGroupsCodec.write(null, StoredGroups(roles, Lens.ROLES))!!
        assertNull(written["groupSets"])
        assertTrue(DeckGroupsCodec.read(written).sets.isPlain)
    }

    @Test
    fun setsRoundTripWithTheOneInUseInTheGroupsKey() {
        val (two, live) = GroupSets.PLAIN.rename(GroupSets.FIRST_ID, "Roles").add(roles, copy = false, name = "Combo")
        val stored = StoredGroups(combo, Lens.ROLES, sets = two)
        val written = DeckGroupsCodec.write(JsonObject(mapOf("notes" to JsonPrimitive("keep"))), stored)!!
        assertEquals(DeckGroups.EMPTY, live)
        // Every other key passes through, and the groups key holds the set in use: what older builds read.
        assertEquals(JsonPrimitive("keep"), written["notes"])
        assertEquals(combo, DeckGroupsCodec.read(JsonObject(mapOf("groups" to written["groups"]!!))).groups)
        val back = DeckGroupsCodec.read(written)
        assertEquals(combo, back.groups)
        assertEquals(listOf("Roles", "Combo"), back.sets.sets.map { it.name })
        assertEquals("s2", back.sets.current.id)
        assertEquals(roles, back.sets.byId("s1")!!.groups)
        // Written again, it is the same file.
        assertEquals(written, DeckGroupsCodec.write(written, back))
    }

    @Test
    fun setsSurviveWhenTheSetInUseHasNoGroups() {
        val (two, _) = GroupSets.PLAIN.add(roles, copy = false)
        val written = DeckGroupsCodec.write(null, StoredGroups(DeckGroups.EMPTY, Lens.DECK, sets = two))!!
        assertNull(written["groups"])
        val back = DeckGroupsCodec.read(written)
        assertEquals(roles, back.sets.byId("s1")!!.groups)
        assertEquals("s2", back.sets.current.id)
    }

    @Test
    fun aBrokenSetsKeyReadsPlainOrRepaired() {
        fun read(text: String) = DeckGroupsCodec.read(Json.parseToJsonElement("""{"groupSets":$text}""") as JsonObject).sets
        assertTrue(read("""[]""").isPlain)
        assertTrue(read("""{"sets":[]}""").isPlain)
        // An active id naming no set puts the first in use.
        assertEquals("a", read("""{"active":"zz","sets":[{"id":"a","name":"A"},{"id":"b"},{"name":"no id"},{"id":"a"}]}""").current.id)
        assertEquals(listOf("A", "b"), read("""{"sets":[{"id":"a","name":"A"},{"id":"b"}]}""").sets.map { it.name })
    }
}
