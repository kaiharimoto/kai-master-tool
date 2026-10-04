package com.kaiharimoto.mastertool.core.ai.web

import com.kaiharimoto.mastertool.core.ai.rules.YgoOrg
import com.kaiharimoto.mastertool.core.ai.rules.YgoOrgFixture
import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.ai.rules.YugipediaFixture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Only a reply the caller could read is kept for the week (1.0.99, the red team's finding on the rulings cache). */
class ReplyCacheTest {
    private val card: (String) -> Boolean = { YgoOrg.card(it).isSuccess }
    private val wikitext: (String) -> Boolean = { Yugipedia.wikitextOf(it) != null }

    @Test
    fun aReplyTheCallerCannotReadIsNeverKept() {
        assertTrue(ReplyCache.keep(YgoOrgFixture.ASH, card))
        // JSON of the wrong shape: a card answer with no card in it.
        assertFalse(ReplyCache.keep("""{"cardData":{}}""", card))
        assertFalse(ReplyCache.keep("""{"parse":{"title":"x"}}""", wikitext))
        assertTrue(ReplyCache.keep(YugipediaFixture.SNAKE_EYE_SECTIONS, Yugipedia::hasSections))
        assertFalse(ReplyCache.keep("""{"parse":{"title":"Snake-Eye"}}""", Yugipedia::hasSections))
        // A reader that throws has not read it.
        assertFalse(ReplyCache.keep(YgoOrgFixture.ASH) { error("no") })
    }

    @Test
    fun withoutAReaderTheOldRuleStands() {
        // Any JSON that is not an API error, as before 1.0.99.
        assertTrue(ReplyCache.keep("""{"cardData":{}}"""))
        assertFalse(ReplyCache.keep(YugipediaFixture.MISSING))
        assertFalse(ReplyCache.keep("<html>a captcha</html>"))
        assertFalse(ReplyCache.keep(YugipediaFixture.MISSING) { true })
    }

    @Test
    fun aKeptReplyIsServedOnlyWhileYoungAndReadable() {
        assertTrue(ReplyCache.serve(YgoOrgFixture.ASH, 1_000, card))
        assertFalse(ReplyCache.serve(YgoOrgFixture.ASH, ReplyCache.WEEK, card))
        // One an older build kept unread is asked for again, not served for the rest of its week.
        assertFalse(ReplyCache.serve("""{"cardData":{}}""", 1_000, card))
        assertTrue(ReplyCache.serve("""{"cardData":{}}""", 1_000))
    }
}
