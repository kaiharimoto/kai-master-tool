package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.world.JsRuntime
import com.kaiharimoto.mastertool.core.world.WorldApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * `ygo.fx` covers the vocabulary (Phase D step 2), as R17 holds `ygo.tools` to the instruments: every word of every family
 * in `core/duel/effects` has a builder, every enum's values are the builders' words, and a script written with every
 * builder compiles to a script this build reads whole — nothing an `Unknown`. A word added to the vocabulary without a
 * builder fails here.
 */
@OptIn(ExperimentalSerializationApi::class)
class FxPreludeTest {
    private fun run(code: String): JsonElement {
        val r = JsRuntime(FxCompile.LIMITS).run(code, "t.js", WorldApi(FxHost.of(FxRef.cards)), data = true)
        assertTrue(r.ok, r.err)
        return r.data!!
    }

    /** A sealed family's words: its subclasses' serial names, the newer build's "?" aside. */
    private fun words(family: String, s: KSerializer<*>): Set<String> =
        s.descriptor.getElementDescriptor(1).elementNames.filter { it != "?" }.map { "$family:$it" }.toSet()

    private val expected: Set<String> =
        words("op", Op.serializer()) + words("filter", Filter.serializer()) + words("cond", Cond.serializer()) +
            words("num", Num.serializer()) + words("opt", Opt.serializer()) + words("proc", Proc.serializer()) +
            Kind.entries.map { "kind:" + it.name.lowercase() } + Event.entries.map { "event:" + it.name.lowercase() } +
            setOf("card", "pick", "spot", "respond", "restriction", "summon", "mat")

    @Test
    fun everyWordOfTheVocabularyHasABuilder() {
        val have = run("fx.words()").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(emptySet(), expected - have, "words with no builder")
        assertEquals(emptySet(), have - expected, "builders for no word")
    }

    @Test
    fun theBuildersEnumsAreTheVocabularysOwn() {
        val e = run("fx.enums").jsonObject
        fun of(key: String) = e[key]!!.jsonArray.map { it.jsonPrimitive.content }
        fun names(vararg v: Enum<*>) = v.map { it.name.lowercase() }
        assertEquals(names(*Kind.entries.toTypedArray()), of("kind"))
        assertEquals(names(*Where.entries.toTypedArray()), of("where"))
        assertEquals(names(*Rel.entries.toTypedArray()), of("rel"))
        assertEquals(names(*Area.entries.toTypedArray()), of("area"))
        assertEquals(names(*Timing.entries.toTypedArray()), of("timing"))
        assertEquals(names(*Event.entries.toTypedArray()), of("event"))
        assertEquals(names(*Cause.entries.toTypedArray()), of("cause"))
        assertEquals(names(*ProcKind.entries.toTypedArray()), of("procKind"))
        assertEquals(names(*Includes.entries.toTypedArray()), of("includes"))
        assertEquals(names(*CardType.entries.toTypedArray()), of("cardType"))
        assertEquals(names(*CardFrame.entries.toTypedArray()), of("frame"))
        assertEquals(names(*Stat.entries.toTypedArray()), of("stat"))
        assertEquals(names(*Join.entries.toTypedArray()), of("join"))
        assertEquals(names(*Dest.entries.toTypedArray()), of("dest"))
        assertEquals(names(*Pos.entries.toTypedArray()), of("pos"))
        assertEquals(names(*LevelRule.entries.toTypedArray()), of("levelRule"))
        assertEquals(names(*NegWhat.entries.toTypedArray()), of("negWhat"))
        assertEquals(names(*LinkRef.entries.toTypedArray()), of("linkRef"))
        assertEquals(names(*DeclareKind.entries.toTypedArray()), of("declareKind"))
        assertEquals(names(*Ban.entries.toTypedArray()), of("ban"))
        assertEquals(names(*Cmp.entries.toTypedArray()), of("cmp"))
        assertEquals(names(*DuelPhase.entries.toTypedArray()), of("phase"))
        assertEquals(CardAttribute.entries.filter { it != CardAttribute.UNKNOWN }.map { it.name.lowercase() }, of("attribute"))
    }

