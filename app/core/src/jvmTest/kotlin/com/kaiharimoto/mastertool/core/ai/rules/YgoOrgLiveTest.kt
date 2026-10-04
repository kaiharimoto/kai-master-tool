package com.kaiharimoto.mastertool.core.ai.rules

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YGOrganization's database read live, not a capture: only when `NEUE_LIVE_YGORG` names cards
 * (`;`-separated, e.g. "Ash Blossom & Joyous Spring;Called by the Grave"), so CI never leans on
 * the network. A handful of requests per card, one at a time, as the site asks.
 */
class YgoOrgLiveTest {
    @Test
    fun cardsReadOffTheLiveDatabase() {
        val names = System.getenv("NEUE_LIVE_YGORG")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        val web = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        fun get(url: String): String {
            val r = web.send(
                HttpRequest.newBuilder(URI(url)).header("User-Agent", "NeueMasterTool (live test)").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(r.statusCode() == 200) { "$url answered ${r.statusCode()}" }
            return r.body()
        }
        val index = YgoOrg.index(get(YgoOrg.INDEX_URL)).getOrThrow()
        assertTrue(index.size > 10_000, "the index holds ${index.size} names")
        names.forEach { name ->
            val id = index.id(name) ?: error("no id for $name")
            val card = YgoOrg.card(get(YgoOrg.cardUrl(id))).getOrThrow()
            val qas = YgoOrg.pick(card.qaIds, max = 3).map { YgoOrg.qa(get(YgoOrg.qaUrl(it))).getOrThrow() }
            val text = YgoOrg.text(card, qas, card.qaIds.size, { index.name(it) })
            println(text)
            println("----")
            assertTrue(card.sections.isNotEmpty() || card.qaIds.isNotEmpty(), "$name has FAQ notes or Q&As")
            assertTrue("<<" !in text, "every reference named")
        }
    }
}
