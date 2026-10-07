package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAuth
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * The Lounge served to a real browser (`docs/LOUNGE.md`): with `LOUNGE_PAGE` set to the built page
 * (`:guest:guestBundle`, `guest/build/lounge`), the door opens on `LOUNGE_PORT` (47391) with the passcode
 * `labrynth-night` and a few real cards, kai makes a room and sits down ready, and the door stays open
 * `LOUNGE_HOLD_MS` for a browser to come in and play the other seat; kai throws the opening dice after the browser
 * does, and gives it the first turn. Without `LOUNGE_PAGE` it does nothing.
 */
class LoungeBrowserHarness {
    @Test
    fun serveForABrowser() {
        val page = System.getenv("LOUNGE_PAGE")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val port = System.getenv("LOUNGE_PORT")?.toIntOrNull() ?: 47391
        val hold = System.getenv("LOUNGE_HOLD_MS")?.toLongOrNull() ?: 600_000L
        val dir = Files.createTempDirectory("lounge-browser").toFile()
        val catalog = DuelCatalog { code -> POOL.firstOrNull { it.id.value == code }?.let(DuelCardInfo::of) }
        // LOUNGE_AI=real (with ANTHROPIC_API_KEY): a real model at the tables and in the log, instead of the stand-in.
        val players: LoungeAiPlayers = if (System.getenv("LOUNGE_AI") == "real") LiveLoungeAi.fromEnv() ?: StandIn else StandIn
        val host = LoungeHost(dir, catalog = { catalog }, ai = { players })
        val server = LoungeServer(
            host,
            passcodeHash = { HASH },
            pool = { POOL },
            original = { null },
            artCache = File(dir, "art"),
            page = { path -> File(page, path).takeIf { it.isFile && it.canonicalPath.startsWith(page.canonicalPath) }?.readBytes() },
        )
        server.start(port, lan = false)
        println("[lounge] open at http://127.0.0.1:$port")
        val later = CoroutineScope(Dispatchers.Main)
        runBlocking {
            withContext(Dispatchers.Main) {
                lateinit var kai: LoungeHost.Session
                var seq = 0
                var threw: Pair<Int, Boolean>? = null
                val online = HashMap<String, Boolean>()
                kai = host.open(out = { w ->
                    println("[lounge] kai hears ${w::class.simpleName}")
                    if (w is LoungeWire.Said) println("[lounge] said in the room: ${w.nick}: ${w.text}")
                    // A best of three (LOUNGE_BEST_OF=3): kai concedes when asked in the room, and sides as the deck came.
                    if (w is LoungeWire.Said && w.text == "kai, concede") later.launch { kai.hear(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.Concede(0))))) }
                    if (w is LoungeWire.Siding) {
                        println("[lounge] kai sides for game ${w.game}")
                        later.launch { kai.hear(LoungeWire.Side(w.main, w.extra, w.side, first = true)) }
                    }
                    if (w is LoungeWire.State) w.lounge.rooms.firstOrNull()?.match?.let { m -> println("[lounge] match: games ${m.games}, wins ${m.wins}, siding ${m.siding}") }
                    if (w is LoungeWire.State) w.lounge.members.filter { it.id != LoungeHost.HOST }.forEach { m ->
                        println("[lounge] ${m.nick} is ${if (m.online) "here" else "away"}")
                        // Back after a drop, to a seat held for them mid-duel: what the smoke walk's cut must lead to.
                        if (m.online && online[m.id] == false && w.lounge.rooms.any { r -> r.playing && r.seats.any { it.member == m.id } }) println("[lounge] ${m.nick} came back to their seat")
                        online[m.id] = m.online
                    }
                    if (w is LoungeWire.Deck) kai.hear(LoungeWire.Ready(w.id))
                    // kai throws once the browser has, and gives the browser the first turn.
                    val o = ((w as? LoungeWire.Table)?.wire as? Wire.Update)?.view?.opening ?: return@open
                    // After this push, not inside it.
                    fun act(a: DuelAction) { val n = ++seq; later.launch { kai.hear(LoungeWire.Table(Wire.Intent(n, listOf(a)))) } }
                    // The browser's own throw, not Ai's: seat 2 must be a person's.
                    if (o.dice[1].isNotEmpty() && host.lounge.rooms.single().seats[1].let { !it.ai && it.member != null }) println("[lounge] the browser threw ${o.dice[1]}")
                    when {
                        o.first != null -> Unit
                        o.winner == 0 -> act(DuelAction.GoFirst(0, first = false))
                        o.winner == null && o.dice[1].isNotEmpty() && (o.dice[0].isEmpty() || o.tied) && threw != o.round to o.tied -> {
                            threw = o.round to o.tied
                            act(DuelAction.OpeningRoll(0))
                        }
                    }
                })
                host.hostJoin(kai, "kai")
                kai.hear(LoungeWire.Create("Locals"))
                // Ai allowed in the room, answered by a stand-in: the harness has no model.
                kai.hear(LoungeWire.RoomSet(host.lounge.rooms.single().id, ai = true))
                System.getenv("LOUNGE_BEST_OF")?.toIntOrNull()?.let { kai.hear(LoungeWire.RoomSet(host.lounge.rooms.single().id, bestOf = it)) }
                kai.hear(LoungeWire.Sit(0))
                kai.hear(LoungeWire.DeckSave(null, "kai's deck", DECK))
            }
        }
        Thread.sleep(hold)
        server.stop()
    }

    /** Ai at the harness's tables with no model: it ends its turns, and answers the room in a line. */
    private object StandIn : LoungeAiPlayers {
        override val name = "Ai"
        override val rules = MatchRules(paceMs = 300, turnCap = Int.MAX_VALUE)
        override fun cardText(name: String): String? = POOL.firstOrNull { it.name == name }?.description
        override fun unavailable(): String? = null
        override fun player(seat: Int, seatName: String, deckName: String, against: String?, library: String?, strength: String): MatchPlayer = object : MatchPlayer {
            override suspend fun cue(text: String, tools: ToolRunner): CueResult {
                val kind = text.lineSequence().first().substringAfterLast("· ").removeSuffix("]")
                val ops = when (kind) { "choose" -> "go first"; "play" -> "end"; "resolve" -> "resolve"; else -> "pass" }
                tools.run(Part.ToolUse("t", "duel_act", buildJsonObject { putJsonArray("ops") { add(ops) } }))
                return CueResult(tokens = 10)
            }
        }
        override fun spent(tokens: Long) = Unit
        override fun release(player: MatchPlayer) = Unit
        override fun talker(roomName: String, seatName: String?, strength: String): LoungeTalker = object : LoungeTalker {
            override suspend fun ask(cue: String, tools: ToolRunner, saying: (String) -> Unit): Pair<String?, CueResult> {
                val asked = cue.lineSequence().first().substringAfter(" asks: ")
                val eyes = if (seatName == null) "the table as everyone sees it" else "$seatName's seat"
                val answer = "You asked “$asked”. A stand-in answers here, with $eyes in view: the harness has no model."
                // Written a few words at a time, as a model streams.
                val words = answer.split(" ")
                for (n in 1..words.size step 3) { saying(words.take(n).joinToString(" ")); delay(250) }
                return answer to CueResult(tokens = 10)
            }
        }
        override fun release(talker: LoungeTalker) = Unit
    }

    internal companion object {
        val HASH = LoungeAuth.hash("labrynth-night", ByteArray(16) { it.toByte() }, iterations = 1_000)

        fun monster(id: Int, name: String, level: Int, attribute: Attribute, race: String, atk: Int, def: Int, effect: Boolean) = Card(
            id = CardId(id), name = name, type = if (effect) "Effect Monster" else "Normal Monster", frameType = if (effect) "effect" else "normal",
            description = "$name's text.", race = race, attribute = attribute, atk = atk, def = def, level = level,
            imageUrl = "/art/f/$id.jpg", imageUrlSmall = "/art/s/$id.jpg",
        )

        fun spell(id: Int, name: String, trap: Boolean = false) = Card(
            id = CardId(id), name = name, type = if (trap) "Trap Card" else "Spell Card", frameType = if (trap) "trap" else "spell",
            description = "$name's text.", race = "Normal", imageUrl = "/art/f/$id.jpg", imageUrlSmall = "/art/s/$id.jpg",
        )

        val POOL = listOf(
            monster(14558127, "Ash Blossom & Joyous Spring", 3, Attribute.FIRE, "Zombie", 0, 1800, effect = true),
            monster(23434538, "Maxx \"C\"", 2, Attribute.EARTH, "Insect", 500, 200, effect = true),
            monster(89631139, "Blue-Eyes White Dragon", 8, Attribute.LIGHT, "Dragon", 3000, 2500, effect = false),
            monster(46986414, "Dark Magician", 7, Attribute.DARK, "Spellcaster", 2500, 2100, effect = false),
            spell(55144522, "Pot of Greed"),
            spell(44095762, "Mirror Force", trap = true),
        )

        val DECK = "#main\n" + POOL.flatMap { c -> List(3) { c.id.value } }.let { it + it + it.take(4) }.joinToString("\n") + "\n#extra\n!side\n"
    }
}
