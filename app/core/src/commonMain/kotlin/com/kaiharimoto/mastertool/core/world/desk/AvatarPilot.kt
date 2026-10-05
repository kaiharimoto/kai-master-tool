package com.kaiharimoto.mastertool.core.world.desk

/** What Ai is doing in a world, as the avatar is told it (§5.2): one of these per tool call, from `Worlds`. */
sealed interface AiDoes {
    data object TurnStart : AiDoes

    /** `world_write` to [path] (typed into the Editor); for a made app's code too, before [MakeApp]. */
    data class Write(val path: String) : AiDoes

    /** `world_run`: [label] is the file or `snippet.js`. */
    data class Run(val label: String) : AiDoes

    /** `world_tool`: an instrument; [detail] its few words for the avatar's caption ("50,000 hands", [AvatarStatus.instrument]). */
    data class Tool(val name: String, val detail: String? = null) : AiDoes

    /** `world_show`: the page opened in [tab]; [title] the page's, for the avatar's caption. */
    data class Show(val tab: String, val title: String? = null) : AiDoes

    /** `world_app make` (its code already typed as a [Write]): the new icon, a press, its window. */
    data class MakeApp(val slug: String, val name: String) : AiDoes

    /** `world_app open`. */
    data class OpenApp(val slug: String, val name: String) : AiDoes

    /** `world_app press`: Ai tries its app — the widget it presses. */
    data class Press(val slug: String, val name: String, val widget: String) : AiDoes

    /** Reading: `world_read` (the Editor), the Library — [what] for the taskbar's line ("the guide"). */
    data class Read(val app: AppRef, val what: String) : AiDoes

    /** A question to the person. */
    data object Question : AiDoes

    data object TurnEnd : AiDoes
}

/**
 * Turns what Ai does into the avatar's targets (§5.2, `AvatarPilotTest`), and keeps the queue (§5.3): it dwells at
 * least [DWELL_MS] at a target so the eye finds it; once two routes are waiting, the older are dropped and it goes
 * straight to the newest's end, so it is never behind by more than a hop. Time is the caller's: [next] is asked each
 * frame of the travel loop, and only then.
 */
class AvatarPilot {
    private val waiting = ArrayDeque<List<AvatarTarget>>()
    private var steps = ArrayDeque<AvatarTarget>()
    private var arrivedAt: Long? = null

    /** The target it heads for or stands at now; null asleep at home. */
    var current: AvatarTarget? = null
        private set

    /** The taskbar's line (§2.2). */
    var status: String = ASLEEP
        private set

    /** What it went to do at [current], in words, a face and a sign (§5.7, [AvatarStatus]); null on the walk home. */
    var doing: AvatarStatus? = null
        private set

    /** Asleep at home: drawn as the still `AiMark`, no frame loop (§5.4). */
    var asleep: Boolean = true
        private set

    /** How many routes wait behind the one under way. */
    val behind: Int get() = waiting.size

    /** Queues the route for [does] on [desk], as [FocusPolicy] decided for its window. */
    fun on(does: AiDoes, desk: Desk, decision: FocusDecision = FocusDecision.RAISE) {
        val route = route(does, desk, decision)
        if (route.isEmpty()) return
        asleep = false
        if (current == null && steps.isEmpty()) {
            steps = ArrayDeque(route)
            advance()
            return
        }
        waiting.addLast(route)
        if (waiting.size >= 2) {
            // Ai's tools outran it: straight to the newest, its last place only.
            val newest = waiting.last()
            waiting.clear()
            waiting.addLast(listOf(newest.last()))
            if (steps.isNotEmpty()) steps.clear()
        }
    }

    /** Skip ahead (§5.5): waiting targets dropped; the hop under way finishes (the path's own [AvatarPath.skip]). */
    fun skip() {
        val last = waiting.lastOrNull()?.lastOrNull() ?: steps.lastOrNull()
        waiting.clear()
        steps.clear()
        if (last != null) steps.addLast(last)
    }

    /**
     * The target to head for at [now]; [arrived] says whether the avatar stands at [current]. It moves on once it has
     * dwelt there and something is next; a [AvatarTarget.follow] target is held until then.
     */
    fun next(now: Long, arrived: Boolean): AvatarTarget? {
        val c = current ?: return null
        if (!arrived) {
            arrivedAt = null
            return c
        }
        val at = arrivedAt ?: now.also { arrivedAt = it }
        val more = steps.isNotEmpty() || waiting.isNotEmpty()
        val dwell = c.dwell ?: if (steps.isEmpty()) DWELL_MS else PASS_MS
        if (now - at < dwell) return c
        if (!more) {
            if (c.anchor == Anchor.HOME && status != WAKING) {
                asleep = true
                status = ASLEEP
            }
            return c
        }
        if (steps.isEmpty()) steps = ArrayDeque(waiting.removeFirst())
        advance()
        return current
    }

