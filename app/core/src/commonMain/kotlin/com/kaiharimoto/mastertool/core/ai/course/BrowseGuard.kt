package com.kaiharimoto.mastertool.core.ai.course

import kotlinx.serialization.Serializable

/**
 * One thing on a page the study could press, as the browser reports it: what it is, what it says and where it leads.
 * [ref] is the browser's handle for it, good until the page changes.
 */
@Serializable
data class PageElement(
    val ref: Int,
    val tag: String,
    val text: String = "",
    val href: String = "",
    /** An input's or a button's type: submit, password, checkbox… */
    val type: String = "",
    val role: String = "",
    /** Inside a form: pressing it may send the form. */
    val inForm: Boolean = false,
    /** A link that downloads a file. */
    val download: Boolean = false,
    /** autocomplete, name and aria words, lowercase: how a payment or a login field gives itself away. */
    val hints: String = "",
)

/**
 * What the study may do in the browser, decided before it is done (the course study's red lines). The browser is the
 * person's logged-in Metafy session, so a page that tells Ai to buy, post or follow must find nothing that does it:
 * - it stays on the course's hosts, over https — and the DuelingBook replay pages its chapters link to;
 * - it reads and presses links and buttons, and never types — the login is the person's alone;
 * - it never presses what buys, pays, subscribes, tips, posts, messages, comments, reviews, rates, reports, follows,
 *   deletes, shares or signs out, never sends a form, never downloads.
 */
object BrowseGuard {
    /** The hosts [course] may load from: its own list, and always the start's host. */
    fun hosts(course: Course): Set<String> = (course.hosts + host(course.start)).map { it.lowercase().removePrefix("www.") }.filter { it.isNotBlank() }.toSet()

    fun host(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()

    /** Why [url] may not be opened for [course], or null when it may. */
    fun openRefusal(url: String, course: Course): String? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return "Only https pages are opened: $u"
        val h = host(u).removePrefix("www.")
        if (h.isBlank()) return "Not an address: $u"
        // A DuelingBook replay a chapter links to is read too: its page, and nothing else on that site (1.1.41).
        if (DbReplays.isReplay(u)) return null
        val allowed = hosts(course)
        if (allowed.none { h == it || h.endsWith(".$it") }) {
            return "$h is not one of this course's sites (${allowed.joinToString()}). The study stays on the course."
        }
        return null
    }

    /** Whether [href] leads deeper into [course]'s own guide: under its start's path, on its host. */
    fun inGuide(href: String, course: Course): Boolean {
        val url = Chapters.absolute(href, course.start) ?: return false
        return url.startsWith("https://", ignoreCase = true) && under(url, course.start)
    }

    private fun under(url: String, start: String): Boolean {
        fun place(u: String) = u.substringAfter("://").lowercase().removePrefix("www.").substringBefore('?').substringBefore('#').trimEnd('/')
        return place(url).startsWith(place(start) + "/")
    }

    private val FORBIDDEN = Regex(
        """\b(buy|purchase|checkout|check\s*out|pay|payment|subscribe|subscription|upgrade|unlock|tip|donate|gift|cart|order|""" +
            """post|reply|comment|message|send|chat|review|rate|rating|report|flag|follow|unfollow|like|share|invite|""" +
            """delete|remove|cancel|unsubscribe|refund|log\s*out|logout|sign\s*out|signout|settings|account|billing|""" +
            """edit|save\s+changes|submit|book|schedule|claim|redeem)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SECRET_FIELD = Regex("""\b(password|passwd|cc-|card|cvc|cvv|iban|otp|one-time|2fa|email|login|username)""", RegexOption.IGNORE_CASE)

    /** Why [e] may not be pressed in [course], or null when it may. */
    fun clickRefusal(e: PageElement, course: Course): String? {
        val words = (e.text + " " + e.hints).trim()
        val tag = e.tag.lowercase()
        val type = e.type.lowercase()
        return when {
            tag == "input" && type !in setOf("checkbox", "radio", "button") -> "Fields are never typed in or pressed: the study only reads and follows links."
            tag == "textarea" || tag == "select" -> "Fields are never typed in or pressed."
            SECRET_FIELD.containsMatchIn(e.hints) && tag != "a" -> "That is a login or payment field: never touched."
            e.download -> "Downloads are never started."
            // A link deeper into the guide itself is reading, whatever its title says ("Ordering your end board").
            tag == "a" && inGuide(e.href, course) -> null
            type == "submit" || (e.inForm && tag != "a") -> "Forms are never sent."
            FORBIDDEN.containsMatchIn(words) -> "“${words.take(60)}” could buy, post, change or end something: the study never presses it."
            e.href.isNotBlank() && !e.href.startsWith("#") && !e.href.startsWith("javascript:", ignoreCase = true) ->
                openRefusal(e.href, course)
            else -> null
        }
    }
}