    /** Every builder at least once: no sense as a card, every word as data. */
    private val everything = """
        var p = fx.pick({ n: 2, upTo: true, from: ['your deck', 'their gy', 'any banished'], where: fx.any(), who: 'them', bind: 'p', pickFaceDown: true });
        var all = fx.all(fx.self(), fx.notSelf(), fx.name(900000001), fx.name('Example Lamp'), fx.nameHas('Example'), fx.kind('spell', 'Quick-Play'),
          fx.frame('tuner'), fx.attribute('light', 'dark'), fx.race('Warrior'), fx.level(1, 4), fx.rank({ min: 3 }), fx.linkRating(2),
          fx.atk(null, 2000), fx.def(1000, null), fx.faceUp(), fx.faceDown(), fx.controller('you'), fx.same('name', 'p'), fx.lowest('atk'),
          fx.highest('level'), fx.anyOf(fx.monster(), fx.spell(), fx.trap()), fx.not(fx.tuner()), fx.declared('d'));
        var n = [fx.num.const(2), fx.num.count('your gy', fx.monster()), fx.num.of('level', 'p')];
        var c = fx.cond.all(fx.cond.controls(fx.monster(), 2, 'you'), fx.cond.noMonsters('them'), fx.cond.count('your gy', fx.monster(), '>=', 3),
          fx.cond.phase('main1', 'main2'), fx.cond.yourTurn(), fx.cond.chainEmpty(), fx.cond.newest({ seat: 'them', about: fx.monster(), includes: ['search'] }),
          fx.cond.thisTurn('summoned'), fx.cond.lp('you', 'ge', 1000), fx.cond.compare(n[1], 'gt', n[0]), fx.cond.anyOf(fx.cond.theirTurn()), fx.cond.not(fx.cond.chainEmpty()));
        var does = [
          fx.move('deck_top', { from: 'your hand', faceDown: true }), fx.add({ pick: p }), fx.send({ from: 'your deck', top: true, n: 3 }),
          fx.discard({ from: 'hand' }), fx.destroy({ all: true, from: 'any monsters' }), fx.banish({ ref: 'self', faceDown: true }),
          fx.tribute({ from: 'your monsters' }), fx.returnTo('extra', { ref: 'p' }), fx.draw(2, 'them'), fx.shuffle('you', 'deck'),
          fx.reveal({ from: 'your hand', where: all }), fx.specialSummon({ from: 'your gy', pos: 'defense' }),
          fx.fusionSummon(fx.frame('fusion'), { materialsFrom: ['your hand', 'your gy'] }),
          fx.ritualSummon(fx.frame('ritual'), { tributesFrom: 'your monsters', levels: 'equal', from: 'your deck' }),
          fx.synchroSummon(fx.any()), fx.xyzSummon(fx.any()), fx.linkSummon(fx.any()),
          fx.attach({ from: 'your gy' }, 'self'), fx.detach(1, 'self'),
          fx.token({ name: 'Seed Token', attribute: 'earth', race: 'Plant', level: 1, atk: 0, def: 0, n: 2, pos: 'defense', rel: 'them' }),
          fx.negate({ what: 'effect', link: 'newest', bind: 'neg' }), fx.changeLevel({ ref: 'p', by: 1, until: 'turn' }), fx.changeLevel({ ref: 'p', to: n[2] }),
          fx.lp('them', -500), fx.payLp(1000), fx.counter({ ref: 'self' }, 'Spell', 1), fx.normalSummonAgain(fx.nameHas('Example')),
          fx.choose([[fx.draw(1)], [fx.lp('you', 100)]], { who: 'them', labels: ['Draw', 'Gain'] }),
          fx.ifThen(c, [fx.draw(1)], [fx.lp('you', 100)]),
          fx.restrict(fx.restriction('special_summon_from_extra', { except: fx.frame('synchro'), seat: 'you', until: 'turn' })),
          fx.declare('attribute', { among: fx.monster(), bind: 'd' }),
          fx.and(fx.draw(1)), fx.andIfYouDo(fx.draw(1)), fx.then(fx.draw(1)), fx.also(fx.draw(1)), fx.alongside(fx.draw(1))
        ];
        fx.card(900000001, {
          alsoNamed: ['Example'],
          summon: fx.summon({ normal: false, mustFirstBe: 'inherent', oncePerTurn: true, tributes: 1, procs: [
            fx.proc.fusion(fx.mat(1, fx.name(900000001)), fx.mat(1, fx.attribute('light'), { more: true })),
            fx.proc.synchro(fx.mat(1, fx.tuner()), fx.mat(1, fx.not(fx.tuner()), { more: true })),
            fx.proc.xyz(2, fx.monster(), 3), fx.proc.link(2, 3, fx.frame('effect'), fx.nameHas('Example')), fx.proc.ritual(),
            fx.proc.inherent({ from: 'hand', condition: fx.cond.noMonsters('you'), cost: [fx.discard({ from: 'your hand' })], opt: fx.opt.perCopy(), pos: 'defense' })
          ] }),
          effects: [
            fx.ignition('e1', { label: 'All', from: 'monsters', opt: fx.opt.byName({ times: 1, group: 'g', activate: true }), condition: c,
              cost: [fx.payLp(100)], targets: [fx.pick({ from: 'their monsters', bind: 't' })], does: does,
              leaves: [fx.restriction('activate', { seat: 'them', until: 'chain' })] }),
            fx.trigger('e2', { on: fx.on.specialSummoned('fusion', { from: 'extra', cause: 'effect' }), self: false, about: fx.monster(), timing: 'when', optional: false,
              does: [fx.draw(1)] }),
            fx.quick('e3', { from: 'gy', opt: fx.opt.perDuel(), respond: fx.respond({ seat: 'them', about: fx.monster(), includes: ['special_summon', 'search'] }),
              does: [fx.negate()] }),
            fx.activation('e4', { from: ['hand', 'spell_zone'], sameTurn: true, does: [fx.draw(1)] }),
            fx.continuous('e5', { from: 'spell_zone', leaves: [fx.restriction('normal_summon')] }),
            fx.trigger('e6', { on: fx.on.summoned(), does: [fx.draw(1)] }),
            fx.trigger('e7', { on: fx.on.normalSummoned(), does: [fx.draw(1)] }),
            fx.trigger('e8', { on: fx.on.sentToGy({ cause: 'cost' }), does: [fx.draw(1)] })
          ],
          unsupported: ['A coin toss'], notes: 'Every word'
        })
    """.trimIndent()