    private fun advance() {
        val n = steps.removeFirstOrNull() ?: return
        current = n
        arrivedAt = null
        if (n.status.isNotEmpty()) status = n.status
        doing = n.doing
        asleep = false
    }

    companion object {
        const val ASLEEP = "Asleep"
        const val WAKING = "Waking"
        const val DONE = "Done"
        const val WAITING = "Waiting on you"

        /** At least this long at a route's last target, so the eye finds it. */
        const val DWELL_MS = 400L

        /** At a target on the way (an icon pressed, a title bar), a beat: the press's squash. */
        const val PASS_MS = 120L

        /** "Done" stands where it is this long before going home (§5.2). */
        const val DONE_MS = 2_500L

        /** The longest `Worlds.arrive` waits for the avatar to reach an icon before opening its window (§5.3). */
        const val ARRIVE_WAIT_MS = 700L

        /** How long the window's open waits for the avatar: only with Follow on and the avatar shown. */
        fun arriveWait(follow: Boolean, shown: Boolean): Long = if (follow && shown) ARRIVE_WAIT_MS else 0L

        private fun name(path: String) = path.substringAfterLast('/')

        /**
         * The targets for [does] on [desk] (§5.2), [decision] being the focus policy's for its window: a window that will
         * not come forward ([FocusDecision.BEHIND], [FocusDecision.MARK]) is stood on at its taskbar cell.
         */
        fun route(does: AiDoes, desk: Desk, decision: FocusDecision = FocusDecision.RAISE): List<AvatarTarget> {
            fun into(app: AppRef, status: String, inner: List<AvatarTarget>): List<AvatarTarget> {
                val key = app.key
                if (decision != FocusDecision.RAISE) return listOf(AvatarTarget(Anchor.CELL, key, status = status))
                val head = if (desk.isOpen(key)) emptyList() else listOf(AvatarTarget(Anchor.LAUNCH, key, press = true, status = status))
                return head + AvatarTarget(Anchor.TITLE, key, status = status) + inner.map { if (it.status.isEmpty()) it.copy(status = status) else it }
            }
            val editor = BuiltInApp.EDITOR.ref
            val terminal = BuiltInApp.TERMINAL.ref
            val browser = BuiltInApp.BROWSER.ref
            // Every place on the way carries what it went to do (§5.7); home says nothing.
            val doing = AvatarStatus.of(does)
            val route = when (does) {
                AiDoes.TurnStart -> listOf(AvatarTarget(Anchor.HOME, status = WAKING, dwell = PASS_MS))
                is AiDoes.Write -> into(editor, "Writing ${name(does.path)}", listOf(AvatarTarget(Anchor.CARET, editor.key, follow = true)))
                is AiDoes.Run -> into(terminal, "Running ${name(does.label)}", listOf(AvatarTarget(Anchor.LINE, terminal.key, follow = true)))
                is AiDoes.Tool -> into(terminal, "Running ${does.name}", listOf(AvatarTarget(Anchor.LINE, terminal.key, follow = true)))
                is AiDoes.Show -> into(
                    browser, "Showing a page",
                    listOf(
                        AvatarTarget(Anchor.TABS, browser.key),
                        AvatarTarget(Anchor.TAB, browser.key, key = does.tab, press = true),
                        AvatarTarget(Anchor.HEAD, browser.key),
                    ),
                )
                is AiDoes.MakeApp -> {
                    val app = AppRef.Made(does.slug)
                    val status = "Making ${does.name}"
                    if (decision != FocusDecision.RAISE) listOf(AvatarTarget(Anchor.ICON, app.key, status = status))
                    else listOf(AvatarTarget(Anchor.ICON, app.key, press = true, status = status), AvatarTarget(Anchor.TITLE, app.key, status = status))
                }
                is AiDoes.OpenApp -> into(AppRef.Made(does.slug), "Opening ${does.name}", emptyList())
                is AiDoes.Press -> {
                    val app = AppRef.Made(does.slug)
                    into(app, "Trying ${does.name}", listOf(AvatarTarget(Anchor.WIDGET, app.key, key = does.widget, press = true)))
                }
                is AiDoes.Read -> into(does.app, "Reading ${does.what}", listOf(AvatarTarget(Anchor.BODY, does.app.key)))
                AiDoes.Question -> into(BuiltInApp.THOUGHTS.ref, WAITING, listOf(AvatarTarget(Anchor.COMPOSER, BuiltInApp.THOUGHTS.id)))
                AiDoes.TurnEnd -> listOf(
                    AvatarTarget(Anchor.HERE, status = DONE, dwell = DONE_MS),
                    AvatarTarget(Anchor.HOME, status = "Going home"),
                )
            }
            return route.map { if (it.anchor == Anchor.HOME) it else it.copy(doing = doing) }
        }
    }
}
