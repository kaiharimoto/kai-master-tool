package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.eval.PuzzleCards
import com.kaiharimoto.mastertool.core.ai.eval.Puzzles
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzles
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.PuzzleSolve
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.mapper.MapperTab

/**
 * The Mapper's puzzles photographed (Phase M, M4, `--puzzles=demo|empty|save`): `demo` puts three of the duel puzzle set on
 * the Puzzles tab as positions, solved by the real search on the set's own cards (`PuzzleCards.kit`), the first chosen;
 * `empty` the tab before any; `save` the Duel page's Save as puzzle dialog over the duel on the table.
 */
internal suspend fun studioPuzzles(h: NeueHolders, how: String, clock: FrameClock) {
    val z = h.puzzles
    when (how) {
        "save" -> {
            h.neue.page = Page.DUEL
            z.saving = true
        }
        "empty" -> {
            z.keep(Puzzles.position(Puzzles.all.first()), null)
            z.delete("set-${Puzzles.all.first().id}")
            h.mapper.tab = MapperTab.PUZZLES
            h.neue.page = Page.MAPPER
        }
        else -> {
            // The person's library print, so the answers read as current rather than "effects changed".
            val print = h.goldfishKit()
            listOf("p12", "p10", "p03").mapNotNull(Puzzles::byId).forEachIndexed { i, p ->
                val pos = Puzzles.position(p, at = 1_000L + i)
                val s = PuzzleSolve.solve(pos, PuzzleCards.kit)
                val a = s?.let { PuzzleSolve.answer(it, PuzzleCards.kit, PuzzleCards::name, 2_000L) }
                    ?.let { it.copy(effects = print.trust.library(MapPuzzles.cards(pos))) }
                println("[neue-studio] puzzle ${p.id}: ${s?.verdict} ${a?.line}")
                z.keep(pos.copy(answer = a), s)
            }
            z.selected = "set-p12"
            h.mapper.tab = MapperTab.PUZZLES
            h.neue.page = Page.MAPPER
        }
    }
    repeat(8) { clock.run(20); Thread.sleep(100) }
}
