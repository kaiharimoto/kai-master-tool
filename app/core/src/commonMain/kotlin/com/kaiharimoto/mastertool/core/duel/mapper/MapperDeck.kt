package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishReduce

/** What the scripts in a deck let the mapper skip, read as the goldfish reads it ([GoldfishReduce]); the mapper has no target. */
object MapperDeck {
    private fun reduce(main: List<Int>, extra: List<Int>, kit: GoldfishKit) =
        GoldfishReduce(kit, GoldfishDeck(main, extra), EndBoard("mapper", "mapper", ""))

    /** No script reads the Deck's order: tables equal but for it are one. */
    fun orderFree(main: List<Int>, extra: List<Int>, kit: GoldfishKit): Boolean = reduce(main, extra, kit).orderFree

    /** A Link Monster in the deck: zones are told apart. */
    fun zonesMatter(main: List<Int>, extra: List<Int>, kit: GoldfishKit): Boolean = reduce(main, extra, kit).zonesMatter
}
