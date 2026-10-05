package com.kaiharimoto.mastertool.core.world.desk

/** The kinds of notice (§6.3), each with its one action's words. */
enum class NoticeKind(val action: String) {
    APP_MADE("Open"),
    RUN_FINISHED("Show"),
    RUN_FAILED("Show in Terminal"),
    NEW_PAGES("Open"),
    AI_IS_IN("Show"),
    WAITING("Answer"),
    APP_FAILED("Show code"),
    WINDOW_CLOSED("Show"),
}

/**
 * One notice: its [kind], its words ([title] and [text]), and what its action opens ([address] — a `world://` address,
 * or an app's key for [NoticeKind.AI_IS_IN]). [count] is how many it stands for once coalesced ("3 new pages").
 */
data class Notice(
    val id: Long,
    val kind: NoticeKind,
    val title: String,
    val text: String = "",
    val address: String? = null,
    val at: Long = 0L,
    val count: Int = 1,
    val read: Boolean = false,
) {
    val action: String get() = kind.action
}

/**
 * The World's notices (§6.3, `WorldNoticesTest`): derived from the world's events and live state, never stored apart
 * from `log.jsonl`. One toast at a time over the tray's corner for [TOAST_MS] (held while the pointer is on it), at most
 * one every [GAP_MS] — the rest go straight to the count. The tray keeps the last [MAX_TRAY], newest first. New pages
 * coalesce into one notice while it is unread.
 *
 * A pure value: [post], [tick], [hold], [dismiss], [readAll] and [clear] each return the next.
 */
