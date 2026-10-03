package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.HouseRuling
import com.kaiharimoto.mastertool.core.duel.HouseRulingBook
import com.kaiharimoto.mastertool.core.duel.HouseRulingCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The house rulings agreed at the table (1.0.79), a part of [Duels], which forwards every member under its own name. */
internal class DuelRulings(private val d: Duels) {
    /** The rulings agreed at this table, kept in `<data>/duel/rulings.json` (synced, backed up). */
    var rulings by mutableStateOf(HouseRulingBook())
        private set
    private var rulingsRead = false

    fun loadRulings() {
        if (rulingsRead) return
        rulingsRead = true
        d.scope.launch {
            val text = withContext(Dispatchers.IO) { File(d.dir, HouseRulingCodec.PATH).takeIf { it.exists() }?.readText() }
            if (text != null) rulings = HouseRulingCodec.decode(text)
        }
    }

    /** Reads the rulings again: a sync or a restore brought new ones. */
    fun reloadRulings() {
        rulingsRead = false
        loadRulings()
    }

    fun keepRuling(code: Int?, card: String?, text: String): HouseRuling {
        val r = HouseRuling("r${Duels.now()}", text, code, card, Duels.now())
        rulings = rulings.add(r)
        writeRulings()
        return r
    }

    fun forgetRuling(id: String): Boolean {
        if (rulings.rulings.none { it.id == id }) return false
        rulings = rulings.remove(id)
        writeRulings()
        return true
    }

    private fun writeRulings() {
        val book = rulings
        d.scope.launch {
            withContext(Dispatchers.IO) {
                d.io.withLock {
                    d.dir.mkdirs()
                    val target = File(d.dir, HouseRulingCodec.PATH)
                    val temp = File(d.dir, "${target.name}.tmp")
                    temp.writeText(HouseRulingCodec.encode(book))
                    if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
                }
            }
        }
    }
}
