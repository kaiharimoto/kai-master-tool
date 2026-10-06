package com.kaiharimoto.mastertool.core.ai.library

import com.kaiharimoto.mastertool.core.ai.course.CoursePaths
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths

/** A file the Library can see: its path under the data folder (`ai/guides/d1.md`), its size and when it changed. */
data class FileStat(val path: String, val bytes: Long, val updated: Long)

/** A document read a window at a time: like a `Reader`, [read] fills [into] and says how many, or -1 at the end. */
interface LibraryReader {
    fun read(into: CharArray, off: Int, len: Int): Int
    fun close()
}

/**
 * What the Library reads (`docs/world/DESKTOP.md` §10): the data folder, read-only. Paths are `/`-separated and relative
 * to it; an implementation refuses anything that would leave it. `DiskLibraryFiles` (jvmMain) is the app's; tests use a
 * map.
 */
interface LibraryFiles {
    /** Every file under [dir], recursively, or none when there is no such folder. */
    fun list(dir: String): List<FileStat>

    /** A reader over [path]'s text, or null when it is not there. */
    fun reader(path: String): LibraryReader?

    /** [path]'s text whole: for a document opened to read, off the frame thread. */
    fun text(path: String): String? {
        val r = reader(path) ?: return null
        try {
            val out = StringBuilder()
            val buf = CharArray(64 * 1024)
            while (true) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) break
                out.appendRange(buf, 0, n)
            }
            return out.toString()
        } finally {
            r.close()
        }
    }
}

/** What a document of the Library is (§10.1). */
enum class LibraryKind(val title: String) {
    GUIDE("Guide"),
    BOOK("Book"),
    NOTES("Notes"),
    REPORTS("Reports"),
    EVIDENCE("Evidence"),
    RUBRIC("Rubric"),
    WEB("Web notes"),
    LESSONS("Lessons"),
    SOUL("Character"),
    USER("About you"),

    /** A chapter's notes from a course Ai studied (`ai/courses/<id>/notes/<n>.md`). */
    COURSE("Course notes"),
}

/** The Library's shelves (§10.1). */
enum class Shelf(val title: String) {
    THIS_DECK("This deck"),
    WEBS("Webs"),
    AI("Ai"),
    EVERYTHING("Everything"),
}

/**
 * One document: where it lives ([path], under the data folder), what it is, whose it is ([scope]: `deck:<id>`,
 * `web:<id>` or `ai`), its [title] for the shelf, its size and when it changed. Nothing of its text: the catalogue is a
 * list, never a copy (§10.3).
 */
data class LibraryDoc(
    val path: String,
    val kind: LibraryKind,
    val scope: String,
    val title: String,
    val bytes: Long,
    val updated: Long,
    /** The deck or web it is about, by name, when it is about one. */
    val about: String? = null,
)

/**
 * Everything Ai knows, listed from where it lives (§10.1, `LibraryCatalogTest`): Ai's memory folder (`ai/`: the guides,
 * books, deck and web notes, reports, evidence, `MEMORY.md`, `SOUL.md`, `USER.md`) and the Shootout's rubrics. Built
 * from the folders' listings alone — no text read — so it is cheap at any size.
 */
class LibraryCatalog(val docs: List<LibraryDoc>) {
    /** [shelf]'s documents; [deck] is the deck the This deck shelf is about. */
    fun shelf(shelf: Shelf, deck: String? = null): List<LibraryDoc> = when (shelf) {
        Shelf.THIS_DECK -> deck?.let { d -> docs.filter { it.scope == DECK + d } }.orEmpty()
        Shelf.WEBS -> docs.filter { it.scope.startsWith(WEB) }
        Shelf.AI -> docs.filter { it.scope == AI }
        Shelf.EVERYTHING -> docs.sortedWith(compareBy({ it.about ?: "￿" }, { it.kind.ordinal }))
    }

    /** The documents for a scope as a script names it: `deck:<id>`, `web:<id>`, `ai`, or null for everything. */
    fun scoped(scope: String?): List<LibraryDoc> = when {
        scope.isNullOrBlank() || scope == "all" -> docs
        else -> docs.filter { it.scope == scope }
    }

    fun doc(path: String): LibraryDoc? = docs.firstOrNull { it.path == path }

    /** Every document, [first]'s first (the shelf on screen searched first, §10.3). */
    fun ordered(first: List<LibraryDoc>): List<LibraryDoc> = first + docs.filterNot { d -> first.any { it.path == d.path } }

