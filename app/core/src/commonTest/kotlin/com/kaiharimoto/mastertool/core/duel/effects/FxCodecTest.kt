package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.ai.eval.PuzzleCards
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Lock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/** The vocabulary's codec (D.md §2.2, §6): every word round-trips, and what a newer build wrote is kept unread. */
class FxCodecTest {
    private val pick = Pick(
        n = 2, upTo = true, from = Area.entries.flatMap { a -> Rel.entries.map { Spot(it, a) } }, where = Filter.FaceUp, who = Rel.THEM,
        bind = "b", faceDown = true,
    )

    private val filters: List<Filter> = listOf(
        Filter.Any, Filter.Self, Filter.NotSelf, Filter.Name(FxRef.SCOUT), Filter.NameHas("Example"),
        Filter.Kind(CardType.SPELL, "Quick-Play"), Filter.Kind(CardType.MONSTER),
        *CardFrame.entries.map { Filter.Frame(it) }.toTypedArray(),
        Filter.Attribute(setOf(CardAttribute.DARK, CardAttribute.LIGHT)), Filter.Race(setOf("Dragon")),
        Filter.Level(Span(1, 4)), Filter.Rank(Span(min = 4)), Filter.LinkRating(Span(max = 2)), Filter.Atk(Span(2000)), Filter.Def(Span(0, 0)),
        Filter.FaceUp, Filter.FaceDown, Filter.Controller(Rel.THEM),
        *Stat.entries.map { Filter.Same(it, "x") }.toTypedArray(),
        Filter.Lowest(Stat.ATK), Filter.Highest(Stat.LEVEL),
        Filter.All(listOf(Filter.FaceUp, Filter.NameHas("A"))), Filter.AnyOf(listOf(Filter.Self, Filter.Kind(CardType.TRAP))),
        Filter.Not(Filter.Frame(CardFrame.TUNER)), Filter.Declared("declared"),
    )

    private val nums: List<Num> = listOf(Num.Const(3), Num.Const(-800), Num.Count(listOf(Spot(Rel.ANY, Area.FIELD)), Filter.FaceUp), Num.Of(Stat.LEVEL, "t"))

    private val conds: List<Cond> = listOf(
        Cond.Controls(Filter.NameHas("Example"), Num.Const(2), Rel.ANY), Cond.NoMonsters(Rel.THEM),
        *Cmp.entries.map { Cond.Count(listOf(Spot(Rel.YOU, Area.GY)), Filter.Any, it, Num.Const(3)) }.toTypedArray(),
        Cond.Phase(setOf(DuelPhase.MAIN1, DuelPhase.MAIN2)), Cond.Turn(Rel.YOU), Cond.ChainEmpty,
        Cond.Newest(Rel.THEM, Filter.Kind(CardType.MONSTER), Includes.entries), *Event.entries.map { Cond.ThisTurn(it) }.toTypedArray(),
        Cond.Lp(Rel.YOU, Cmp.LE, Num.Const(2000)), Cond.Compare(Num.Of(Stat.ATK), Cmp.GT, Num.Count(listOf(Spot(Rel.YOU, Area.HAND)))),
        Cond.All(listOf(Cond.ChainEmpty, Cond.Turn(Rel.THEM))), Cond.AnyOf(listOf(Cond.NoMonsters())), Cond.Not(Cond.ChainEmpty),
    )

