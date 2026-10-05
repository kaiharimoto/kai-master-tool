package com.kaiharimoto.neue.world.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.apps.AppStore
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.AvatarPilot
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Desk
import com.kaiharimoto.mastertool.core.world.desk.DeskArea
import com.kaiharimoto.mastertool.core.world.desk.DeskCodec
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.FocusArrival
import com.kaiharimoto.mastertool.core.world.desk.FocusDecision
import com.kaiharimoto.mastertool.core.world.desk.FocusPolicy
import com.kaiharimoto.mastertool.core.world.desk.Notice
import com.kaiharimoto.mastertool.core.world.desk.PersonState
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldNotices
import com.kaiharimoto.neue.world.Worlds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The World's desktop (1.1.x, `docs/world/DESKTOP.md` §2, §5, §6): the shell's owned part of [Worlds], as `Duels`
 * keeps its parts beside it (1.0.91). It holds the [Desk] — the windows, the Browser's tabs, the icons' cells — and moves
 * it only through core's reducer ([Desk.step]), so the page, the keys, Ai and the tests all move it the same way; it
 * writes `desk.json` (through [DeskCodec]) a moment after each change. Beside it: the avatar ([DeskAvatarState]), the
 * notices ([WorldNotices]), the apps Ai made, and what only the screen needs (the launcher open, the icon picked, the
 * switcher's strip).
 *
 * Ai reaches it through [arrive] (one per tool call, through [FocusPolicy]) and [turnEnd] (`DeskTidy` puts away what Ai
 * opened and nobody touched). What runs inside a window is not here: the windows' contents are each app's (`DeskApps`).
 */
class WorldDeskState(private val worlds: Worlds) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The desk of the world open; an empty desktop before one is. */
    var desk by mutableStateOf(Desk())
        private set

    /**
     * Where windows stand, as the page last measured it (dp, the desktop between Neue's bar and the taskbar). Plain: the
     * reducer reads it, composition never does — the page passes its own measure down.
     */
    var area: DeskArea = DeskArea.LAPTOP

    /** The World page's top-left in window pixels and the density it is drawn at: what turns layout into the avatar's dp. */
    var origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero
    var density: Float = 1f

    /** The Terminal's ↑ and ↓ (§3.1), for the world open. */
    var terminalHistory = com.kaiharimoto.mastertool.core.world.TerminalHistory()
        private set

    var notices by mutableStateOf(WorldNotices())
        private set

    /** The apps Ai made in this world (`apps/<slug>/app.json`), newest last. */
    var apps by mutableStateOf<List<AppManifest>>(emptyList())
        private set

    /** Which version of each app the person has opened, for the icon's `NEW` tag (this run's memory; §2.1). */
    private val opened = mutableStateMapOf<String, Int>()

    val avatar = DeskAvatarState()

    /** The icon picked out on the desktop (inverted), by its app's key. */
    var selectedIcon by mutableStateOf<String?>(null)

    /** The launcher, open (§2.4): `⊞`, `Alt 0`, the phone's Apps. */
    var launcherOpen by mutableStateOf(false)

    /** The notices' tray, open (§6.3). */
    var trayOpen by mutableStateOf(false)

    /** `Ctrl \`` held: the open windows in the order they will be walked, and the one chosen (§9.1). */
    var switching by mutableStateOf<Switching?>(null)

    /** The phone's full-screen switcher (§2.5). */
    var phoneSwitcher by mutableStateOf(false)

    /** A dialog of the desk's own (New file, Rename, Delete), drawn over the page. */
    var dialog by mutableStateOf<DeskDialog?>(null)

    /** Asks the Browser to put the keyboard in its address (`Ctrl L`): a count the Browser watches. */
    var addressAsked by mutableStateOf(0)

    /** The person pressed or typed on the World page while Ai worked: the windows stop receding until the next turn (§6.2). */
    var recedeBroken by mutableStateOf(false)
        private set

    /** When Ai's turn began (ms), for the clock's `07:48 · 0:42`; 0 when no turn is under way. */
    var turnSince by mutableStateOf(0L)
        private set

    /** The taskbar's cells for open windows that are not pinned, in the order they opened (§2.2). */
    var opening by mutableStateOf<List<String>>(emptyList())
        private set

    /** When the person last pressed, typed or dragged on the World page (ms; 0 never): the focus policy's quiet rule. */
    var lastInput: Long = 0L
        private set

    /** What the person is doing as Ai arrives, beyond [lastInput]: typing in a field, a menu or dialog open. Set by the app. */
    var person: () -> PersonState = { PersonState() }

    /** Whether the World page is on screen now: the avatar walks, and Ai's arrival waits for it, only then. Set by the app. */
    var shown: () -> Boolean = { false }

    private var root: File? = null
    private var saveJob: Job? = null

    // ---- The world's desk on disk --------------------------------------------------------------------------------

    /** The desk of the world in [dir]: `desk.json` read (an empty desktop for a 1.0.97 world), its apps listed. */
    fun load(dir: File) {
        flush()
        root = dir
        desk = DeskCodec.decode(File(dir, DeskCodec.FILE).takeIf { it.isFile }?.readText())
        opening = desk.windows.map { it.app }.filterNot { it in pinned() }
        notices = WorldNotices()
        launcherOpen = false
        switching = null
        selectedIcon = null
        terminalHistory = com.kaiharimoto.mastertool.core.world.TerminalHistory()
        refreshApps()
    }

    /** The apps folder read again (after Ai makes, changes or deletes one: the app host calls this). */
    fun refreshApps() {
        val dir = root ?: return
        apps = runCatching { AppStore(dir).list().sortedBy { it.created } }.getOrDefault(emptyList())
    }

    /** Whether [app] wears its `NEW` tag: Ai made or changed it and the person has not opened that version (§2.1). */
    fun isNew(app: AppManifest): Boolean = app.by == WorldEvent.AI && (opened[app.slug] ?: 0) < app.version

    private fun save() {
        val dir = root ?: return
        val text = DeskCodec.encode(desk)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_AFTER_MS)
            write(dir, text)
        }
    }

    /** What waits to be written is written now: before another world's desk is read. */
    private fun flush() {
        val dir = root ?: return
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        runCatching { writeNow(dir, DeskCodec.encode(desk)) }
    }

    private suspend fun write(dir: File, text: String) = withContext(Dispatchers.IO) { runCatching { writeNow(dir, text) } }

    private fun writeNow(dir: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, DeskCodec.FILE + ".tmp")
        tmp.writeText(text)
        val f = File(dir, DeskCodec.FILE)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    // ---- Moving the desk ---------------------------------------------------------------------------------------

    /** [op] through core's reducer; the windows it closed to make room are said in a notice (§2.3). */
    fun apply(op: DeskOp) {
        val step = desk.step(op, area)
        val before = desk
        desk = step.desk
        step.evicted.forEach { notify(WorldNotices.windowClosed(title(it))) }
        val open = desk.windows.map { it.app }.toSet()
        val pins = pinned()
        opening = (opening.filter { it in open } + desk.windows.map { it.app }.filter { it !in opening && it !in pins }).filterNot { it in pins }
        if (before != desk) save()
    }

    /** The person's own hands on the World page: a press, a key, a drag. Ends the recede (§6.2) and starts the quiet rule. */
    fun touched(at: Long = now()) {
        lastInput = at
        if (desk.working) recedeBroken = true
    }

    /** Opens [ref] for the person, or brings it forward. */
    fun open(ref: AppRef, size: com.kaiharimoto.mastertool.core.world.desk.DeskSize? = null) {
        touched()
        apply(DeskOp.Open(ref, now(), WorldEvent.YOU, size = size ?: sizeOf(ref)))
        launcherOpen = false
        if (ref is AppRef.Made) apps.firstOrNull { it.slug == ref.slug }?.let { opened[it.slug] = it.version }
    }

    /**
     * A taskbar cell's click or an app's `Alt` key (§2.2, §9.1): open it, bring it forward — or, on the window in front,
     * minimise it.
     */
    fun toggle(ref: AppRef) {
        val key = ref.key
        val w = desk.window(key)
        if (w != null && !w.minimised && desk.front == key) {
            touched()
            apply(DeskOp.Minimise(key))
        } else {
            open(ref)
        }
    }

    fun focus(app: String) {
        touched()
        if (desk.front != app) apply(DeskOp.Focus(app, now()))
    }

    fun close(app: String) {
        touched()
        apply(DeskOp.Close(app))
    }

    /** The comfort size of a made app's window, from its manifest. */
    fun sizeOf(ref: AppRef) = (ref as? AppRef.Made)?.let { m -> apps.firstOrNull { it.slug == m.slug }?.size }

    /** The app's name as its title bar and cell say it. */
    fun title(key: String): String = when (val ref = AppRef.parse(key)) {
        is AppRef.BuiltIn -> ref.kind.title
        is AppRef.Made -> apps.firstOrNull { it.slug == ref.slug }?.title ?: ref.slug
        null -> key
    }

    /** The apps on the taskbar this device pins (`WorldPrefs.pinned`), in their order. */
    fun pinned(): List<String> = worlds.prefs().pinned

    // ---- Notices -------------------------------------------------------------------------------------------------

    fun notify(n: Notice?) {
        if (n != null) notices = notices.post(n, now())
    }

    fun noticesNow(f: (WorldNotices) -> WorldNotices) {
        notices = f(notices)
    }

    /** A run or an instrument ended: a notice when the rules say so (§6.3). */
    fun ran(label: String, ok: Boolean, error: String, ms: Long, pages: Int, at: Long) {
        notify(
            if (ok) WorldNotices.runFinished(label, ms, pages, at, terminalInFront = desk.front == BuiltInApp.TERMINAL.id)
            else WorldNotices.runFailed(label, error, at),
        )
    }

    // ---- Ai on the desk --------------------------------------------------------------------------------------------

    /**
     * Pages pinned ([boards]): each opens in its tab (`BrowserTabs.show` — a board pinned again updates its tab in
     * place), and when Ai pinned them it walks to the Browser to open the last (§4, §5.2).
     */
    fun showed(boards: List<String>, by: String) {
        if (boards.isEmpty()) return
        val ai = by == WorldEvent.AI
        val browserFront = desk.front == BuiltInApp.BROWSER.id
        // Ai's pages come forward as the focus policy lets them; the person's own run's open beside the tab in view.
        val raise = if (ai) decide(BuiltInApp.BROWSER.ref) == FocusDecision.RAISE else browserFront
        var tabs = desk.tabs
        boards.forEach { b -> tabs = tabs.show(b, now(), raise = raise, turn = desk.turn, by = by) }
        apply(DeskOp.Tabs(tabs))
        notify(WorldNotices.newPages(boards.size, browserFront || raise, WorldAddress.Board(boards.last()).format()))
        if (!ai) return
        apply(DeskOp.AiShowed)
        val tab = tabs.showing(WorldAddress.Board(boards.last()).format())?.id.orEmpty()
        // At once, never later: an arrival made after the turn ended would start a turn nobody ends.
        arriveNow(BuiltInApp.BROWSER.ref, AiDoes.Show(tab))
    }

    private fun decide(ref: AppRef): FocusDecision {
        val p = person().let { it.copy(lastInput = maxOf(it.lastInput, lastInput), now = now()) }
        return FocusPolicy.decide(FocusArrival(ref.key, ref.key in desk.closedThisTurn), p, worlds.prefs())
    }

    private fun startTurn() {
        if (desk.working) return
        apply(DeskOp.TurnStart(now()))
        recedeBroken = false
        turnSince = now()
        avatar.on(AiDoes.TurnStart, desk)
    }

    /**
     * Ai arrives in [ref] to do [does] (§5.2, §6.1): the focus policy decides Raise, Behind or Mark; the avatar is told
     * its way; with Follow on and the avatar on screen, a window not yet open waits for the avatar to reach its icon
     * (at most [AvatarPilot.ARRIVE_WAIT_MS]) — cause, then effect — and then opens. Behind says where Ai is in a notice.
     */
    suspend fun arrive(ref: AppRef, does: AiDoes) {
        startTurn()
        val decision = decide(ref)
        avatar.on(does, desk, decision)
        if (decision == FocusDecision.RAISE) worlds.comeForward()
        val prefs = worlds.prefs()
        val wait = AvatarPilot.arriveWait(prefs.follow, prefs.avatar && shown())
        if (wait > 0 && decision == FocusDecision.RAISE && !desk.isOpen(ref.key)) {
            withTimeoutOrNull(wait) { while (!avatar.standsAt(ref.key)) delay(16) }
        }
        land(ref, decision)
    }

    /** [arrive] without the wait, for callers that cannot suspend (1.0.97's `arrive(pane)`). */
    fun arriveNow(ref: AppRef, does: AiDoes) {
        startTurn()
        val decision = decide(ref)
        avatar.on(does, desk, decision)
        if (decision == FocusDecision.RAISE) worlds.comeForward()
        land(ref, decision)
    }

    private fun land(ref: AppRef, decision: FocusDecision) {
        apply(DeskOp.Arrive(ref, decision, now(), sizeOf(ref)))
        if (decision == FocusDecision.BEHIND) notify(WorldNotices.aiIsIn(ref.key, title(ref.key)))
    }

    /** Ai made or changed the app [slug] this turn (the app host calls this): its window is where the answer is. */
    fun aiMadeApp(slug: String, name: String, made: Boolean) {
        refreshApps()
        apply(DeskOp.AiMadeApp(slug))
        if (made) notify(WorldNotices.appMade(name, slug))
    }

    /** Ai asked the person something (§5.2): Thoughts' composer, and a notice. */
    fun waiting() {
        if (!desk.working) return
        avatar.on(AiDoes.Question, desk, decide(BuiltInApp.THOUGHTS.ref))
        notify(WorldNotices.waiting())
    }

    /** Ai's turn ended: `DeskTidy` puts away what it opened and the person never touched (§6.4); the avatar goes home. */
    fun turnEnd() {
        if (!desk.working) return
        apply(DeskOp.TurnEnd(now()))
        avatar.on(AiDoes.TurnEnd, desk)
        turnSince = 0L
        recedeBroken = false
    }

    // ---- The switcher's strip (`Ctrl \``) ----------------------------------------------------------------------------

    /**
     * `Ctrl \`` (or with Shift): the first press snapshots the open windows, most recent first, and picks the next; more
     * presses walk on; letting go of Ctrl ([commitSwitch]) brings the chosen one forward. Without a held Ctrl (the palette,
     * a menu) the choice is made at once.
     */
    fun cycle(forward: Boolean, held: Boolean) {
        val s = switching
        if (s == null) {
            val order = desk.recent.map { it.app }
            if (order.isEmpty()) return
            val start = if (desk.front != null && order.size > 1) (if (forward) 1 else order.size - 1) else 0
            switching = Switching(order, start, now())
        } else {
            val n = s.order.size
            switching = s.copy(at = ((s.at + if (forward) 1 else -1) % n + n) % n)
        }
        if (!held) commitSwitch()
    }

    fun commitSwitch() {
        val s = switching ?: return
        switching = null
        s.order.getOrNull(s.at)?.let { app -> AppRef.parse(app)?.let(::open) }
    }

    companion object {
        /** `desk.json` is written this long after the last change. */
        const val SAVE_AFTER_MS = 400L

        /** The strip of open windows shows once `Ctrl \`` has been held this long (§9.1). */
        const val STRIP_AFTER_MS = 200L

        fun now() = System.currentTimeMillis()
    }
}

/** The switcher's strip: the windows in the order walked, the one chosen, and when the walk began. */
data class Switching(val order: List<String>, val at: Int, val since: Long)

/** The desk's own dialogs. */
sealed interface DeskDialog {
    data object NewFile : DeskDialog
    data class Rename(val path: String) : DeskDialog
    data class Delete(val path: String) : DeskDialog
}
