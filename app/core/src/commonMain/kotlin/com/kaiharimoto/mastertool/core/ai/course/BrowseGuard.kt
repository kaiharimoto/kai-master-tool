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

    /**
     * [url]'s host, lowercase, port aside; empty — refused by every check — when the address could lead the browser
     * somewhere else than it seems to: a user part ("evil.com\@metafy.gg" is evil.com to Chrome, which reads `\` as
     * `/`), a space or a control character in the authority, or a scheme that is not letters alone.
     */
    fun host(url: String): String {
        val u = url.trim()
        val at = u.indexOf("://")
        if (at <= 0) return ""
        if (!u.substring(0, at).all { it in 'a'..'z' || it in 'A'..'Z' }) return ""
        val rest = u.substring(at + 3)
        // Read both ways — "\" a separator, as Chrome reads it, or not — and refused where they could disagree.
        fun upTo(stops: String) = rest.indexOfFirst { it in stops }.let { if (it < 0) rest else rest.substring(0, it) }
        val authority = upTo("/\\?#")
        val loose = upTo("/?#")
        if ((authority + loose).any { it == '@' || it == '\\' || it.isWhitespace() || it.isISOControl() }) return ""
        return authority.substringBefore(':').lowercase()
    }

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

    /**
     * Why the browser, having navigated or been pressed on, may not stay where it [landed] (the address it ended on), or
     * null when it may: an https page on one of [course]'s sites — or, reading a [replay], a DuelingBook replay page. A
     * link or a redirect that left the course is caught here, after the fact, and the study goes back.
     */
    fun landedRefusal(landed: String, course: Course, replay: Boolean = false): String? {
        val u = landed.trim()
        val h = host(u).removePrefix("www.")
        val allowed = hosts(course)
        val https = u.startsWith("https://", ignoreCase = true)
        if (https && h.isNotBlank() && allowed.any { h == it || h.endsWith(".$it") }) return null
        if (https && replay && DbReplays.isReplay(u)) return null
        val where = h.ifBlank { u.take(80).ifBlank { "nowhere" } }
        return "The page ended up at $where, not on this course's sites (${allowed.joinToString()}" +
            (if (replay) ", or a DuelingBook replay" else "") + "). The study stays on the course."
    }

    private val LOGIN_WORDS = setOf("login", "log-in", "signin", "sign-in", "sign_in", "signup", "sign-up", "register", "auth", "sso", "session", "sessions")
    private val RETURN_KEYS = setOf("redirect", "redirect_to", "redirect_uri", "redirect_url", "return_to", "returnto", "return_url", "returnurl", "next")

    /**
     * Whether the page at [url] asks for a login: it has a password field ([hasPassword]), a path segment that is a login
     * word ("/login", "/auth/…", "/sign-in"), or a query sending the person back somewhere afterwards ("?next=", "?redirect=")
     * beside a segment that begins with one ("/authorize?redirect_uri=…", "/login.php?next=/guide"). The study never logs
     * in: the person does, and the study waits.
     */
    fun loginPage(url: String, hasPassword: Boolean): Boolean {
        if (hasPassword) return true
        val rest = url.trim().let { if ("://" in it) it.substringAfter("://") else it }
        val tail = rest.dropWhile { it != '/' && it != '\\' && it != '?' && it != '#' }
        val path = tail.substringBefore('?').substringBefore('#')
        val query = tail.substringAfter('?', "").substringBefore('#')
        val segments = path.split('/', '\\').map { it.lowercase() }.filter { it.isNotEmpty() }
        if (segments.any { it in LOGIN_WORDS }) return true
        val pairs = query.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=').lowercase() to it.substringAfter('=', "").lowercase() }
        if (pairs.none { it.first in RETURN_KEYS }) return false
        fun begins(s: String) = LOGIN_WORDS.any { s.startsWith(it) }
        val inValues = pairs.flatMap { (_, v) -> v.replace("%2f", "/").split('/', '?', '&', '=') }.any { it in LOGIN_WORDS }
        return segments.any(::begins) || inValues
    }

    /** Whether [href] leads deeper into [course]'s own guide: under its start's path, on its host. */
    fun inGuide(href: String, course: Course): Boolean {
        val url = Chapters.absolute(href, course.start) ?: return false
        if (host(url).isBlank()) return false
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
