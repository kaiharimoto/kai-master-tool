package com.kaiharimoto.neue.world.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BrowserTabs
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskCodec
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.world.Worlds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The World's Browser, as state (`docs/world/DESKTOP.md` §4): the open world's tabs ([BrowserTabs], core's reducer) and
 * what moves them — the person's clicks and keys, a link in a page, Ai's `world_show` and `world_open`. An owned part of
 * [Worlds] (`world.browser`), as `Duels` keeps its parts.
 *
 * The tabs live in the world's `desk.json` beside the windows. Until the desktop keeps the whole desk itself, the
 * Browser reads and writes their part of the file on its own; the desk takes over by setting [store] and calling
 * [adopt] with the tabs it read.
 */
class WorldBrowser internal constructor(private val world: Worlds) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()

    var tabs by mutableStateOf(BrowserTabs())
        private set

    /** Bumped to put the keyboard in the address line (`Ctrl L`). */
    var addressTick by mutableStateOf(0)

    /** Where the tabs go when they change: the desk's own reducer (`DeskOp.Tabs`), once it keeps them. Null: `desk.json` here. */
    var store: ((BrowserTabs) -> Unit)? = null

    /** Ai's turn under way in this world, for a tab it opens (`DeskTidy` puts away its tabs from earlier turns). */
    var turn: () -> Int = { 0 }

    /** The world folder whose `desk.json` the tabs are kept in. */
    private var folder: File? = null

    /** The tabs of the world in [dir], read from its `desk.json` (none for a 1.0.97 world: an empty Browser). */
    internal fun load(dir: File) {
        folder = dir
        // Read at once: `desk.json` is a few kilobytes, and a page shown the moment the world opens must land on these tabs.
        tabs = runCatching { DeskCodec.decode(File(dir, DeskCodec.FILE).takeIf { it.isFile }?.readText()).tabs }.getOrDefault(BrowserTabs())
    }

    /** The desk read the tabs itself: these are the Browser's now. */
    fun adopt(read: BrowserTabs) {
        tabs = read
    }

    private fun set(next: BrowserTabs) {
        if (next == tabs) return
        tabs = next
        val keep = store
        if (keep != null) {
            keep(next)
            return
        }
        val dir = folder ?: return
        scope.launch {
            io.withLock {
                withContext(Dispatchers.IO) {
                    val f = File(dir, DeskCodec.FILE)
                    val desk = DeskCodec.decode(f.takeIf { it.isFile }?.readText()).copy(tabs = next)
                    dir.mkdirs()
                    val tmp = File(dir, DeskCodec.FILE + ".tmp")
                    tmp.writeText(DeskCodec.encode(desk))
                    if (!tmp.renameTo(f)) {
                        f.writeText(tmp.readText())
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    val current get() = tabs.current

    /** A new tab on [address] (`+`, `Ctrl T`, a middle-click on a link). */
    fun open(address: String = WorldAddress.HOME, by: String = WorldEvent.YOU, select: Boolean = true) {
        set(tabs.open(address, now(), by, if (by == WorldEvent.AI) turn() else 0, select))
    }

    /**
     * [raw] opened here: an app's address opens its window (an app is never a tab); a page goes in the selected tab, or a
     * new one when there is none. The Browser's own window is the desk's to bring up ([Worlds.apps]'s windows).
     */
    fun go(raw: String, by: String = WorldEvent.YOU) {
        val address = WorldAddress.parse(raw)
        if (address is WorldAddress.App) {
            world.apps.windows.open(AppRef.Made(address.slug), by)
            return
        }
        val text = address.format()
        val tab = tabs.current
        if (tab == null) open(text, by) else set(tabs.go(tab.id, text))
    }

    /** [raw] in a new tab beside the selected one (a middle-click). */
    fun goNew(raw: String) {
        val address = WorldAddress.parse(raw)
        if (address is WorldAddress.App) return go(raw)
        open(address.format(), select = false)
    }

    fun back() = tabs.current?.let { set(tabs.back(it.id)) }
    fun forward() = tabs.current?.let { set(tabs.forward(it.id)) }
    fun select(id: String) = set(tabs.select(id))
    fun close(id: String) = set(tabs.close(id))
    fun step(n: Int) = set(tabs.step(n))
    fun move(id: String, to: Int) = set(tabs.move(id, to))
    fun keep(id: String, kept: Boolean) = set(tabs.keep(id, kept))

    /** `Ctrl W` in the Browser: the selected tab closes. False when there was none (the window closes instead). */
    fun closeCurrent(): Boolean {
        val t = tabs.current ?: return false
        close(t.id)
        return true
    }

    /**
     * A board pinned (`world_show`, a run, an instrument, an app's `ygo.show`): its page opens in a tab, or the tab already
     * on it is updated in place. [raise] selects it — Ai's tab while the person is not busy, or the person's own run.
     */
    fun shown(board: String, raise: Boolean, by: String = WorldEvent.AI) {
        set(tabs.show(board, now(), raise, if (by == WorldEvent.AI) turn() else 0, by))
        if (by == WorldEvent.AI) onAiShowed()
    }

    /** Ai opened or changed a page this turn: the desktop's `DeskOp.AiShowed` (the Browser is where the answer is, §6.4). */
    var onAiShowed: () -> Unit = {}

    /** Brings the Browser's window up for [by], on a page if given. */
    fun show(address: String? = null, by: String = WorldEvent.YOU) {
        if (address != null) go(address, by)
        world.apps.windows.open(BuiltInApp.BROWSER.ref, by)
    }
}

/**
 * The Browser's keys (§9.1: `Ctrl T`, `Ctrl L`, `Ctrl Tab`, `Ctrl Shift Tab`, `Alt ←`/`→`, and `Ctrl W` on a tab): true
 * when [action] was the Browser's and it acted. The desktop's key router calls this while the Browser is in front.
 */
fun WorldBrowser.key(action: DeskAction): Boolean {
    when (action) {
        DeskAction.WORLD_TAB_NEW -> open()
        DeskAction.WORLD_TAB_ADDRESS -> addressTick++
        DeskAction.WORLD_TAB_NEXT -> step(1)
        DeskAction.WORLD_TAB_PREVIOUS -> step(-1)
        DeskAction.WORLD_TAB_BACK -> back()
        DeskAction.WORLD_TAB_FORWARD -> forward()
        DeskAction.WORLD_CLOSE -> return closeCurrent()
        else -> return false
    }
    return true
}
