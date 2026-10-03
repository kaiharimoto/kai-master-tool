package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.0.86, the log stays true and fast: stamped token uids and lock ids, wider secrets, a log folded once. */
class DuelLogTruthTest {
    private val m0 = Place.Zone(0, ZoneKind.MONSTER, 0)
    private val m1 = Place.Zone(0, ZoneKind.MONSTER, 1)
    private val first = DuelState.TOKEN_UIDS

    /**
     * Turn 1: Main Phase 1 (the insert goes after it), a Lock that lasts the duel, a token in M0, the Battle
     * Phase, the token attacks directly, then leaves the duel, and the lock is lifted.
     */
    private fun duel(): Pair<DuelGame, Int> {
        var g = DuelGame.start(header())
        g = g.act(DuelAction.Phase(DuelPhase.MAIN1), 0).game
        val past = g.cursor
        g = g.act(DuelAction.Lock(0, "Synchro Monsters only", Lock.UNTIL_DUEL), 0).game
        g = g.act(DuelAction.Token(0, m0, CardPosition.FACE_UP_ATK, name = "Sheep"), 0).game
        g = g.act(DuelAction.Phase(DuelPhase.BATTLE), 0).game
        g = g.act(DuelAction.Attack(0, first), 0).game
        g = g.act(DuelAction.Move(first, Place.Pile(0, PileKind.GY), how = "send"), 0).game
        g = g.act(DuelAction.Unlock(1), 0).game
        return g to past
    }

    private fun checkLaterMovesKeepTheirOwn(after: DuelGame, insertedToken: Int, insertedLock: Int) {
        val s = after.state
        assertTrue(Replays.refused(after.record()).isEmpty(), "Nothing after the insert is struck")
        // The token put in the past stays in M1; the later one attacked and left the duel.
        assertEquals(insertedToken, s.at(m1))
        assertEquals("Ram", s.cards.getValue(insertedToken).name)
        assertNull(s.cards[first])
        assertEquals(listOf(Attack(0, first, null)), s.attacks)
        // The lock lifted is the one that was lifted, not the one put in before it.
        assertEquals(listOf(insertedLock), s.locks.map { it.id })
        assertEquals("Spells only", s.locks.single().text)
    }

    @Test
    fun aTokenAndALockPutIntoThePastNeverRenumberTheOnesAfter() {
        val (g, past) = duel()
        // The numbers are written into the log as it is made.
        assertEquals(first, (g.entries.first { it.action is DuelAction.Token }.action as DuelAction.Token).uid)
        assertEquals(1, (g.entries.first { it.action is DuelAction.Lock }.action as DuelAction.Lock).id)
        val inserted = Replays.insert(
            g.record(), past,
            listOf(DuelAction.Lock(0, "Spells only", Lock.UNTIL_DUEL), DuelAction.Token(0, m1, name = "Ram")), 0,
        )
        val after = DuelGame.of(inserted)
        // A number used nowhere else in the log: the highest ever + 1.
        checkLaterMovesKeepTheirOwn(after, insertedToken = first + 1, insertedLock = 2)
        // The next token made takes a number past every one.
        val more = after.act(DuelAction.Token(0, Place.Zone(0, ZoneKind.MONSTER, 2), name = "Goat"), 0).game
        assertEquals(first + 2, more.state.at(Place.Zone(0, ZoneKind.MONSTER, 2)))
    }

    @Test
    fun aLogWrittenBeforeTheNumbersWereStampedIsSettledBeforeAnInsert() {
        val (g, past) = duel()
        // As 1.0.85 wrote it: no uid on the token, no id on the lock.
        val old = unstamped(g.record())
        assertEquals(g.state, DuelGame.of(old).state, "An old log folds as it always did")
        val after = DuelGame.of(Replays.insert(old, past, listOf(DuelAction.Lock(0, "Spells only", Lock.UNTIL_DUEL), DuelAction.Token(0, m1, name = "Ram")), 0))
        checkLaterMovesKeepTheirOwn(after, insertedToken = first + 1, insertedLock = 2)
    }

