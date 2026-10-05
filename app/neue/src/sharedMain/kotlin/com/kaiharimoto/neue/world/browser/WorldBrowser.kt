package com.kaiharimoto.neue.world.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BrowserTabs
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.world.Worlds

/**
 * The World's Browser, as state (`docs/world/DESKTOP.md` §4): what moves its tabs — the person's clicks and keys, a link
 * in a page, Ai's `world_open` — over core's [BrowserTabs]. An owned part of [Worlds] (`world.browser`), as `Duels` keeps
 * its parts. The tabs themselves are the desk's (`desk.json`, `DeskOp.Tabs`): one value, kept and tidied with the windows.
 */
class WorldBrowser internal constructor(private val world: Worlds) {
    private val desk get() = world.desk

    val tabs: BrowserTabs get() = desk.desk.tabs

    /** Every tab as pictures, open in place of the page (the strip's count). */
    var overview by mutableStateOf(false)

    /** The tab a resting pointer (a held finger) shows as a picture. */
    var peek by mutableStateOf<String?>(null)

    /** Where each tab stands in the strip (px from its left), written from layout: where its picture opens. Plain. */
    internal val tabX = HashMap<String, Float>()

    /** Bumped to put the keyboard in the address line (`Ctrl L`): the desk's counter. */
    val addressTick: Int get() = desk.addressAsked

    private fun set(next: BrowserTabs) {
        if (next != tabs) desk.apply(DeskOp.Tabs(next))
    }

    private fun now() = System.currentTimeMillis()

    val current get() = tabs.current

    /** A new tab on [address] (`+`, `Ctrl T`, a middle-click on a link). */
    fun open(address: String = WorldAddress.HOME, by: String = WorldEvent.YOU, select: Boolean = true) {
        set(tabs.open(address, now(), by, if (by == WorldEvent.AI) desk.desk.turn else 0, select))
    }

    /** [raw] opened here: an app's address opens its window (an app is never a tab); a page goes in the selected tab, or a new one. */
    fun go(raw: String, by: String = WorldEvent.YOU) {
        val address = WorldAddress.parse(raw)
        if (address is WorldAddress.App) {
            world.apps.windows.open(AppRef.Made(address.slug), by)
            return
        }
        val text = address.format()
        val there = tabs.showing(text)
        val tab = tabs.current
        when {
            there != null -> set(tabs.select(there.id))
            tab == null -> open(text, by)
            else -> set(tabs.go(tab.id, text))
        }
    }

    /** [raw] in a new tab beside the selected one (a middle-click). */
    fun goNew(raw: String) {
        val address = WorldAddress.parse(raw)
        if (address is WorldAddress.App) return go(raw)
        open(address.format(), select = false)
    }

    fun back() = tabs.current?.let { set(tabs.back(it.id)) }
    fun forward() = tabs.current?.let { set(tabs.forward(it.id)) }
    fun select(id: String) {
        overview = false
        set(tabs.select(id))
    }
    fun close(id: String) = set(tabs.close(id))
    fun step(n: Int) = set(tabs.step(n))
    fun move(id: String, to: Int) = set(tabs.move(id, to))
    fun keep(id: String, kept: Boolean) = set(tabs.keep(id, kept))

    /** `Ctrl 1`–`Ctrl 9`: the [n]th tab, the last for 9. */
    fun jump(n: Int) {
        overview = false
        set(tabs.jump(n))
    }

    /** `Ctrl W` in the Browser: the selected tab closes. False when there was none (the window closes instead). */
    fun closeCurrent(): Boolean {
        val t = tabs.current ?: return false
        close(t.id)
        return true
    }

    /** A board's page in a tab without Ai walking anywhere (an instrument's *Open pages*): updated in place when one is on it. */
    fun shown(board: String, raise: Boolean, by: String = WorldEvent.YOU, group: String? = null) {
        set(tabs.show(board, now(), raise, if (by == WorldEvent.AI) desk.desk.turn else 0, by, group))
    }

    /** Brings the Browser up on [address]: the person's window opens; Ai's goes through the focus policy and the avatar. */
    fun show(address: String? = null, by: String = WorldEvent.YOU) {
        if (address != null) go(address, by)
        if (by == WorldEvent.AI) {
            desk.arriveNow(BuiltInApp.BROWSER.ref, AiDoes.Show(tabs.selected.orEmpty()))
        } else {
            desk.open(BuiltInApp.BROWSER.ref)
        }
    }
}

/**
 * The Browser's keys (§9.1: `Ctrl T`, `Ctrl L`, `Ctrl Tab`, `Ctrl Shift Tab`, `Alt ←`/`→`, and `Ctrl W` on a tab): true
 * when [action] was the Browser's and it acted. The page's key router does the same on the desk's tabs.
 */
fun WorldBrowser.key(action: DeskAction): Boolean {
    when (action) {
        DeskAction.WORLD_TAB_NEW -> open()
        DeskAction.WORLD_TAB_NEXT -> step(1)
        DeskAction.WORLD_TAB_PREVIOUS -> step(-1)
        DeskAction.WORLD_TAB_BACK -> back()
        DeskAction.WORLD_TAB_FORWARD -> forward()
        DeskAction.WORLD_CLOSE -> return closeCurrent()
        DeskAction.WORLD_TAB_1, DeskAction.WORLD_TAB_2, DeskAction.WORLD_TAB_3, DeskAction.WORLD_TAB_4, DeskAction.WORLD_TAB_5,
        DeskAction.WORLD_TAB_6, DeskAction.WORLD_TAB_7, DeskAction.WORLD_TAB_8, DeskAction.WORLD_TAB_9,
        -> jump(com.kaiharimoto.neue.world.tabNumber(action))
        else -> return false
    }
    return true
}
