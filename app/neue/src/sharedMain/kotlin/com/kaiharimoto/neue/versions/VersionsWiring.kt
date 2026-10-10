package com.kaiharimoto.neue.versions

import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.launch
import java.io.File

/*
 * How the app keeps each deck's versions (Phase G, G.8): a save that changes the cards by card keeps one, every deck has
 * one from the first time the pool is read, and a duplicate begins with its source's version and measurements.
 */

/** [deck] saved as [id]: its version kept, when the cards changed by card. Only once the pool is read (prints are by card). */
fun NeueHolders.keepVersion(id: String, name: String, deck: Deck) {
    val index = builder.index
    if (index.cards.isEmpty()) return
    versions.scope.launch { versions.record(id, deck, name, deps.now(), index::byId) }
}

/** Every deck in the library with a version for the cards it holds now (its last save's time): the deck as the person left it. */
suspend fun NeueHolders.sweepVersions() {
    val index = builder.index
    if (index.cards.isEmpty()) return
    deps.deckRepository.all().forEach { d -> versions.record(d.entry.id, d.entry.deck, d.entry.name, d.entry.updatedAtEpochMs, index::byId) }
}

/**
 * [to], a copy of [from] (Duplicate, or a web's copy): it begins as [from]'s version, so its lineage reads back, and takes
 * [from]'s measurements with it — Shootout's trials, the goldfish's runs, the Mapper's library and the combos — as Ai's
 * learning goes with `carryLearning`. Prep's games are not copied: the ledger reads [from]'s at that version as the copy's
 * ([com.kaiharimoto.mastertool.core.prep.MatchupLedger.gather]).
 */
fun NeueHolders.carryMeasurements(from: String, to: String) {
    val index = builder.index
    versions.scope.launch {
        val source = deps.deckRepository.byId(from)
        val copy = deps.deckRepository.byId(to) ?: return@launch
        val parentPrint = source?.let { DeckVersions.print(it.entry.deck, index::byId.takeIf { index.cards.isNotEmpty() }) }
        if (source != null && index.cards.isNotEmpty()) versions.record(from, source.entry.deck, source.entry.name, source.entry.updatedAtEpochMs, index::byId)
        versions.record(to, copy.entry.deck, copy.entry.name, deps.now(), index::byId.takeIf { index.cards.isNotEmpty() }, parentDeck = from, parentPrint = parentPrint)
        val data = Platform.dataDir
        runCatching {
            // Shootout's trials, each log saying whose deck it is now.
            val shootFrom = File(data, ShootoutPaths.folder(from))
            val shootTo = File(data, ShootoutPaths.folder(to))
            if (shootFrom.isDirectory && !shootTo.exists()) {
                shootFrom.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
                    val log = ShootoutCodec.decode(f.readText()) ?: return@forEach
                    shootTo.mkdirs()
                    File(shootTo, f.name).writeText(ShootoutCodec.encode(log.copy(deck = to)))
                }
            }
        }
        val effects = File(data, FxPaths.FOLDER)
        runCatching {
            val fish = File(effects, GoldfishCodec.path(from))
            val fishTo = File(effects, GoldfishCodec.path(to))
            if (fish.isFile && !fishTo.exists()) fish.copyTo(fishTo)
        }
        runCatching {
            val map = File(effects, MapperPaths.deck(from))
            val mapTo = File(effects, MapperPaths.deck(to))
            if (map.isDirectory && !mapTo.exists()) map.copyRecursively(mapTo)
        }
        runCatching {
            val combos = File(data, "duel/" + ComboCodec.path(from))
            val combosTo = File(data, "duel/" + ComboCodec.path(to))
            if (combos.isFile && !combosTo.exists()) combos.copyTo(combosTo)
        }
    }
}

/** [id]'s versions deleted with the deck. */
fun NeueHolders.forgetVersions(id: String) {
    versions.scope.launch { versions.forget(id) }
}