    /** A record as 1.0.85 wrote it: no uid on a token, no id on a lock. */
    private fun unstamped(r: DuelRecord): DuelRecord = r.copy(
        entries = r.entries.map { e ->
            when (val a = e.action) {
                is DuelAction.Token -> e.copy(action = a.copy(uid = null))
                is DuelAction.Lock -> e.copy(action = a.copy(id = null))
                else -> e
            }
        },
    )

    @Test
    fun aGestureTakenOutOfAnOldLogLeavesTheLaterLocksTheirIds() {
        var g = DuelGame.start(header()).act(DuelAction.Phase(DuelPhase.MAIN1), 0).game
        g = g.act(DuelAction.Lock(0, "A", Lock.UNTIL_DUEL), 0).game
        val a = g.cursor - 1
        g = g.act(DuelAction.Lock(0, "B", Lock.UNTIL_DUEL), 0).game
        g = g.act(DuelAction.Unlock(2), 0).game
        assertEquals(listOf("A"), g.state.locks.map { it.text })
        // Lock A taken out: B is still lock 2, so "lift lock 2" still lifts B.
        val cut = Replays.deleteGroup(unstamped(g.record()), a)
        assertTrue(Replays.refused(cut).isEmpty())
        assertEquals(emptyList(), DuelGame.of(cut).state.locks)
    }

    @Test
    fun aGroupThatNamesItsOwnTokenFollowsTheStampedNumber() {
        val (g, past) = duel()
        // "Token, then turn it to Defense": made against the table of the past, where it would have been `first`.
        val inserted = Replays.insert(g.record(), past, listOf(DuelAction.Token(0, m1, CardPosition.FACE_UP_ATK, name = "Ram"), DuelAction.Position(first, CardPosition.FACE_UP_DEF)), 0)
        val e = inserted.entries.map { it.action }
        assertTrue(DuelAction.Position(first + 1, CardPosition.FACE_UP_DEF) in e, e.toString())
        val s = DuelGame.of(inserted).state
        assertEquals(CardPosition.FACE_UP_DEF, s.cards.getValue(first + 1).pos)
        assertTrue(Replays.refused(inserted).isEmpty())
    }

    @Test
    fun aShuffleUndoneComesOutTheSameAfterALineOfChat() {
        val start = DuelGame.start(header())
        val shuffled = start.act(DuelAction.Shuffle(0), 0).game
        val salt = (shuffled.entries.last().action as DuelAction.Shuffle).salt
        val again = shuffled.undo().act(DuelAction.Chat(0, "hm"), 0).game.act(DuelAction.Shuffle(0), 0).game
        assertEquals(salt, (again.entries.last().action as DuelAction.Shuffle).salt)
        assertEquals(shuffled.state.seats[0].deck, again.state.seats[0].deck)
        // The next roll is another die.
        val second = shuffled.act(DuelAction.Shuffle(0), 0).game
        assertNotEquals(salt, (second.entries.last().action as DuelAction.Shuffle).salt)
        // What a log stamped keeps its values, whatever the dice are keyed to now.
        val kept = DuelGame.of(second.record())
        assertEquals(second.state, kept.state)
    }

    // ---- secrets -------------------------------------------------------------------------------------

    private val names = mapOf(
        1 to "Ash Blossom & Joyous Spring",
        2 to "Droll & Lock Bird",
        3 to "Nibiru, the Primal Being",
        4 to "Called by the Grave",
        5 to "Destiny HERO - Malicious",
        6 to "Destiny HERO - Plasma",
        7 to "Lady Labrynth of the Silver Castle",
        8 to "Dark Magician",
        9 to "Mystic Mine",
        10 to "Ghost Ogre & Snow Rabbit",
    )
    private val catalog = DuelCatalog { code -> names[code]?.let { DuelCardInfo(it, CardKind.MONSTER) } }

    /** Ai is seat 1 and holds every named card in its Deck; the person, seat 0, holds [mine] face-up on the field. */
    private fun table(mine: List<Int> = emptyList()): DuelState {
        val h = DuelHeader(seats = listOf(SeatSetup("Kai", main = mine + List(10) { 99 }), SeatSetup("Ai", main = names.keys.toList())))
        var s = DuelSetup.initial(h)
        mine.indices.forEach { i -> s = DuelFixtures.ok(s, DuelAction.Move(1 + i, Place.Zone(0, ZoneKind.MONSTER, i), CardPosition.FACE_UP_ATK)) }
        return s
    }

