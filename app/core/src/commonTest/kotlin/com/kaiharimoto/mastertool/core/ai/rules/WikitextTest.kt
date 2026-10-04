package com.kaiharimoto.mastertool.core.ai.rules

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikitextTest {
    /** The API's formatversion=2 answer round a page's wikitext, as the live one is shaped. */
    private fun v2(title: String, wikitext: String) = buildJsonObject {
        put("parse", buildJsonObject {
            put("title", title)
            put("pageid", 481236)
            put("wikitext", wikitext)
        })
    }.toString()

    /** The older shape, `"wikitext":{"*":…}`, as the API answers without formatversion=2. */
    private fun legacy(title: String, wikitext: String) = buildJsonObject {
        put("parse", buildJsonObject {
            put("title", title)
            put("pageid", 992390)
            put("wikitext", buildJsonObject { put("*", JsonPrimitive(wikitext)) })
        })
    }.toString()

    @Test
    fun urls() {
        assertEquals(
            "https://yugipedia.com/api.php?action=parse&page=Card_Rulings:Ash_Blossom_%26_Joyous_Spring&prop=wikitext&format=json&formatversion=2&redirects=1",
            Yugipedia.rulingsUrl("Ash Blossom & Joyous Spring"),
        )
        assertEquals(
            "https://yugipedia.com/api.php?action=parse&page=Snake-Eye&prop=wikitext&section=5&format=json&formatversion=2&redirects=1",
            Yugipedia.parseUrl("Snake-Eye", 5),
        )
        assertEquals(
            "https://yugipedia.com/api.php?action=parse&page=Snake-Eye&prop=wikitext&format=json&formatversion=2&redirects=1",
            Yugipedia.parseUrl("Snake-Eye"),
        )
        assertEquals(
            "https://yugipedia.com/api.php?action=parse&page=Snake-Eye&prop=sections&format=json&formatversion=2&redirects=1",
            Yugipedia.sectionsUrl("Snake-Eye"),
        )
        assertEquals("Where_Arf_Thou%3F", Yugipedia.title("Where Arf Thou?"))
        assertEquals("Number_38:_Hope_Harbinger_Dragon_Titanic_Galaxy", Yugipedia.title("Number 38: Hope Harbinger Dragon Titanic Galaxy"))
        assertEquals("A%23B%25C%2BD%3DE", Yugipedia.title("A#B%C+D=E"))
        assertEquals("%E8%9B%87%E7%9C%BC_Pok%C3%A9mon", Yugipedia.title("蛇眼 Pokémon"))
        assertEquals("Magicians'_Souls", Yugipedia.title("Magicians' Souls"))
    }

    @Test
    fun wikitextOfBothShapesAndErrors() {
        assertEquals(YugipediaFixture.ASH_RULINGS, Yugipedia.wikitextOf(v2("Card Rulings:Ash Blossom & Joyous Spring", YugipediaFixture.ASH_RULINGS)))
        assertEquals(YugipediaFixture.SNAKE_EYE_PLAYING_STYLE, Yugipedia.wikitextOf(legacy("Snake-Eye", YugipediaFixture.SNAKE_EYE_PLAYING_STYLE)))
        assertEquals("x", Yugipedia.wikitextOf("""{"parse":{"title":"T","pageid":1,"wikitext":"x"}}"""))
        assertEquals("y", Yugipedia.wikitextOf("""{"parse":{"title":"T","pageid":1,"wikitext":{"*":"y"}}}"""))
        assertNull(Yugipedia.wikitextOf(YugipediaFixture.MISSING))
        assertNull(Yugipedia.wikitextOf("not json"))
        // A missing page answers 200 with an error: never kept in the week's cache as if it were the page.
        assertTrue(Yugipedia.isError(YugipediaFixture.MISSING))
        assertTrue(Yugipedia.isError("<html>Too many requests</html>"))
        assertFalse(Yugipedia.isError(v2("Card Rulings:Ash Blossom & Joyous Spring", YugipediaFixture.ASH_RULINGS)))
        assertFalse(Yugipedia.isError(YugipediaFixture.SNAKE_EYE_SECTIONS))
        assertNull(Yugipedia.wikitextOf(YugipediaFixture.SNAKE_EYE_SECTIONS))
    }

    @Test
    fun sectionIndexFromTheLiveAnswer() {
        assertEquals(listOf(5 to "Playing style"), Yugipedia.sectionIndex(YugipediaFixture.SNAKE_EYE_SECTIONS, listOf("playing style")))
        assertEquals(
            listOf(5 to "Playing style", 9 to "Weaknesses"),
            Yugipedia.sectionIndex(YugipediaFixture.SNAKE_EYE_SECTIONS, listOf("Weaknesses", "Playing style")),
        )
        val all = Yugipedia.sectionIndex(YugipediaFixture.SNAKE_EYE_SECTIONS, emptyList())
        assertEquals(9, all.size)
        assertEquals(1 to "Lore", all.first())
        assertEquals(listOf(8 to "Recommended cards"), Yugipedia.sectionIndex(YugipediaFixture.SNAKE_EYE_SECTIONS, listOf("recommended")))
        assertTrue(Yugipedia.sectionIndex(YugipediaFixture.MISSING, listOf("x")).isEmpty())
        assertEquals(
            listOf(2 to "Rulings"),
            Yugipedia.sectionIndex(
                """{"parse":{"sections":[{"line":"Lead","index":"T-1"},{"line":"<i>Rulings</i>","index":"2"}]}}""",
                listOf("rulings"),
            ),
        )
    }

    @Test
    fun ashRulings() {
        val rulings = Wikitext.rulings(YugipediaFixture.ASH_RULINGS)
        assertEquals(4 + 19, rulings.size)
        // The OCG bullets first: plain answers, links and references gone.
        val bullets = rulings.take(4)
        assertTrue(bullets.all { it.question == null })
        assertEquals("The effect of \"Ash Blossom & Joyous Spring\" is a Quick Effect that activates in the hand.", bullets[0].answer)
        assertEquals("Discarding \"Ash Blossom & Joyous Spring\" is a cost to activate its effect.", bullets[1].answer)
        assertEquals("It cannot be activated during the Damage Step.", bullets[2].answer)
        assertTrue(rulings.none { "[[" in it.answer || "<ref" in it.answer || "Konami OCG Card Database" in it.answer })
        // Then the Q&A templates, with their questions and citations.
        val macro = rulings[4]
        assertEquals(
            "If you activate the effect of \"Ash Blossom & Joyous Spring\" in Chain to the activation of the card \"Macro Cosmos\", which effects are negated?",
            macro.question,
        )
        assertTrue(macro.answer.startsWith("The effect of \"Ash Blossom & Joyous Spring\" negates the effect it was directly Chained to."))
        assertEquals("7315", macro.cite)
        assertTrue(rulings.drop(4).all { it.question != null && it.cite != null && it.answer.isNotEmpty() })
        // Italics inside an answer are only marks.
        val ascator = rulings.first { it.question?.contains("Ascator") == true }
        assertTrue("\"You cannot Special Summon monsters from the Extra Deck the turn you activate this effect, except Synchro Monsters\"" in ascator.answer)
        assertEquals("20546", rulings.last().cite)
        assertTrue(rulings.last().question!!.contains("Zoodiac Drident"))
    }

    @Test
    fun ashAsPlainText() {
        val text = Wikitext.plain(YugipediaFixture.ASH_RULINGS)
        assertTrue(text.startsWith("OCG Rulings\n\n- The effect of \"Ash Blossom & Joyous Spring\" is a Quick Effect"), text.take(200))
        assertTrue("Q&A Rulings\n\nQ: If you activate the effect of" in text)
        assertTrue("\nA: The effect of \"Ash Blossom & Joyous Spring\" negates" in text)
        assertFalse("{{" in text || "}}" in text || "[[" in text || "<ref" in text || "cite" in text)
        assertTrue(text.endsWith("References"))
    }

    @Test
    fun snakeEyeSections() {
        val sections = Wikitext.sections(YugipediaFixture.SNAKE_EYE_PLAYING_STYLE)
        assertEquals(
            listOf(2 to "Playing style", 3 to "External support", 3 to "Sample combo", 3 to "Recommended cards", 3 to "Weaknesses"),
            sections.map { it.level to it.title },
        )
        val style = sections[0].body
        assertTrue(style.startsWith("\"Snake-Eye\" is a theme of mostly Level 1 FIRE Pyro monsters, focused around placing monsters in the Spell & Trap Zone as Continuous Spell Cards"), style)
        assertTrue("- \"Sinful Spoils of Subversion\" places 1 monster into the Spell & Trap Zone" in style)
        assertTrue("\"Original Sinful Spoils\" (OCG / Traditional Format)" in style)
        val external = sections[1].body
        assertTrue("\n  - The \"Fire King\" support released in Structure Deck: Fire Kings." in external, external)
        val combo = sections[2].body
        assertTrue("1. Placing \"I:P Masquerena\" in the Spell & Trap Zone" in combo, combo)
        assertTrue("\n3. \"Amphibious Swarmship Amblowhale\" can act" in combo)
        assertTrue("Opening: \"Snake-Eye Ash\"" in combo)
        assertTrue("Steps:\n1. Summon \"Snake-Eye Ash\".\n2. Activate \"Ash\" to search for \"Snake-Eyes Poplar\"." in combo)
        assertTrue("15. Link Summon \"Amphibious Swarmship Amblowhale\"" in combo)
        assertTrue("Activate \"Ash\" to send itself and \"Poplar\" to the GY to Special Summon" in combo)
        val cards = sections[3].body
        assertTrue(cards.startsWith("Recommended cards\nEffect monsters:\n- Snake-Eye Ash\n- Snake-Eye Birch"), cards)
        assertFalse("not an exact Decklist" in cards)
        val weak = sections[4].body
        assertTrue(weak.startsWith("- Due to the archetype being reliant on using Graveyard"), weak)
        assertFalse("Category" in weak || "navbox" in weak.lowercase())
    }

    @Test
    fun plainStripsLinksTemplatesAndRefs() {
        assertEquals("Bonfire and Snake-Eye support", Wikitext.plain("[[Bonfire (card)|Bonfire]] and [[Snake-Eye]] support"))
        assertEquals("Summoning a card", Wikitext.plain("[[Summon]]ing a [[card]]"))
        assertEquals("Rank 8 monsters", Wikitext.plain("[[Rank 8 Monster Cards|Rank 8]] monsters[[Category:Archetypes]][[File:X.png|thumb|A [[link]] caption]]"))
        assertEquals("A cost.", Wikitext.plain("A '''[[cost]]'''.<ref name=\"a\">[http://x.org Konami]: notes</ref><ref name=\"a\"/>"))
        assertEquals("Ash and Snake-Eye decks", Wikitext.plain("{{Card|Ash}} and {{Arch|Snake-Eye}} decks{{Navigation}}"))
        assertEquals("Uses Oak.", Wikitext.plain("Uses {{C|Oak|Snake-Eye Oak}}.<!-- hidden -->"))
        assertEquals("See the database.", Wikitext.plain("See [https://db.yugioh-card.com the database]."))
        assertEquals("Heading\n\n- one\n  - two\n- three", Wikitext.plain("== [[Heading]] ==\n* one\n** two\n* three"))
        assertEquals("1. a\n2. b\ntext\n1. c", Wikitext.plain("# a\n# b\ntext\n# c"))
        assertEquals("a\nb — c", Wikitext.plain("a<br />b &mdash; c"))
        assertEquals("Q: Can I?\nA: Yes.", Wikitext.plain("{{Ruling\n| Q = Can I?\n| A = ''Yes''.\n| cite = 1\n}}"))
        assertEquals("", Wikitext.plain("{{Infobox archetype\n| image = x.png\n| caption = \"[[A]]\"\n}}"))
    }

    @Test
    fun rulingsFromHandWrittenPages() {
        val page = """
            == OCG Rulings ==
            * First ruling, about [[Chain Link|Chain Links]].
            ** A note on it.
            * Second ruling.<ref name="x"/>
            {{Ruling|Q=Does {{Card|Ash}} target?|A=No.}}
            == References ==
            * Not a ruling.
        """.trimIndent()
        assertEquals(
            listOf(
                Wikitext.Ruling(null, "First ruling, about Chain Links.\n- A note on it.", null, "OCG Rulings"),
                Wikitext.Ruling(null, "Second ruling.", null, "OCG Rulings"),
                Wikitext.Ruling("Does Ash target?", "No.", null, "OCG Rulings"),
            ),
            Wikitext.rulings(page),
        )
        assertTrue(Wikitext.rulings("").isEmpty())
        assertTrue(Wikitext.rulings("{{Ruling|Q=never closed").isEmpty())
    }

    @Test
    fun eachRulingKeepsItsSectionAndItsSource() {
        // Red team: the rulings tool dropped both, so a TCG ruling and an OCG one read the same, unsourced.
        val rulings = Wikitext.rulings(YugipediaFixture.ASH_RULINGS)
        val bullets = rulings.take(4)
        assertTrue(bullets.all { it.section == "OCG Rulings" })
        // A bullet's source is its reference, written on the first and only named on the rest.
        assertTrue(bullets.all { it.cite == "Konami OCG Card Database: Ash Blossom & Joyous Spring" }, bullets.map { it.cite }.toString())
        val macro = rulings[4]
        assertEquals("OCG Rulings › Q&A Rulings", macro.section)
        assertEquals("Konami OCG Card Database, Q&A #7315", macro.source)
        assertEquals(
            "- [OCG Rulings › Q&A Rulings] Q: ${macro.question}\n  A: ${macro.answer} (source: Konami OCG Card Database, Q&A #7315)",
            macro.line(),
        )
        assertEquals(
            "- [OCG Rulings] It cannot be activated during the Damage Step. (source: Konami OCG Card Database: Ash Blossom & Joyous Spring)",
            bullets[2].line(),
        )
        // A level-2 heading after a level-3 one closes it; TCG rulings are told from OCG ones.
        val page = """
            == TCG Rulings ==
            === Q&A ===
            * A TCG ruling.<ref>[https://www.yugioh-card.com/en/ Konami TCG FAQ]</ref>
            == OCG Rulings ==
            {{Ruling|Does it?|Yes.|1234}}
        """.trimIndent()
        assertEquals(
            listOf(
                Wikitext.Ruling(null, "A TCG ruling.", "Konami TCG FAQ", "TCG Rulings › Q&A"),
                Wikitext.Ruling("Does it?", "Yes.", "1234", "OCG Rulings"),
            ),
            Wikitext.rulings(page),
        )
        assertEquals("- No section, no source.", Wikitext.Ruling(null, "No section, no source.", null).line())
    }
}
