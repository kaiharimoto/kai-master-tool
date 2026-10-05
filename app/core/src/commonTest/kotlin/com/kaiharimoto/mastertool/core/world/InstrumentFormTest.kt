package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstrumentFormTest {
    @Test
    fun everyInstrumentHasAForm() {
        assertEquals(Instruments.ALL.map { it.name }, InstrumentForm.ALL.map { it.instrument })
    }

    @Test
    fun everyArgumentOfEveryInstrumentHasAField() {
        Instruments.ALL.forEach { spec ->
            val declared = InstrumentForm.declared(spec)
            assertTrue(declared.isNotEmpty(), "${spec.name} declares no arguments?")
            val fields = InstrumentForm.of(spec.name)!!.fields.map { it.name }.toSet()
            assertTrue((declared - fields).isEmpty(), "${spec.name}: no field for ${declared - fields}")
            assertTrue((fields - declared).isEmpty(), "${spec.name}: a field for nothing it takes, ${fields - declared}")
        }
    }

    @Test
    fun theArgumentsAreReadOffTheSpecsWords() {
        assertEquals(setOf("deck", "conditions", "groups", "goal", "trials", "seed", "samples"), InstrumentForm.declared(Instruments.ALL.first { it.name == "openings" }))
        assertEquals(
            setOf("deck", "condition", "card", "group", "grow", "from", "to", "cut", "also", "keep_size", "groups"),
            InstrumentForm.declared(Instruments.ALL.first { it.name == "ratios" }),
        )
        assertEquals(setOf("deck", "out", "in", "conditions", "groups"), InstrumentForm.declared(Instruments.ALL.first { it.name == "siding" }))
    }

    @Test
    fun aFormTurnsWhatWasTypedIntoArguments() {
        val f = InstrumentForm.of("openings")!!
        val a = f.args(mapOf("deck" to "d1", "conditions" to "Starters>=1\n\n Hand traps>=1 ", "trials" to "1000", "seed" to "")).getOrThrow()
        assertEquals(JsonPrimitive("d1"), a["deck"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Starters>=1"), JsonPrimitive("Hand traps>=1"))), a["conditions"])
        assertEquals(JsonPrimitive(1000L), a["trials"])
        assertTrue("seed" !in a, "a blank field leaves the instrument's default")
        assertTrue(f.args(mapOf("trials" to "lots")).isFailure)
        assertTrue(f.args(mapOf("trials" to "2000000")).isFailure, "past its bound")
        val o = InstrumentForm.of("optimize")!!.args(mapOf("roles" to """{"Starters":[8,15]}""", "size" to "40-42", "turn" to "first")).getOrThrow()
        assertTrue(o["roles"] is JsonObject)
        assertEquals(JsonArray(listOf(JsonPrimitive(40L), JsonPrimitive(42L))), o["size"])
        assertTrue(InstrumentForm.of("optimize")!!.args(mapOf("roles" to "{nope")).isFailure)
        val c = InstrumentForm.of("combos")!!.args(mapOf("combos" to """[{"name":"x","needs":["A"]}]""")).getOrThrow()
        assertTrue(c["combos"] is JsonArray)
        val w = InstrumentForm.of("card_web")!!.args(mapOf("include" to "main, side")).getOrThrow()
        assertEquals(JsonArray(listOf(JsonPrimitive("main"), JsonPrimitive("side"))), w["include"])
        val d = InstrumentForm.of("draws")!!.args(mapOf("extra" to "1, 1, 2")).getOrThrow()
        assertEquals(3, (d["extra"] as JsonArray).size)
    }
}
