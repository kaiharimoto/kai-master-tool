package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ai at a Lounge room's table (`docs/LOUNGE.md`, L5), through the real driver: a person seats Ai with one of their decks,
 * both throw the opening dice, and Ai — a scripted player here, answering its cues through the very table tools a model
 * gets — takes its turn and hands it back, while the person's moves are theirs alone. Ai's spend is counted.
 */
class LoungeAiTest {
    private val dir = Files.createTempDirectory("lounge-ai").toFile()

    /** Ai's cues, answered as a player who always passes, goes first, and ends its turn. */
    private class Scripted : MatchPlayer {
        val kinds = mutableListOf<String>()

        override suspend fun cue(text: String, tools: ToolRunner): CueResult {
            val kind = text.lineSequence().first().substringAfterLast("· ").removeSuffix("]")
            kinds += kind
            val ops = when (kind) {
                "choose" -> listOf("go first")
                "play" -> listOf("end")
                "resolve" -> listOf("resolve")
                else -> listOf("pass")
            }
            tools.run(Part.ToolUse("t${kinds.size}", "duel_act", buildJsonObject { putJsonArray("ops") { ops.forEach { add(it) } } }))
            return CueResult(tokens = 100)
        }
    }

    private class Players : LoungeAiPlayers {
        val made = mutableListOf<Scripted>()
        var spent = 0L
        var released = 0
        override val name = "Ai"
        override val rules = MatchRules(paceMs = 0, turnCap = Int.MAX_VALUE)
        override fun cardText(name: String): String? = null
        override fun unavailable(): String? = null
        override fun player(seat: Int, seatName: String, deckName: String, against: String?): MatchPlayer = Scripted().also { made += it }
        override fun spent(tokens: Long) { spent += tokens }
        override fun release(player: MatchPlayer) { released++ }
    }

    private val players = Players()
    private val host = LoungeHost(dir, catalog = { DuelCatalog.NONE }, ai = { players })

    /** A member's side, in-process: what they hear, queued. */
    private inner class Member {
        val heard = LinkedBlockingQueue<LoungeWire>()
        val session: LoungeHost.Session = main { host.open(out = { heard.add(it) }) }
        fun say(w: LoungeWire) = main { session.hear(w) }
    }

    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun deck(card: Int) = "#main\n" + List(40) { card }.joinToString("\n") + "\n#extra\n!side\n"

    /** The table as [m] was last sent it, once [where] holds; fails after ten seconds. */
    private fun Member.table(where: (DuelState) -> Boolean): DuelState {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val w = heard.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val u = ((w as? LoungeWire.Table)?.wire as? Wire.Update) ?: continue
            val s = DuelMirror.state(u.view)
            if (where(s)) return s
        }
        fail("the table never came to that")
    }

    private fun eventually(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!main(what)) {
            if (System.currentTimeMillis() > until) fail("it never came to be")
            Thread.sleep(50)
        }
    }

    private inline fun <reified T : LoungeWire> Member.next(): T {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val w = heard.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (w is T) return w
        }
        fail("no ${T::class.simpleName} came")
    }

    @Test
    fun aiTakesItsSeatPlaysItsTurnAndHandsItBack() {
        val kai = Member()
        main { host.hostJoin(kai.session, "kai") }
        val ash = Member()
        ash.say(LoungeWire.Hi(nick = "Ash"))
        ash.next<LoungeWire.Welcome>()
        ash.say(LoungeWire.Create("Den"))
        val room = main { host.lounge.rooms.single().id }
        // Ai sits only where kai allows it.
        ash.say(LoungeWire.AiSeat(1, deck = "nothing"))
        assertTrue(ash.next<LoungeWire.Refused>().reason.contains("kai"))
        kai.say(LoungeWire.RoomSet(room, ai = true))
        ash.say(LoungeWire.DeckSave(null, "Ash's", deck(1001)))
        val ashDeck = ash.next<LoungeWire.Deck>().id
        ash.say(LoungeWire.DeckSave(null, "For Ai", deck(2002)))
        val aiDeck = ash.next<LoungeWire.Deck>().id
        ash.say(LoungeWire.AiSeat(1, deck = aiDeck))
        assertEquals(true, main { host.lounge.room(room)!!.seats[1].ai })
        ash.say(LoungeWire.Sit(0))
        ash.say(LoungeWire.Ready(ashDeck))
        // The duel is dealt with Ai's deck at its seat, and Ai throws its own dice; Ash throws hers.
        ash.table { it.opening != null }
        var seq = 0
        var s = ash.table { it.opening?.dice?.get(1)?.isNotEmpty() == true }
        while (s.opening?.decided != true) {
            if (s.opening?.dice?.get(0).isNullOrEmpty() || s.opening?.tied == true) ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.OpeningRoll(0)))))
            // Ash, winning, lets Ai go first; Ai, winning, goes first by its own choice.
            if (s.opening?.winner == 0) ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.GoFirst(0, first = false)))))
            s = ash.table { it.opening?.decided == true || it.opening?.winner != null || it.opening?.tied == true }
        }
        // Ai plays turn 1 and ends it: Ash's turn 2 comes to her without her lifting a finger.
        val handedBack = ash.table { it.turn == 2 && it.active == 0 }
        assertEquals(0, handedBack.active)
        assertTrue("play" in players.made.single().kinds, players.made.single().kinds.toString())
        // Counted once the cue is over, a moment after its last move reached the table.
        eventually { players.spent > 0 }
        // Ash's move is hers; Ai waits on it.
        val cues = players.made.single().kinds.size
        Thread.sleep(500)
        assertEquals(cues, players.made.single().kinds.size)
        // The duel ended, Ai's player is let go.
        ash.say(LoungeWire.End)
        eventually { players.released == 1 }
    }
}
