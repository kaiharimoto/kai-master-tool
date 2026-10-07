package com.kaiharimoto.mastertool.core.ai.course

/**
 * A course as kept on this computer, written out as one page the person can read without the browser (1.1.48): every
 * chapter's words with its pictures in their places, then the replays in words. One self-contained HTML file — the
 * pictures inside it — that opens in any browser, offline. The replays held out for the exam are named, never written
 * out, so the file never carries the exam's answers.
 */
object CourseExport {
    /** A chapter as kept: its words (with "[Picture N]" markers) and each kept picture as a `data:` address by number. */
    data class ChapterDoc(val n: Int, val title: String, val url: String, val text: String, val pictures: Map<Int, String> = emptyMap(), val notes: String = "")

    data class ReplayDoc(val n: Int, val players: String, val url: String, val text: String, val heldOut: Boolean)

    fun html(course: Course, chapters: List<ChapterDoc>, replays: List<ReplayDoc>, notes: Boolean = false): String = buildString {
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(esc(course.label)).append("</title>")
        append("<style>").append(STYLE).append("</style></head><body><main>")
        append("<h1>").append(esc(course.label)).append("</h1>")
        append("<p class=\"meta\">")
        if (course.author.isNotBlank()) append("By ").append(esc(course.author)).append(" · ")
        append("Kept by Neue Master Tool for ").append(esc(course.deckName.ifBlank { "your deck" })).append(" · ")
        append("<a href=\"").append(attr(course.start)).append("\">").append(esc(course.start)).append("</a></p>")
        append("<nav><h2>Contents</h2><ol>")
        chapters.forEach { c -> append("<li><a href=\"#ch").append(c.n).append("\">").append(esc(c.title)).append("</a></li>") }
        if (replays.isNotEmpty()) append("<li><a href=\"#replays\">Replays</a></li>")
        append("</ol></nav>")
        chapters.forEach { c ->
            append("<section id=\"ch").append(c.n).append("\"><h2>").append(c.n).append(". ").append(esc(c.title)).append("</h2>")
            if (c.url.isNotBlank()) append("<p class=\"meta\"><a href=\"").append(attr(c.url)).append("\">On the course's page</a></p>")
            append(body(c.text, c.pictures))
            if (notes && c.notes.isNotBlank()) append("<details><summary>The study's notes</summary>").append(body(c.notes, emptyMap())).append("</details>")
            append("</section>")
        }
        if (replays.isNotEmpty()) {
            append("<section id=\"replays\"><h2>Replays</h2>")
            replays.forEach { r ->
                append("<h3>Replay ").append(r.n).append(if (r.players.isNotBlank()) " · " + esc(r.players) else "").append("</h3>")
                append("<p class=\"meta\"><a href=\"").append(attr(r.url)).append("\">").append(esc(r.url)).append("</a></p>")
                // The exam's duels are named only: what Ai is asked stays unread, here too.
                if (r.heldOut) append("<p class=\"meta\">Held out for Ai's exam: not written here.</p>")
                else if (r.text.isNotBlank()) append("<details><summary>The duel in words</summary><pre>").append(esc(r.text)).append("</pre></details>")
            }
            append("</section>")
        }
        append("</main></body></html>")
    }

    /** [text] as HTML: headings, lists, paragraphs, and each "[Picture N]" as its picture when it was kept. */
    fun body(text: String, pictures: Map<Int, String>): String = buildString {
        var list = false
        fun closeList() { if (list) { append("</ul>"); list = false } }
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) append("<p>").append(inline(para.toString().trim())).append("</p>")
            para.setLength(0)
        }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val heading = HEADING.matchEntire(line)
            val picture = PICTURE.matchEntire(line.trim())
            when {
                line.isBlank() -> { flush(); closeList() }
                heading != null -> {
                    flush(); closeList()
                    val level = (heading.groupValues[1].length + 2).coerceAtMost(6)
                    append("<h").append(level).append(">").append(inline(heading.groupValues[2])).append("</h").append(level).append(">")
                }
                picture != null -> {
                    flush(); closeList()
                    val n = picture.groupValues[1].toIntOrNull()
                    val src = n?.let { pictures[it] }
                    if (src != null) {
                        append("<figure><img src=\"").append(attr(src)).append("\" alt=\"").append(attr(picture.groupValues[2])).append("\">")
                        if (picture.groupValues[2].isNotBlank()) append("<figcaption>").append(esc(picture.groupValues[2])).append("</figcaption>")
                        append("</figure>")
                    } else {
                        append("<p class=\"meta\">").append(esc(line.trim())).append("</p>")
                    }
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    flush()
                    if (!list) { append("<ul>"); list = true }
                    append("<li>").append(inline(line.trimStart().drop(2))).append("</li>")
                }
                else -> { closeList(); para.append(line).append(' ') }
            }
        }
        flush(); closeList()
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.+)$""")
    private val PICTURE = Regex("""^\[Picture (\d{1,3})(?::\s*(.*))?]$""")

    private fun inline(s: String): String = esc(s).replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(s: String): String = esc(s).replace("\"", "&quot;")

    /** A file name for [course]'s copy: its title, plain. */
    fun fileName(course: Course): String =
        course.label.substringAfter("://").map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }.joinToString("")
            .trim().replace(Regex("\\s+"), " ").take(80).ifBlank { "course" } + ".html"

    private const val STYLE = """
        :root { color-scheme: light dark; --paper: #fbfaf7; --ink: #141414; --soft: #6b6b6b; --rule: #d9d6cf; }
        @media (prefers-color-scheme: dark) { :root { --paper: #141414; --ink: #ecebe7; --soft: #9a9a9a; --rule: #333; } }
        html { background: var(--paper); color: var(--ink); }
        body { margin: 0; font: 17px/1.6 Inter, system-ui, -apple-system, "Segoe UI", sans-serif; }
        main { max-width: 760px; margin: 0 auto; padding: 32px 16px 96px; }
        h1 { font-size: 34px; line-height: 1.2; margin: 0 0 8px; }
        h2 { font-size: 24px; margin: 56px 0 8px; border-top: 1px solid var(--rule); padding-top: 24px; }
        h3, h4, h5, h6 { margin: 28px 0 6px; }
        .meta { color: var(--soft); font-size: 14px; }
        a { color: inherit; }
        figure { margin: 20px 0; }
        img { max-width: 100%; height: auto; display: block; border: 1px solid var(--rule); }
        figcaption { color: var(--soft); font-size: 14px; margin-top: 6px; }
        pre { white-space: pre-wrap; font: 13px/1.5 ui-monospace, "JetBrains Mono", monospace; }
        details { margin: 12px 0; } summary { cursor: pointer; color: var(--soft); }
        nav ol { padding-left: 22px; }
    """
}