    companion object {
        const val AI = "ai"
        const val DECK = "deck:"
        const val WEB = "web:"

        /** Ai's memory folder, under the data folder. */
        const val MEMORY = "ai"

        /**
         * The catalogue of [files], naming documents from [decks] and [webs] (id → name). A document whose deck is gone
         * keeps its file's name as its title.
         */
        fun build(files: LibraryFiles, decks: Map<String, String>, webs: Map<String, String> = emptyMap()): LibraryCatalog {
            val deckBySafe = decks.keys.associateBy { AiMemory.safeId(it) }
            val webBySafe = webs.keys.associateBy { AiMemory.safeId(it) }
            val out = ArrayList<LibraryDoc>()
            fun deckDoc(f: FileStat, kind: LibraryKind, safe: String) {
                val id = deckBySafe[safe] ?: safe
                val name = decks[id]
                out += LibraryDoc(f.path, kind, DECK + id, kind.title + (name?.let { " · $it" } ?: " · $safe"), f.bytes, f.updated, name)
            }
            for (f in files.list(MEMORY)) {
                val rel = f.path.removePrefix("$MEMORY/")
                val dir = rel.substringBefore('/', "")
                val name = rel.substringAfterLast('/')
                when {
                    rel == MemoryKind.AGENT.file -> out += LibraryDoc(f.path, LibraryKind.LESSONS, AI, "Lessons", f.bytes, f.updated)
                    rel == "SOUL.md" -> out += LibraryDoc(f.path, LibraryKind.SOUL, AI, "Character", f.bytes, f.updated)
                    rel == MemoryKind.USER.file -> out += LibraryDoc(f.path, LibraryKind.USER, AI, "About you", f.bytes, f.updated)
                    dir == "guides" && name.endsWith(BOOK) -> deckDoc(f, LibraryKind.BOOK, name.removeSuffix(BOOK))
                    dir == "guides" && name.endsWith(".md") -> deckDoc(f, LibraryKind.GUIDE, name.removeSuffix(".md"))
                    dir == "decks" && name.endsWith(".md") -> deckDoc(f, LibraryKind.NOTES, name.removeSuffix(".md"))
                    dir == "reports" && name.endsWith(".json") -> deckDoc(f, LibraryKind.REPORTS, name.removeSuffix(".json"))
                    dir == "evidence" && name.endsWith(".json") -> deckDoc(f, LibraryKind.EVIDENCE, name.removeSuffix(".json"))
                    // A studied course's notes, a chapter a document: the course's own words stay in its pages.
                    dir == CoursePaths.ROOT && rel.count { it == '/' } == 3 && rel.split('/')[2] == "notes" && name.endsWith(".md") -> {
                        val course = rel.split('/')[1]
                        out += LibraryDoc(f.path, LibraryKind.COURSE, AI, "Course · $course · ch. ${name.removeSuffix(".md")}", f.bytes, f.updated)
                    }
                    dir == "webs" && name.endsWith(".md") -> {
                        val safe = name.removeSuffix(".md")
                        val id = webBySafe[safe] ?: safe
                        val wn = webs[id]
                        out += LibraryDoc(f.path, LibraryKind.WEB, WEB + id, "Notes · " + (wn ?: safe), f.bytes, f.updated, wn)
                    }
                }
            }
            val shootSafe = decks.keys.associateBy { ShootoutPaths.safe(it) }
            for (f in files.list(ShootoutPaths.FOLDER)) {
                val rel = f.path.removePrefix(ShootoutPaths.FOLDER + "/")
                if (!rel.endsWith(ShootoutPaths.RUBRIC) || rel.count { it == '/' } != 1) continue
                val folder = rel.substringBefore('/')
                val id = shootSafe[folder] ?: folder
                val matchup = rel.substringAfter('/').removeSuffix(ShootoutPaths.RUBRIC)
                val against = if (matchup == "alone") "alone" else "against " + (shootSafe[matchup]?.let { decks[it] } ?: decks[matchup] ?: matchup)
                out += LibraryDoc(f.path, LibraryKind.RUBRIC, DECK + id, "Rubric · $against", f.bytes, f.updated, decks[id])
            }
            return LibraryCatalog(out.sortedWith(compareBy({ it.scope }, { it.kind.ordinal }, { it.title })))
        }

        private const val BOOK = ".book.json"

        /** The paths each kind lives at, for one deck: kept beside the code that writes them, read here. */
        fun deckPaths(deckId: String): List<String> = listOf(
            "$MEMORY/" + AiMemory.path(MemoryKind.GUIDE, deckId),
            "$MEMORY/" + GuideBook.path(deckId),
            "$MEMORY/" + AiMemory.path(MemoryKind.DECK, deckId),
            "$MEMORY/" + ReportLog.path(deckId),
            "$MEMORY/" + Ledger.path(deckId),
        )

        /** "Guide · 41,200 words", "Evidence · 128 numbers": a document's count, from its text once it is read. */
        fun count(doc: LibraryDoc, text: String): String {
            val n = when (doc.kind) {
                LibraryKind.EVIDENCE -> Ledger.read(text).let { l ->
                    val stale = l.count { it.status == Proven.Status.STALE || it.status == Proven.Status.CONTRADICTED }
                    "${commas(l.size)} numbers" + if (stale > 0) ", $stale stale" else ""
                }
                LibraryKind.REPORTS -> ReportLog.read(text).size.let { if (it == 1) "1 report" else "${commas(it)} reports" }
                else -> "${commas(words(text))} words"
            }
            return "${doc.kind.title} · $n"
        }

        /** Words in [text]: runs of letters or digits. */
        fun words(text: String): Int {
            var n = 0
            var inWord = false
            for (c in text) {
                val w = c.isLetterOrDigit()
                if (w && !inWord) n++
                inWord = w
            }
            return n
        }

        private fun commas(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
