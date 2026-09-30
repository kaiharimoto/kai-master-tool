package com.kaiharimoto.mastertool.core.ai.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlTextTest {
    @Test
    fun entitiesDecode() {
        assertEquals("Ash Blossom & Joyous Spring", HtmlText.decode("Ash Blossom &amp; Joyous Spring"))
        assertEquals("<b> \"q\" 'a'", HtmlText.decode("&lt;b&gt; &quot;q&quot; &apos;a&apos;"))
        assertEquals("a — b – c… ’‘”“ ©", HtmlText.decode("a &mdash; b &ndash; c&hellip; &rsquo;&lsquo;&rdquo;&ldquo; &copy;"))
        assertEquals("A A — 😀", HtmlText.decode("&#65; &#x41; &#x2014; &#128512;"))
        assertEquals("a b", HtmlText.decode("a&nbsp;b"))
        // Unknown or broken references stay as written; decoding happens once.
        assertEquals("&bogus; & alone &#xZZ; &amp;", HtmlText.decode("&bogus; & alone &#xZZ; &amp;amp;"))
    }

    @Test
    fun scriptsStylesAndCommentsAreDropped() {
        val html = """
            <html><head><title>Rulings &amp; more</title><style>body { color: red }</style>
            <script>var x = "<p>not text</p>";</script></head>
            <body><header>Site</header><!-- a comment <p>hidden</p> -->
            <script type="text/javascript">
              document.write('<div>gone</div>');
            </script>
            <noscript>Enable JavaScript</noscript>
            <svg viewBox="0 0 10 10"><title>icon</title><path d="M0 0"/></svg>
            <p>Kept.</p></body></html>
        """.trimIndent()
        val text = HtmlText.text(html)
        assertEquals("Rulings & more\n\nSite\n\nKept.", text)
        assertFalse("gone" in text || "hidden" in text || "icon" in text || "color" in text || "JavaScript" in text)
    }

    @Test
    fun listsHeadingsAndTables() {
        val html = """
            <body>
            <h1>Snake-Eye</h1>
            <p>A   theme of
               Level 1 FIRE monsters.<br>Second line.</p>
            <h2>Key <em>cards</em></h2>
            <ul>
              <li>Snake-Eye Ash</li>
              <li><a href="/oak">Snake-Eye Oak</a></li>
            </ul>
            <h3>Ratios</h3>
            <table>
              <tr><th>Card</th><th>Copies</th></tr>
              <tr><td>Ash</td><td>3</td></tr>
            </table>
            <h4>Small heading</h4>
            <div>Footer</div>
            </body>
        """.trimIndent()
        val expected = """
            # Snake-Eye

            A theme of Level 1 FIRE monsters.
            Second line.

            ## Key cards

            - Snake-Eye Ash
            - Snake-Eye Oak

            ### Ratios

            Card | Copies
            Ash | 3

            Small heading

            Footer
        """.trimIndent()
        assertEquals(expected, HtmlText.text(html))
    }

    @Test
    fun titleIsReadOrNull() {
        assertEquals("Snake-Eye — Yugipedia", HtmlText.title("<head><title>\n  Snake-Eye &mdash; Yugipedia\n</title></head>"))
        assertNull(HtmlText.title("<p>No title</p>"))
        assertNull(HtmlText.title("<title>  </title>"))
        // The title is not repeated when the page opens with it.
        assertEquals("# Deck\n\nBody", HtmlText.text("<title>Deck</title><h1>Deck</h1><p>Body</p>"))
    }

    @Test
    fun brokenMarkupNeverThrows() {
        assertEquals("a < b and c", HtmlText.text("a < b <i>and</i> c"))
        assertEquals("open", HtmlText.text("<p>open<script>never closed"))
        assertEquals("x", HtmlText.text("<div class=\"a>b\">x</div"))
    }

    @Test
    fun longPagesKeepHeadAndTail() {
        val words = (1..3000).joinToString(" ") { "word$it" }
        val text = HtmlText.text("<p>$words</p>", maxChars = 2000)
        assertTrue(text.length <= 2000, "length ${text.length}")
        assertTrue(text.startsWith("word1 word2 "))
        assertTrue(text.endsWith("word3000"))
        val marker = Regex("\\[… (\\d+) characters cut …]").find(text)!!
        val kept = text.replace(marker.value, "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Every word is whole, and the count cut is what is missing.
        assertTrue(kept.all { Regex("word\\d+").matches(it) })
        assertEquals(words.length - marker.groupValues[1].toInt(), text.length - marker.value.length - 4)
        val tail = text.substringAfter(marker.value).trim()
        assertTrue(tail.length in 200..300, "tail ${tail.length}")
        assertEquals("short", HtmlText.text("<p>short</p>", maxChars = 2000))
    }

    @Test
    fun duckDuckGoResults() {
        val hits = SearchResults.duckDuckGo(DuckDuckGoFixture.RESULTS)
        assertEquals(
            listOf(
                "https://yugipedia.com/wiki/Snake-Eye",
                "https://www.reddit.com/r/yugioh/comments/18x2k3j/snakeeye_combo_guide_for_beginners/",
                "https://www.masterduelmeta.com/tier-list/deck-types/Snake-Eye",
                "https://ygoprodeck.com/deck/snake-eye-fiendsmith-512345?sort=new&page=2",
                "https://ja.yugioh-wiki.net/index.php?%E3%80%8A%E8%9B%87%E7%9C%BC%E3%80%8B",
            ),
            hits.map { it.url },
        )
        val first = hits.first()
        assertEquals("Snake-Eye - Yugipedia", first.title)
        assertTrue(first.snippet.startsWith("\"Snake-Eye\" is a theme of mostly Level 1 FIRE Pyro monsters"), first.snippet)
        assertTrue(first.snippet.endsWith("Spell & Trap Zone as Continuous Spell Cards…"), first.snippet)
        assertEquals("Snake-Eye Deck Type & Combos | Master Duel Meta", hits[2].title)
        assertEquals("Top decklists, combos and the Snake-Eye engine's best extenders — updated daily.", hits[2].snippet)
        assertEquals("Deck built for the 2025 format. Combo ends on Flamberge Dragon & Fiendsmith’s Requiem.", hits[3].snippet)
        assertEquals("《蛇眼》 - 遊戯王 Wiki", hits[4].title)
        assertTrue(hits.none { "tcgplayer" in it.url || "Buy" in it.title })
        assertEquals(2, SearchResults.duckDuckGo(DuckDuckGoFixture.RESULTS, limit = 2).size)
        assertFalse(SearchResults.blocked(DuckDuckGoFixture.RESULTS))
    }

    @Test
    fun duckDuckGoChallengeIsBlockedNotEmpty() {
        assertTrue(SearchResults.duckDuckGo(DuckDuckGoFixture.CHALLENGE).isEmpty())
        assertTrue(SearchResults.blocked(DuckDuckGoFixture.CHALLENGE))
    }

    @Test
    fun percentDecoding() {
        assertEquals("https://a.b/c d?e=f&g", SearchResults.percentDecode("https%3A%2F%2Fa.b%2Fc%20d%3Fe%3Df%26g"))
        assertEquals("蛇眼", SearchResults.percentDecode("%E8%9B%87%E7%9C%BC"))
        assertEquals("100% a+b %zz", SearchResults.percentDecode("100% a+b %zz"))
        assertEquals("https://x.org/p", SearchResults.resolve("//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.org%2Fp&amp;rut=1"))
        assertEquals("https://x.org/p", SearchResults.resolve("//x.org/p"))
        assertNull(SearchResults.resolve("/html/?q=next"))
    }
}