    private fun redact(text: String, s: DuelState = table()) = Secrets.redact(text, s, 0, 1, catalog).text

    @Test
    fun theNamesPlayersSayAreSecretToo() {
        assertEquals("I'll chain a card to that; a card is live, and a card if you go past five.", redact("I'll chain Ash Blossom to that; Droll is live, and Nibiru if you go past five."))
        assertEquals("a card is in my hand", redact("Lady Labrynth is in my hand"))
        assertEquals("I hold a card", redact("I hold Ghost Ogre"))
        // Full names still go first, whole.
        assertEquals("a card, a card", redact("Ash Blossom & Joyous Spring, [[Droll & Lock Bird]]"))
    }

    @Test
    fun shortNamesStayConservative() {
        // No head: the name only.
        assertEquals("I called it; a card", redact("I called it; Called by the Grave"))
        assertEquals("the dark side of a card", redact("the dark side of Mystic Mine"))
        // An archetype — the head of two cards — is not a card.
        assertEquals("my Destiny HERO engine, with a card", redact("my Destiny HERO engine, with Destiny HERO - Plasma"))
        // An everyday word is never a short name.
        assertTrue("mystic" in Secrets.COMMON)
        assertEquals(emptyList(), Secrets.shortNames(listOf("Mystic - Thing"), emptySet(), listOf("Mystic - Thing")))
        assertEquals(emptyList(), Secrets.shortNames(listOf("Pot - of Greed"), emptySet(), listOf("Pot - of Greed")))
        // A name the person can see is no secret, nor a short name in it.
        val shown = table(mine = listOf(1))
        // Seat 0's own card 1 is an Ash Blossom face-up on its field (the header gives seat 0 code 1).
        assertEquals("Ash Blossom is everywhere", redact("Ash Blossom is everywhere", shown))
        assertEquals(listOf("Ghost Ogre"), Secrets.shortNames(listOf("Ghost Ogre & Snow Rabbit"), setOf("droll & lock bird"), listOf("Ghost Ogre & Snow Rabbit")))
        assertEquals(emptyList(), Secrets.shortNames(listOf("Ghost Ogre & Snow Rabbit"), setOf("ghost ogre"), listOf("Ghost Ogre & Snow Rabbit")))
        // One player's table has nothing to keep.
        assertEquals("Ash Blossom", Secrets.redact("Ash Blossom", table().copy(solo = true), 0, 1, catalog).text)
    }

    @Test
    fun aQuestionsOptionsAreRedactedAndTheAnswerHandedBackAsWritten() {
        val o = Secrets.options(listOf("Ash Blossom", "Droll & Lock Bird", "No response"), table(), 0, 1, catalog)
        assertEquals(listOf("a card", "a card (2)", "No response"), o.shown)
        assertEquals("Droll & Lock Bird", Secrets.answer("a card (2)", o))
        assertEquals("Ash Blossom; chain it now", Secrets.answer("a card; chain it now", o))
        assertEquals("No response", Secrets.answer("No response", o))
        // Solo: untouched.
        val solo = Secrets.options(listOf("Ash Blossom"), table().copy(solo = true), 0, 1, catalog)
        assertEquals(listOf("Ash Blossom"), solo.shown)
    }

    // ---- the log, folded once ----------------------------------------------------------------------

