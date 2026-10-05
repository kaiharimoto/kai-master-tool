package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldPaths

/**
 * An address in a world (§4): what the Browser's address line, a link in a page or Thoughts, the Terminal's `open` and
 * Ai's `world_open` all name. Parsed and formatted here only, every path through [WorldPaths.safe]; anything else is
 * [Unknown], a page that says there is no such page.
 *
 * | Address | Page |
 * |---|---|
 * | `world://home`, `world://home?q=hand` | every page of the world, newest first, filtered |
 * | `world://boards/<id>` | a board |
 * | `world://files/<path>` | a file under `files/` as a page |
 * | `world://runs/<t>` | one run: `t` is its event's time in base 36 |
 * | `world://instruments/<name>` | what an instrument answers |
 * | `world://apps/<slug>` | that app's own window (an app is never a tab) |
 */
sealed interface WorldAddress {
    fun format(): String

    data class Home(val q: String? = null) : WorldAddress {
        override fun format(): String = HOME + (q?.trim()?.takeIf { it.isNotEmpty() }?.let { "?q=" + encode(it) }.orEmpty())
    }

    data class Board(val id: String) : WorldAddress {
        override fun format(): String = "${SCHEME}boards/$id"
    }

    data class File(val path: String) : WorldAddress {
        override fun format(): String = "${SCHEME}files/$path"
    }

    data class Run(val t: Long) : WorldAddress {
        override fun format(): String = "${SCHEME}runs/${t.toString(36)}"
    }

    data class Instrument(val name: String) : WorldAddress {
        override fun format(): String = "${SCHEME}instruments/$name"
    }

    data class App(val slug: String) : WorldAddress {
        override fun format(): String = "${SCHEME}apps/$slug"
    }

    /** No such page: [raw] as it was written, and why. */
    data class Unknown(val raw: String, val why: String) : WorldAddress {
        override fun format(): String = raw
    }

    companion object {
        const val SCHEME = "world://"
        const val HOME = "world://home"
        private val ID = Regex("[A-Za-z0-9_-]{1,40}")
        private val NAME = Regex("[a-z0-9_]{1,40}")

        /** [raw] read as an address. The scheme may be left off, as a person types it: `boards/b3` is `world://boards/b3`. */
        fun parse(raw: String): WorldAddress {
            val s = raw.trim()
            val rest = when {
                s.startsWith(SCHEME, ignoreCase = true) -> s.substring(SCHEME.length)
                "://" in s || s.startsWith("//") -> return Unknown(s, "only world:// addresses open here")
                else -> s
            }
            val (path, query) = rest.substringBefore('?') to rest.substringAfter('?', "")
            val kind = path.substringBefore('/').lowercase()
            val tail = path.substringAfter('/', "").trimEnd('/')
            return when (kind) {
                "", "home" -> if (tail.isEmpty()) Home(param(query, "q")) else Unknown(s, "home has no pages under it")
                "boards" -> if (ID.matches(tail)) Board(tail) else Unknown(s, "a board's id is letters, digits, - and _")
                "files" -> WorldPaths.safe(decode(tail))?.let(::File) ?: Unknown(s, "not a path inside the world")
                "runs" -> tail.toLongOrNull(36)?.takeIf { it >= 0 }?.let(::Run) ?: Unknown(s, "a run is named by its time in base 36")
                "instruments" -> tail.lowercase().takeIf { NAME.matches(it) }?.let(::Instrument) ?: Unknown(s, "no such instrument")
                "apps" -> tail.takeIf { AppRef.SLUG.matches(it) }?.let(::App) ?: Unknown(s, "an app's name is a-z, 0-9 and -")
                else -> Unknown(s, "there is no page here")
            }
        }

        private fun param(query: String, key: String): String? =
            query.split('&').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")?.let(::decode)?.takeIf { it.isNotBlank() }

        /** Spaces as `+`, and every character outside a plain set as `%XX` of its UTF-8. */
        fun encode(s: String): String = buildString {
            s.encodeToByteArray().forEach { b ->
                val c = (b.toInt() and 0xFF).toChar()
                when {
                    c == ' ' -> append('+')
                    c.isLetterOrDigit() && c.code < 128 || c in "-_.~" -> append(c)
                    else -> append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
                }
            }
        }

        fun decode(s: String): String {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        out += v.toByte()
                        i += 3
                        continue
                    }
                }
                if (c == '+') out += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { out += it }
                i++
            }
            return out.toByteArray().decodeToString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
