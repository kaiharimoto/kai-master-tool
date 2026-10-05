package com.kaiharimoto.mastertool.core.ai.library

import com.kaiharimoto.mastertool.core.world.WorldKnowledge
import com.kaiharimoto.mastertool.core.world.WorldLimits
import com.kaiharimoto.mastertool.core.world.WorldPage

/**
 * `ygo.knowledge` over the Library (§10.4): read-only, scripts and apps alike. Only what the catalogue lists can be read —
 * never a path a script makes up — and a read is a page of [WorldLimits.KNOWLEDGE_PAGE] characters.
 *
 * [catalog] is asked once per call, so a script sees what Ai knows as of now.
 */
class LibraryKnowledge(private val files: LibraryFiles, private val catalog: () -> LibraryCatalog) : WorldKnowledge {
    override fun list(scope: String?): List<LibraryDoc> = catalog().scoped(scope)

    override fun read(path: String, from: Int): Pair<LibraryDoc, WorldPage>? {
        val doc = catalog().doc(path) ?: return null
        val text = files.text(doc.path) ?: return null
        return doc to WorldPage.of(text, from, WorldLimits.KNOWLEDGE_PAGE)
    }

    override fun search(q: String, scope: String?, limit: Int): List<LibraryHit> {
        val query = LibraryQuery.parse(q) ?: return emptyList()
        val out = ArrayList<LibraryHit>()
        LibrarySearch.walk(files, catalog().scoped(scope), query, limit.coerceIn(1, WorldLimits.KNOWLEDGE_HITS)) { out += it }
        return out
    }
}