    /** A long random duel: draws, sends, banishes, shuffles, tokens, phases, turns, chat, LP, locks. */
    private fun randomDuel(n: Int, seed: Int = 7): DuelGame {
        val r = Random(seed)
        var g = DuelGame.start(header(seed = seed.toLong()))
        var guard = 0
        while (g.cursor < n && guard++ < n * 20) {
            val s = g.state
            val seat = s.active
            val st = s.seats[seat]
            val a: DuelAction? = when (r.nextInt(12)) {
                0 -> if (st.deck.size > 5) DuelAction.Draw(seat) else null
                1 -> st.hand.randomOrNull(r)?.let { DuelAction.Move(it, Place.Pile(seat, PileKind.GY), how = "send") }
                2 -> st.gy.randomOrNull(r)?.let { DuelAction.Move(it, Place.Pile(seat, PileKind.BANISHED), how = "banish") }
                3 -> st.banished.randomOrNull(r)?.let { DuelAction.Move(it, Place.Pile(seat, PileKind.DECK, Place.BOTTOM), how = "return") }
                4 -> DuelAction.Shuffle(seat)
                5 -> s.freeZones(seat, ZoneKind.MONSTER).firstOrNull()?.let { DuelAction.Token(seat, it, name = "Sheep") }
                6 -> s.onField().filter { s.cards[it]?.token == true }.randomOrNull(r)?.let { DuelAction.Move(it, Place.Pile(seat, PileKind.GY)) }
                7 -> DuelAction.Phase(DuelPhase.entries[r.nextInt(DuelPhase.entries.size)])
                8 -> if (r.nextInt(4) == 0) DuelAction.EndTurn else DuelAction.Chat(seat, "line ${g.cursor}")
                9 -> DuelAction.Lp(seat, -r.nextInt(1, 1000))
                10 -> if (r.nextBoolean()) DuelAction.Lock(seat, "lock ${g.cursor}") else s.locks.randomOrNull(r)?.let { DuelAction.Unlock(it.id) }
                else -> st.hand.randomOrNull(r)?.let { u -> s.freeZones(seat, ZoneKind.SPELL).firstOrNull()?.let { DuelAction.Move(u, it, CardPosition.FACE_DOWN_ATK, "set") } }
            }
            if (a != null) g = g.act(a, seat).game
        }
        return g
    }

    private fun says(game: DuelGame, entries: List<DuelEntry>): List<String> {
        val out = ArrayList<String>()
        var s = DuelSetup.initial(game.header)
        entries.forEach { e ->
            val o = DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok
            val after = o?.state ?: s
            out += "${o != null} " + DuelWords.say(s, after, e, 0, DuelFixtures.catalog)
            s = after
        }
        return out
    }

    private fun folds(g: DuelGame) = DuelFolds<String>(g.header) { e, before, after, applied -> "$applied " + DuelWords.say(before, after, e, 0, DuelFixtures.catalog) }

    @Test
    fun theFoldedLogReadsAsAFreshFoldDoes() {
        val g = randomDuel(600)
        // An edit makes some entries no longer fit: struck through, read the same either way.
        val edited = Replays.insert(g.record(), g.floor + 3, listOf(DuelAction.Draw(0, 3)), 0).entries
        listOf(g.entries, edited).forEach { entries ->
            val f = folds(g).sync(entries)
            assertEquals(says(g, entries), f.results())
            listOf(0, 1, 31, 32, 33, entries.size / 2, entries.size - 1, entries.size).forEach { n ->
                assertEquals(DuelSetup.fold(g.header, entries.subList(0, n)).first, f.stateAt(n), "state at $n")
            }
        }
    }

    @Test
    fun aLongLogSteppedOneEntryAtATimeIsNeverFoldedFromTheStartAgain() {
        val g = randomDuel(2_000)
        val n = g.entries.size
        assertTrue(n >= 1_000, "a long duel: $n entries")
        // Played a move at a time: each move reads one entry.
        val f = folds(g)
        for (k in 1..n) f.sync(g.entries.subList(0, k))
        assertEquals(n, f.applied)
        assertEquals(says(g, g.entries), f.results())
        // A replay scrubbed through it, both ways: nothing read again.
        for (k in 0..n) f.sync(g.entries).results(k)
        for (k in n downTo 0) f.sync(g.entries).results(k)
        assertEquals(n, f.applied)
        // Undo ten entries and play another: back to the nearest table kept, at most a snapshot's worth again.
        val other = g.entries.subList(0, n - 10) + DuelEntry(n - 10, 0L, 0, 99_999, DuelAction.Chat(0, "a new future"))
        f.sync(other)
        assertTrue(f.applied <= n + DuelFolds.EVERY + 1, "applied ${f.applied}")
        assertEquals(says(g, other), f.results())
        // The tally and the host's lines read from the same cache.
        val tally = DuelTally.of(g, DuelFixtures.catalog, DuelFolds.states(g.header))
        assertEquals(DuelTally.of(g, DuelFixtures.catalog), tally)
    }
}
