package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryScope
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.web.WebLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiMemoryTest {

    @Test
    fun entriesAreBulletsAndTheRestIsKept() {
        val doc = AiMemory.parse("# What Ai knows about you\n\nA note kai wrote.\n\n- Plays Branded\n  in TCG.\n- Goes second.\n")
        assertEquals(listOf("# What Ai knows about you", "", "A note kai wrote."), doc.preamble)
        assertEquals(listOf("Plays Branded in TCG.", "Goes second."), doc.entries)
        assertEquals(doc, AiMemory.parse(doc.render()), "render and parse round-trip")
    }

    @Test
    fun addingIsBoundedAndAFullMemoryListsWhatToDrop() {
        var doc = MemoryDoc.blank("x")
        listOf("Goes second.", "Plays Branded.", "Event is YCS Paris.").forEach { entry ->
            val w = AiMemory.add(doc, entry, limit = 50)
            assertIs<MemoryWrite.Done>(w)
            doc = w.doc
        }
        val full = AiMemory.add(doc, "Owns 3 Ash.", limit = 50)
        assertIs<MemoryWrite.Refused>(full)
        assertTrue("- Plays Branded." in full.message, "a full memory shows its entries")
        assertIs<MemoryWrite.Refused>(AiMemory.add(doc, "a much longer entry than half the room", limit = 50), "one entry is at most half")
        assertIs<MemoryWrite.Refused>(AiMemory.add(doc, "goes SECOND.", 999), "no duplicates")
    }

    @Test
    fun replaceAndRemoveFindOneEntryByAPartOfIt() {
        var doc = MemoryDoc(listOf("# t"), listOf("Plays Branded in TCG.", "Plays Snake-Eye for fun."))
        assertIs<MemoryWrite.Refused>(AiMemory.remove(doc, "Plays"), "two entries hold it")
        doc = (AiMemory.replace(doc, "Branded", "Plays Branded Dracotail in TCG.", 999) as MemoryWrite.Done).doc
        assertEquals("Plays Branded Dracotail in TCG.", doc.entries[0])
        doc = (AiMemory.remove(doc, "Snake-Eye") as MemoryWrite.Done).doc
        assertEquals(1, doc.entries.size)
        assertIs<MemoryWrite.Refused>(AiMemory.remove(doc, "Tenpai"))
    }

    @Test
    fun aDeckJoiningAWebLeavesItsNotesToTheWeb() {
        val web = MemoryDoc(listOf("# Notes on YCS Paris"), listOf("Expect Maliss."))
        val deck = MemoryDoc(listOf("# Notes on Branded"), listOf("Ash in main, three copies."))
        val folded = AiMemory.fold(web, deck, "Branded")
        assertEquals(listOf("Expect Maliss.", "[Branded] Ash in main, three copies."), folded.entries)
        assertEquals(folded, AiMemory.fold(folded, deck, "Branded"), "folding twice adds nothing")
    }

    @Test
    fun theScopeIsOneDeckOrOneWebNeverTheLibrary() {
        val webs = WebLibrary(listOf(DeckWeb("w1", "YCS Paris", entries = listOf(WebEntry("d1", mine = true), WebEntry("d2")))))
        assertEquals(MemoryScope(MemoryKind.WEB, "w1", "YCS Paris"), MemoryScope.of("BUILDER", "d2", "Maliss", null, webs))
        assertEquals(MemoryKind.DECK, MemoryScope.of("BUILDER", "d9", "Tenpai", null, webs)!!.kind)
        assertEquals("w1", MemoryScope.of("FORMAT", "d9", "Tenpai", "w1", webs)!!.id, "Format reads the web on screen")
        assertNull(MemoryScope.of("BUILDER", null, "", null, webs), "a deck never saved has no notes yet")
        assertEquals("webs/w1.md", MemoryScope.of("SIDING", null, "", "w1", webs)!!.path)
        assertEquals("decks/a_b.md", AiMemory.path(MemoryKind.DECK, "a/b"))
    }

    @Test
    fun renamingKeepsTheVoice() {
        val soul = Persona.default()
        val renamed = Persona.rename(soul, "Ai", "Roboppi")
        assertTrue(renamed.startsWith("# Roboppi"))
        assertFalse(Regex("\\bAi\\b").containsMatchIn(renamed))
        assertTrue("said" !in soul || "said" in renamed, "only the whole word changes")
    }

    @Test
    fun theSystemPromptIsStableAndTheContextCarriesNotesOnlyWhenTheScopeChanges() {
        val setup = PromptBuilder.Setup("Ai", Persona.default(), "- Goes second.", "", "- format-webs: build a web", "desktop")
        assertEquals(PromptBuilder.system(setup), PromptBuilder.system(setup))
        assertTrue("- Goes second." in PromptBuilder.system(setup))
        val scope = MemoryScope(MemoryKind.WEB, "w1", "YCS Paris")
        val changed = PromptBuilder.context(listOf("Page: Format"), scope, "- Expect Maliss.", scopeChanged = true)
        assertTrue("Expect Maliss" in changed)
        val same = PromptBuilder.context(listOf("Page: Format"), scope, "- Expect Maliss.", scopeChanged = false)
        assertFalse("Expect Maliss" in same)
    }

    @Test
    fun skillsReadTheirFrontMatterAndOwnSkillsWin() {
        val s = Skills.parse("---\nname: Format Webs\ndescription: Build a web.\n---\n\n1. Ask the date.")!!
        assertEquals("format-webs", s.name)
        assertEquals("Build a web.", s.description)
        assertEquals("1. Ask the date.", s.body)
        assertEquals(s.copy(builtIn = false), Skills.parse(s.render()))
        val own = s.copy(description = "Mine.", builtIn = false)
        val merged = Skills.merge(listOf(s.copy(builtIn = true)), listOf(own))
        assertEquals(listOf("Mine."), merged.map { it.description })
        assertTrue("(yours)" in Skills.index(merged))
    }

    @Test
    fun aDecksGuideHasNoCapButOneEntryStillDoes() {
        // kai (1.0.65): "remove the 10k cap for guides".
        val kind = MemoryKind.GUIDE
        assertTrue(!kind.bounded)
        var doc = MemoryDoc.blank("How Labrynth plays")
        repeat(40) { n ->
            val w = AiMemory.add(doc, "Lines: line $n — " + "a step of the combo, ".repeat(20), kind.limit, kind.entryLimit)
            doc = assertIs<MemoryWrite.Done>(w).doc
        }
        assertTrue(doc.used > 10_000, "past the old cap")
        assertTrue((AiMemory.add(doc, "one more", kind.limit, kind.entryLimit) as MemoryWrite.Done).message.endsWith("characters)."))
        assertIs<MemoryWrite.Refused>(AiMemory.add(doc, "x".repeat(kind.entryLimit + 1), kind.limit, kind.entryLimit), "one note is still held to its size")
        // The profile, always in the prompt, stays bounded.
        assertTrue(MemoryKind.USER.bounded)
    }
}