    @Test
    fun aScriptOfEveryWordCompilesWhole() {
        val data = assertIs<JsonObject>(run(everything))
        // Every family's every word appears in what was built.
        val seen = HashSet<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> { (e["t"] as? JsonPrimitive)?.contentOrNull?.let(seen::add); e.values.forEach(::walk) }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(data)
        val tees = (Op.serializer().descriptor.getElementDescriptor(1).elementNames + Filter.serializer().descriptor.getElementDescriptor(1).elementNames +
            Cond.serializer().descriptor.getElementDescriptor(1).elementNames + Num.serializer().descriptor.getElementDescriptor(1).elementNames +
            Opt.serializer().descriptor.getElementDescriptor(1).elementNames + Proc.serializer().descriptor.getElementDescriptor(1).elementNames).filter { it != "?" }.toSet()
        // "const" is written as a bare number once read, so it is looked for in the builder's output, as are the rest.
        assertEquals(emptySet(), tees - seen, "words the script of every word did not use")
        // And every one of them reads as this build's word: nothing unknown, nothing too deep.
        val script = assertIs<FxRead.Script>(FxCodec.read(data.toString())).script
        script.effects.forEach { assertFalse(FxWalk.unread(it), "${it.id} reads whole") }
        script.summon!!.procs.forEach { assertFalse(FxWalk.unread(it), "$it reads whole") }
        assertEquals(36, script.effects.first().does.size)
        assertEquals(Join.WITH, script.effects.first().does.last().link)
        assertEquals(Json.parseToJsonElement(FxCodec.encode(script)), Json.parseToJsonElement(FxCodec.encode(FxCodec.decode(FxCodec.encode(script))!!)))
    }

    @Test
    fun aTriggerAboutItselfIsUsedFromWhereItsEventLeavesIt() {
        val data = run("fx.trigger('e1', { on: fx.on.sentToGy(), does: [fx.draw(1)] })").jsonObject
        assertEquals(listOf("GY"), data["from"]!!.jsonArray.map { it.jsonPrimitive.content })
        val about = run("fx.trigger('e1', { on: fx.on.specialSummoned(), self: false, about: fx.monster(), from: 'hand', does: [fx.draw(1)] })").jsonObject
        assertEquals(listOf("HAND"), about["from"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
