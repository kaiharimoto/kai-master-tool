package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent

/**
 * Put away at the end of a turn (§6.4, `DeskTidyTest`). Every window Ai opened in the turn that the person never pressed
 * in closes, **except where the answer is**: the Browser if Ai opened a page this turn, the apps Ai made or changed, or —
 * when it did neither — the last window it worked in. The answer stays in front. Nothing the person opened is touched,
 * nor anything kept. In the Browser, Ai's unkept tabs from earlier turns beyond the newest [KEEP_OLD_AI_TABS] close.
 */
object DeskTidy {
    const val KEEP_OLD_AI_TABS = 5

    /** The windows where the answer is, for [d]'s turn. */
    fun answer(d: Desk): Set<String> {
        val f = d.facts
        val made = f.apps.map { AppRef.Made(it).key }.filter { d.isOpen(it) }
        val browser = BuiltInApp.BROWSER.id.takeIf { f.pages && d.isOpen(it) }
        val found = listOfNotNull(browser) + made
        return if (found.isNotEmpty()) found.toSet() else setOfNotNull(f.last?.takeIf { d.isOpen(it) })
    }

    fun endTurn(d: Desk): Desk {
        val keep = answer(d)
        val goes = d.windows.filter { w -> w.by == WorldEvent.AI && w.turn == d.turn && !w.touched && !w.kept && w.app !in keep }
        var next = goes.fold(d) { acc, w -> DeskReducer.close(acc, w.app, person = false) }
        // The answer in front: the newest of it Ai was in, else the first.
        val front = keep.firstOrNull { it == d.facts.last } ?: keep.firstOrNull()
        if (front != null && next.window(front)?.minimised == false) {
            next = next.copy(windows = next.windows.filterNot { it.app == front } + next.window(front)!!, front = front)
        }
        return next.copy(tabs = tidyTabs(next.tabs, d.turn), working = false, ai = null, facts = TurnFacts())
    }

    /** Ai's unkept, unselected tabs from earlier turns than [turn], beyond the newest [KEEP_OLD_AI_TABS], closed. */
    fun tidyTabs(tabs: BrowserTabs, turn: Int): BrowserTabs {
        val old = tabs.tabs.filter { it.byAi && !it.kept && it.turn < turn && it.id != tabs.selected }.sortedByDescending { it.opened }
        return old.drop(KEEP_OLD_AI_TABS).fold(tabs) { acc, t -> acc.close(t.id) }
    }
}
