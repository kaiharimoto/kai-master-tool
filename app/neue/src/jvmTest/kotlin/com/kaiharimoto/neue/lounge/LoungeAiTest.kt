package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** A room's conversation that answers with what it was handed: whose eyes, and the cue itself kept for the test. */
    private class Talker(val cues: MutableList<String>) : LoungeTalker {
        override suspend fun ask(cue: String, tools: ToolRunner, saying: (String) -> Unit): Pair<String?, CueResult> {
            cues += cue
            val eyes = if ("as everyone sees it" in cue) "everyone's eyes" else "one seat's eyes"
            // Written a word at a time, as a model streams it.
            saying("Answered")
            delay(400)
            saying("Answered with")
            delay(400)
            return "Answered with $eyes." to CueResult(tokens = 50)
        }
    }

    private class Players : LoungeAiPlayers {
        val cues = mutableListOf<String>()
        val talkers = mutableListOf<String?>()
        val made = mutableListOf<Scripted>()
        var spent = 0L
        var released = 0
        override val name = "Ai"
        override val rules = MatchRules(paceMs = 0, turnCap = Int.MAX_VALUE)
        override fun cardText(name: String): String? = null
        override fun unavailable(): String? = null
        val libraries = mutableListOf<String?>()
        val strengths = mutableListOf<String>()
        override fun player(seat: Int, seatName: String, deckName: String, against: String?, library: String?, strength: String): MatchPlayer =
            Scripted().also { made += it; libraries += library; strengths += strength }
        override fun spent(tokens: Long) { spent += tokens }
        override fun release(player: MatchPlayer) { released++ }
        override fun talker(roomName: String, seatName: String?, strength: String): LoungeTalker = Talker(cues).also { talkers += seatName }
        override fun release(talker: LoungeTalker) = Unit
    }

    private val players = Players()
    private val host = LoungeHost(dir, catalog = { DuelCatalog.NONE }, ai = { players })

    /** A member's side, in-process: what they hear, queued. */
    private inner class Member {
        val heard = LinkedBlockingQueue<LoungeWire>()
        /** Ai's answers as they were being written, as this member was sent them. */
        val streamed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session: LoungeHost.Session = main { host.open(out = { w -> (w as? LoungeWire.Talk)?.streaming?.let(streamed::add); heard.add(w) }) }
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
        // A friend cannot claim one of kai's library decks: the id is kai's to give.
        ash.say(LoungeWire.DeckSave(null, "For Ai", deck(2002), library = "kais-deck"))
        val aiDeck = ash.next<LoungeWire.Deck>().id
        kai.say(LoungeWire.RoomSet(room, aiStrength = DuelPrefs.MAX))
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
        // A friend's deck is played blind, at the room's strength.
        assertEquals(listOf<String?>(null), players.libraries)
        assertEquals(listOf(DuelPrefs.MAX), players.strengths)
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

    /** The room's conversation as [m] was last sent it, once it has [n] entries. */
    private fun Member.talk(n: Int): LoungeWire.Talk {
        val until = System.currentTimeMillis() + 10_000
        var last: LoungeWire.Talk? = null
        while (System.currentTimeMillis() < until) {
            val w = heard.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (w is LoungeWire.Talk) { last = w; if (w.entries.size >= n && !w.thinking) return w }
        }
        fail("the conversation never had $n entries: ${last?.entries}")
    }

    @Test
    fun theRoomAsksAiTogetherOrOnePlayerAsksAlone() {
        val kai = Member()
        main { host.hostJoin(kai.session, "kai") }
        val ash = Member().also { it.say(LoungeWire.Hi(nick = "Ash")); it.next<LoungeWire.Welcome>() }
        val mira = Member().also { it.say(LoungeWire.Hi(nick = "Mira")); it.next<LoungeWire.Welcome>() }
        val kim = Member().also { it.say(LoungeWire.Hi(nick = "Kim")); it.next<LoungeWire.Welcome>() }
        ash.say(LoungeWire.Create("Den"))
        val room = main { host.lounge.rooms.single().id }
        mira.say(LoungeWire.Enter(room))
        kim.say(LoungeWire.Enter(room))
        // Not until kai allows it.
        ash.say(LoungeWire.AskAi("Is Ash Blossom a hand trap?"))
        assertTrue(ash.next<LoungeWire.Refused>().reason.contains("kai"))
        kai.say(LoungeWire.RoomSet(room, ai = true))
        ash.say(LoungeWire.Sit(0))
        ash.say(LoungeWire.DeckSave(null, "Ash's", deck(1001)))
        ash.say(LoungeWire.Ready(ash.next<LoungeWire.Deck>().id))
        mira.say(LoungeWire.Sit(1))
        mira.say(LoungeWire.DeckSave(null, "Mira's", deck(2002)))
        mira.say(LoungeWire.Ready(mira.next<LoungeWire.Deck>().id))
        ash.table { it.seats.all { s -> s.hand.isNotEmpty() || s.deck.isNotEmpty() } }

        // Asked for the room: everyone reads the question and the answer, and Ai saw only what is face-up.
        ash.say(LoungeWire.AskAi("What is on the field?"))
        val public = mira.talk(2)
        assertEquals(listOf("Ash", "Ai"), public.entries.map { it.who })
        assertEquals("Answered with everyone's eyes.", public.entries[1].text)
        // The room read the answer as it was written, before it was whole.
        assertTrue(mira.streamed.any { it.startsWith("Answered") }, mira.streamed.toString())
        assertTrue(public.streaming == null)
        assertTrue(public.entries.all { it.to == null })
        assertTrue(kim.talk(2).entries.size == 2)
        val publicCue = players.cues.single()
        // A card is named "#1001" with no catalog; a face-down one is a random veil ("[?123100145]") whose digits may hold
        // any run, so the bare digits prove nothing either way.
        assertTrue("#1001" !in publicCue && "#2002" !in publicCue, publicCue)

        // Asked privately from a seat: answered with that seat's eyes, to Mira alone.
        val kimBefore = kim.streamed.size
        val miraBefore = mira.streamed.size
        mira.say(LoungeWire.AskAi("What should I keep?", private = true))
        val mine = mira.talk(4)
        assertEquals(mira.session.member, mine.entries.last().to)
        // A private answer is written live to its asker alone.
        assertTrue(mira.streamed.size > miraBefore)
        assertEquals(kimBefore, kim.streamed.size)
        assertEquals("Answered with one seat's eyes.", mine.entries.last().text)
        // Ash's latest view of the room's conversation still has only the room's two lines.
        Thread.sleep(300)
        assertTrue(players.talkers.contains("Mira"))
        ash.say(LoungeWire.AskAi("And now?"))
        assertTrue(ash.talk(4).entries.none { it.to != null })

        // A watcher's ask is always the room's: a watcher's eyes are everyone's.
        kim.say(LoungeWire.AskAi("Who is winning?", private = true))
        assertTrue(kim.talk(6).entries.none { it.to != null })
    }

    @Test
    fun aiPlaysKaisLibraryDeckWithWhatItKnowsOfIt() {
        val kai = Member()
        main { host.hostJoin(kai.session, "kai") }
        val ash = Member().also { it.say(LoungeWire.Hi(nick = "Ash")); it.next<LoungeWire.Welcome>() }
        kai.say(LoungeWire.Create("Den"))
        val room = main { host.lounge.rooms.single().id }
        kai.say(LoungeWire.RoomSet(room, ai = true))
        // kai's Bring a deck: the library's id rides with it, and stays when the deck is saved again here.
        kai.say(LoungeWire.DeckSave(null, "Labrynth", deck(3003), library = "lib-7"))
        val kaiDeck = kai.next<LoungeWire.Deck>().id
        kai.say(LoungeWire.DeckSave(kaiDeck, "Labrynth", deck(3003)))
        kai.next<LoungeWire.Deck>()
        kai.say(LoungeWire.AiSeat(1, deck = kaiDeck))
        assertEquals(true, main { host.lounge.room(room)!!.seats[1].ai }, kai.heard.filterIsInstance<LoungeWire.Refused>().toString())
        ash.say(LoungeWire.Enter(room))
        ash.say(LoungeWire.Sit(0))
        ash.say(LoungeWire.DeckSave(null, "Ash's", deck(1001)))
        ash.say(LoungeWire.Ready(ash.next<LoungeWire.Deck>().id))
        // The opening: Ai throws by itself; Ash throws, and lets Ai go first when she wins.
        var seq = 0
        var st = ash.table { it.opening?.dice?.get(1)?.isNotEmpty() == true }
        while (st.opening?.decided != true) {
            if (st.opening?.dice?.get(0).isNullOrEmpty() || st.opening?.tied == true) ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.OpeningRoll(0)))))
            if (st.opening?.winner == 0) ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.GoFirst(0, first = false)))))
            st = ash.table { it.opening?.decided == true || it.opening?.winner != null || it.opening?.tied == true }
        }
        eventually { players.libraries.isNotEmpty() }
        assertEquals(listOf<String?>("lib-7"), players.libraries)
        assertEquals(listOf(DuelPrefs.STRONG), players.strengths)
    }
}