    private val ops: List<Op> = listOf(
        *Dest.entries.map { Op.Move(pick, it, faceDown = true) }.toTypedArray(),
        Op.Add(pick), Op.Send(pick), Op.Discard(pick), Op.Destroy(pick), Op.Banish(pick, faceDown = true), Op.Tribute(pick),
        Op.Return(pick, Dest.DECK_SHUFFLED), Op.Draw(2, Rel.THEM), Op.Shuffle(Rel.ANY, Area.HAND), Op.Reveal(pick),
        *Pos.entries.map { Op.SpecialSummon(pick, it) }.toTypedArray(),
        Op.FusionSummon(Filter.Frame(CardFrame.FUSION), listOf(Spot(Rel.YOU, Area.GY))),
        *LevelRule.entries.map { Op.RitualSummon(Filter.Name(FxRef.ORACLE), levels = it) }.toTypedArray(),
        Op.SynchroSummon(Filter.NameHas("X")), Op.XyzSummon(), Op.LinkSummon(Filter.Any),
        Op.Attach(pick, "t"), Op.Detach(2), Op.Token("Sheep Token", CardAttribute.EARTH, "Beast", 1, 0, 0, n = 4, pos = Pos.DEFENSE, rel = Rel.THEM),
        *NegWhat.entries.flatMap { w -> LinkRef.entries.map { Op.Negate(w, it, "n") } }.toTypedArray(),
        Op.ChangeLevel(pick, to = Num.Const(4), until = Lock.UNTIL_DUEL), Op.ChangeLevel(pick, by = Num.Of(Stat.LEVEL, "t")),
        Op.Lp(Rel.THEM, Num.Const(-800)), Op.PayLp(Num.Const(1000)), Op.Counter(pick, "Spell", -1),
        Op.NormalSummonAgain(Filter.NameHas("Example")),
        Op.Choose(listOf(listOf(Step(Op.Draw())), listOf(Step(Op.Lp(Rel.YOU, Num.Const(500)), Join.THEN))), Rel.THEM, listOf("a", "b")),
        Op.If(Cond.ChainEmpty, listOf(Step(Op.Draw())), listOf(Step(Op.Draw(2), Join.ALSO))),
        *DeclareKind.entries.map { Op.Declare(it, Filter.Kind(CardType.MONSTER), "d$it") }.toTypedArray(), Op.Declare(DeclareKind.NAME),
        *Ban.entries.map { Op.Restrict(Restriction(it, Filter.Frame(CardFrame.SYNCHRO), Rel.THEM, Lock.UNTIL_CHAIN)) }.toTypedArray(),
    )

    private val procs: List<Proc> = listOf(
        Proc.Fusion(listOf(Mat(1, Filter.Name(1)), Mat(2, Filter.NameHas("X"), more = true))),
        Proc.Synchro(Mat(1, Filter.Frame(CardFrame.TUNER)), Mat(1, Filter.Not(Filter.Frame(CardFrame.TUNER)), more = true)),
        Proc.Xyz(2, Filter.Attribute(setOf(CardAttribute.DARK)), max = 5), Proc.Link(2, 4, Filter.Frame(CardFrame.EFFECT), Filter.NameHas("Example")),
        Proc.Ritual, Proc.Inherent(Where.GY, Cond.NoMonsters(), listOf(Step(Op.Discard(pick))), Opt.ByName(), Pos.DEFENSE),
    )

    private val opts: List<Opt> = listOf(Opt.ByName(), Opt.ByName(2, Opt.CARD), Opt.PerCopy, Opt.PerDuel)

    private fun roundTrip(s: CardScript) {
        val text = FxCodec.encode(s)
        val back = assertIs<FxRead.Script>(FxCodec.read(text), text).script
        assertEquals(s, back, text)
        assertEquals(text, FxCodec.encode(back), "written back the same")
    }

