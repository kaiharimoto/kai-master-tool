package com.kaiharimoto.mastertool.core.ai.web

import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia

/**
 * What the rulings sources' week-long cache keeps and serves (1.0.99, the red team: "a malformed reply from a
 * rulings source sits in the cache for a week"). A reply is kept only when it is no API error and, when the caller
 * says how it reads one, only when it reads; a kept reply is served only while it is young and still reads — so one
 * an older build kept unread is fetched again rather than served for the rest of its week. A caller that names no
 * reader keeps what it kept before.
 */
object ReplyCache {
    const val WEEK: Long = 7L * 24 * 60 * 60 * 1000

    /** Whether [body], just fetched, is kept: never an API error (`Yugipedia.isError`), and readable by [readable] when given. */
    fun keep(body: String, readable: ((String) -> Boolean)? = null): Boolean = !Yugipedia.isError(body) && reads(body, readable)

    /** Whether a kept [body], [age] milliseconds old, is served instead of asking again. */
    fun serve(body: String, age: Long, readable: ((String) -> Boolean)? = null): Boolean = age < WEEK && reads(body, readable)

    /** A reader that throws has not read it. */
    private fun reads(body: String, readable: ((String) -> Boolean)?): Boolean =
        readable == null || runCatching { readable(body) }.getOrDefault(false)
}