data class WorldNotices(
    val tray: List<Notice> = emptyList(),
    /** The toast on screen, or null. */
    val toast: Notice? = null,
    val toastSince: Long = 0L,
    /** The pointer is on the toast: it stays. */
    val held: Boolean = false,
    /** When the last toast was shown, for the one-every-[GAP_MS] rule. */
    val lastToast: Long = Long.MIN_VALUE / 2,
    val nextId: Long = 1L,
) {
    /** The tray's count: unread notices. Nothing to show when 0. */
    val unread: Int get() = tray.count { !it.read }

    /** [n] posted at [now]: into the tray, and shown as the toast unless one is up or one was shown under [GAP_MS] ago. */
    fun post(n: Notice, now: Long): WorldNotices {
        if (n.kind == NoticeKind.NEW_PAGES) {
            val open = tray.firstOrNull { it.kind == NoticeKind.NEW_PAGES && !it.read }
            if (open != null) {
                val total = open.count + n.count
                val merged = open.copy(count = total, title = pages(total), at = now, address = n.address ?: open.address)
                val t = listOf(merged) + tray.filterNot { it.id == open.id }
                return copy(tray = t, toast = if (toast?.id == open.id) merged else toast)
            }
        }
        val stamped = n.copy(id = nextId, at = now, title = if (n.kind == NoticeKind.NEW_PAGES) pages(n.count) else n.title)
        val t = (listOf(stamped) + tray).take(MAX_TRAY)
        val show = toast == null && now - lastToast >= GAP_MS
        return copy(tray = t, nextId = nextId + 1, toast = if (show) stamped else toast, toastSince = if (show) now else toastSince, lastToast = if (show) now else lastToast)
    }

    /** The clock moves: a toast past [TOAST_MS] and not held goes (it stays in the tray, unread). */
    fun tick(now: Long): WorldNotices =
        if (toast != null && !held && now - toastSince >= TOAST_MS) copy(toast = null) else this

    /** The pointer came onto the toast or left it. */
    fun hold(on: Boolean, now: Long): WorldNotices = if (on) copy(held = true) else copy(held = false, toastSince = now)

    /** Esc, or the toast's ✕: the toast goes. */
    fun dismiss(): WorldNotices = copy(toast = null, held = false)

    /** The tray opened: everything in it read. */
    fun readAll(): WorldNotices = copy(tray = tray.map { it.copy(read = true) })

    /** One notice acted on: read, and its toast gone. */
    fun acted(id: Long): WorldNotices =
        copy(tray = tray.map { if (it.id == id) it.copy(read = true) else it }, toast = toast?.takeIf { it.id != id })

    fun clear(): WorldNotices = copy(tray = emptyList(), toast = null, held = false)

    /**
     * [app] came to the front: the notices it answers by being looked at are read, and their toast goes — "6 new pages"
     * standing over the Browser that shows them was noise ([answers]).
     */
    fun looked(app: String): WorldNotices {
        val shown = toast?.let { answers(it, app) } == true
        if (!shown && tray.none { !it.read && answers(it, app) }) return this
        return copy(
            tray = tray.map { if (!it.read && answers(it, app)) it.copy(read = true) else it },
            toast = if (shown) null else toast,
            held = if (shown) false else held,
        )
    }

    companion object {
        const val TOAST_MS = 5_000L
        const val GAP_MS = 2_000L
        const val MAX_TRAY = 50

        /** A run over this long earns a notice even with the Terminal in front. */
        const val LONG_RUN_MS = 3_000L

        private fun pages(n: Int) = if (n == 1) "1 new page" else "$n new pages"

        /**
         * Whether [app] in front answers [n]: the Browser its new pages, the Terminal a run's end, Thoughts a question,
         * the window Ai is in its "Ai is in …", an app Ai made its own window. A failed app still wants its code read.
         */
        fun answers(n: Notice, app: String): Boolean = when (n.kind) {
            NoticeKind.NEW_PAGES -> app == BuiltInApp.BROWSER.id
            NoticeKind.RUN_FINISHED, NoticeKind.RUN_FAILED -> app == BuiltInApp.TERMINAL.id
            NoticeKind.WAITING -> app == BuiltInApp.THOUGHTS.id
            NoticeKind.AI_IS_IN -> n.address == app
            NoticeKind.APP_MADE -> (n.address?.let(WorldAddress::parse) as? WorldAddress.App)?.let { AppRef.Made(it.slug).key } == app
            NoticeKind.APP_FAILED, NoticeKind.WINDOW_CLOSED -> false
        }

        // ---- The rules (§6.3): whether a thing that happened is a notice, and its words. -------------------------

        fun appMade(name: String, slug: String) =
            Notice(0, NoticeKind.APP_MADE, "Ai made an app", "“$name”", WorldAddress.App(slug).format())

        /** A run that ended well: a notice when the Terminal is not in front, or it took over [LONG_RUN_MS]. */
        fun runFinished(label: String, ms: Long, pages: Int, runAt: Long, terminalInFront: Boolean): Notice? {
            if (terminalInFront && ms <= LONG_RUN_MS) return null
            val words = buildString {
                append(label).append(" · ").append(seconds(ms))
                if (pages > 0) append(" · ").append(if (pages == 1) "1 page" else "$pages pages")
            }
            return Notice(0, NoticeKind.RUN_FINISHED, "Run finished", words, WorldAddress.Run(runAt).format())
        }

        fun runFailed(label: String, error: String, runAt: Long) =
            Notice(0, NoticeKind.RUN_FAILED, "Run failed", "$label: " + error.lineSequence().firstOrNull().orEmpty().take(160), WorldAddress.Run(runAt).format())

        /** Pages Ai opened while the Browser was not in front: coalesced into one notice. */
        fun newPages(count: Int, browserInFront: Boolean, address: String? = null): Notice? =
            if (browserInFront || count <= 0) null else Notice(0, NoticeKind.NEW_PAGES, pages(count), address = address ?: WorldAddress.HOME, count = count)

        /** A [FocusDecision.BEHIND] arrival: where Ai is, with Show. */
        fun aiIsIn(app: String, title: String) = Notice(0, NoticeKind.AI_IS_IN, "Ai is in the $title", address = app)

        fun waiting() = Notice(0, NoticeKind.WAITING, "Ai is waiting on you", address = BuiltInApp.THOUGHTS.id)

        /** An app's call that threw: "Hand odds: on(press draw) failed at line 41". */
        fun appFailed(name: String, slug: String, call: String, line: Int?) =
            Notice(0, NoticeKind.APP_FAILED, "An app could not run", "$name: $call failed" + (line?.let { " at line $it" }.orEmpty()), WorldAddress.App(slug).format())

        /** A window closed to make room for a 13th (§2.3). */
        fun windowClosed(title: String) = Notice(0, NoticeKind.WINDOW_CLOSED, "Closed $title to make room", "At most ${Desk.MAX_WINDOWS} windows are open at once.")

        /** `1.2 s`, `412 ms`. */
        fun seconds(ms: Long): String = if (ms < 1_000) "$ms ms" else "${ms / 1000}.${(ms % 1000) / 100} s"
    }
}