    @Test
    fun everyWordOfTheVocabularyRoundTrips() {
        val effects = buildList {
            Kind.entries.forEach { add(Effect("k${it.ordinal}", it.name, it, from = Where.entries.toSet())) }
            Event.entries.forEach { e -> Timing.entries.forEach { tm -> add(Effect("t$e$tm", kind = Kind.TRIGGER, trigger = Trigger(On(e, Where.GY, Cause.COST, ProcKind.entries), false, Filter.FaceUp, tm, false))) } }
            Cause.entries.forEach { add(Effect("c$it", kind = Kind.TRIGGER, trigger = Trigger(On(Event.SENT_TO_GY, cause = it)))) }
            add(Effect("q", kind = Kind.QUICK, respond = Respond(Rel.THEM, Filter.Kind(CardType.SPELL), Includes.entries), sameTurn = true))
            opts.forEachIndexed { i, o -> add(Effect("o$i", kind = Kind.IGNITION, opt = o)) }
            conds.forEachIndexed { i, c -> add(Effect("cond$i", kind = Kind.IGNITION, condition = c)) }
            filters.forEachIndexed { i, f -> add(Effect("f$i", kind = Kind.IGNITION, targets = listOf(Pick(where = f, top = true, all = true, ref = "self")))) }
            nums.forEachIndexed { i, n -> add(Effect("n$i", kind = Kind.IGNITION, cost = listOf(Step(Op.PayLp(n))))) }
            add(Effect("ops", kind = Kind.IGNITION, does = ops.mapIndexed { i, o -> Step(o, Join.entries[i % Join.entries.size]) }))
            add(Effect("leaves", kind = Kind.IGNITION, leaves = Ban.entries.map { Restriction(it) }))
        }
        roundTrip(
            CardScript(
                card = FxRef.SCOUT, name = "Every word", text = "0123456789ab", alsoNamed = listOf("Example", "Other"),
                summon = SummonRule(normal = false, mustFirstBe = ProcKind.SYNCHRO, procs = procs, oncePerTurn = true, tributes = 1),
                effects = effects, unsupported = listOf("its coin toss"), notes = "ours",
            ),
        )
        ProcKind.entries.forEach { roundTrip(CardScript(1, name = "x", summon = SummonRule(mustFirstBe = it))) }
        FxRef.scripts.forEach(::roundTrip)
        PuzzleCards.scripts.forEach(::roundTrip)
    }

    @Test
    fun aConstantIsABareNumberAndDefaultsAreLeftOut() {
        val s = CardScript(1, name = "Burn", effects = listOf(Effect("e1", kind = Kind.ACTIVATION, does = listOf(Step(Op.Lp(Rel.THEM, Num.Const(-800)))))))
        val text = FxCodec.encode(s)
        assertTrue("\"delta\":-800" in text, text)
        assertTrue("vocab" !in text && "\"link\"" !in text, "defaults are not written: $text")
        assertEquals(Num.Const(500), (FxCodec.decode(text.replace("-800", "{\"t\":\"const\",\"n\":500}"))!!.effects[0].does[0].op as Op.Lp).delta)
    }

    @Test
    fun aNewerBuildsWordsAreKeptUnreadAndWrittenBackAsTheyCame() {
        val text = """{"card":1,"name":"Later","effects":[{"id":"e1","kind":"IGNITION","opt":{"t":"hourly","n":2},
            "condition":{"t":"all","all":[{"t":"chain-empty"},{"t":"moon-phase","full":true}]},
            "targets":[{"where":{"t":"all","all":[{"t":"face-up"},{"t":"glows","colour":"red"}]}}],
            "does":[{"op":{"t":"draw","n":1}},{"op":{"t":"teleport","to":"moon"},"link":"THEN"},{"op":{"t":"lp","rel":"YOU","delta":{"t":"squared","of":3}}}]}],
            "summon":{"procs":[{"t":"link","min":2},{"t":"pendulum-swing","scales":[1,8]}]}}"""
        val s = assertNotNull(FxCodec.decode(text))
        val e = s.effects.single()
        assertIs<Opt.Unknown>(e.opt)
        val all = assertIs<Cond.All>(e.condition)
        assertEquals(Cond.ChainEmpty, all.all[0])
        assertEquals(JsonPrimitive("moon-phase"), assertIs<Cond.Unknown>(all.all[1]).raw["t"])
        assertIs<Filter.Unknown>((e.targets[0].where as Filter.All).all[1])
        assertEquals(Op.Draw(1), e.does[0].op)
        assertEquals(JsonPrimitive("teleport"), assertIs<Op.Unknown>(e.does[1].op).raw["t"])
        assertEquals(Join.THEN, e.does[1].link)
        assertIs<Num.Unknown>((e.does[2].op as Op.Lp).delta)
        assertIs<Proc.Link>(s.summon!!.procs[0])
        assertIs<Proc.Unknown>(s.summon!!.procs[1])
        // Never played: the walk finds every unread word.
        assertTrue(FxWalk.unread(e))
        assertTrue(FxWalk.unread(s.summon!!.procs[1]))
        // Written back as it came.
        val again = FxCodec.json.parseToJsonElement(FxCodec.encode(s)).jsonObject
        val does = again["effects"]!!.jsonArray[0].jsonObject["does"]!!.jsonArray
        assertEquals(FxCodec.json.parseToJsonElement("""{"t":"teleport","to":"moon"}"""), does[1].jsonObject["op"])
        assertEquals(FxCodec.json.parseToJsonElement("""{"t":"squared","of":3}"""), does[2].jsonObject["op"]!!.jsonObject["delta"])
        assertEquals(FxCodec.json.parseToJsonElement("""{"t":"hourly","n":2}"""), again["effects"]!!.jsonArray[0].jsonObject["opt"])
        // A known script reads as readable throughout.
        FxRef.scripts.flatMap { it.effects }.forEach { assertTrue(!FxWalk.unread(it), it.id) }
    }

    @Test
    fun aNewerVocabularyIsNeverDecodedAndBrokenFilesSaySo() {
        val newer = """{"card":5,"vocab":${FxVocab.VERSION + 1},"name":"Future","effects":[{"id":"e1","kind":"WARP"}]}"""
        val r = assertIs<FxRead.Newer>(FxCodec.read(newer))
        assertEquals(FxVocab.VERSION + 1, r.vocab)
        assertEquals(JsonPrimitive("Future"), r.raw["name"])
        assertEquals(null, FxCodec.decode(newer))
        assertIs<FxRead.Bad>(FxCodec.read("{"))
        assertIs<FxRead.Bad>(FxCodec.read("[1,2]"))
        assertIs<FxRead.Bad>(FxCodec.read("""{"name":"no card"}"""))
        // An unknown effect kind in this vocabulary is a broken script, not a guess.
        assertIs<FxRead.Bad>(FxCodec.read("""{"card":5,"name":"x","effects":[{"id":"e1","kind":"WARP"}]}"""))
        assertIs<FxRead.Bad>(FxCodec.read("{\"card\":1,\"name\":\"" + "x".repeat(FxCodec.MAX_BYTES) + "\"}"))
        // A newer script is kept out of every book.
        val book = ScriptBook.all(listOf(CardScript(5, vocab = FxVocab.VERSION + 1, name = "Future")))
        assertEquals(null, book.script(5))
    }

    @Test
    fun aHashNamesTheScriptAsWritten() {
        val a = FxRef.scripts.first()
        assertEquals(12, FxCodec.hash(a).length)
        assertEquals(FxCodec.hash(a), FxCodec.hash(FxCodec.decode(FxCodec.encode(a))!!))
        assertTrue(FxCodec.hash(a) != FxCodec.hash(a.copy(notes = "changed")))
    }

    @Test
    fun aTagRoundTrips() {
        val tag = FxTag(7, "e2", FxTag.RESOLVE, link = 2, script = "abcdef012345", verified = true)
        val text = FxCodec.json.encodeToString(FxTag.serializer(), tag)
        assertEquals(tag, FxCodec.json.decodeFromString(FxTag.serializer(), text))
        assertEquals(FxTag(1, "rule", "rule"), FxCodec.json.decodeFromString(FxTag.serializer(), """{"uid":1,"effect":"rule","part":"rule","later":1}"""))
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
