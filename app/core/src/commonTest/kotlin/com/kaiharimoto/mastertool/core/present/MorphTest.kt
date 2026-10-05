package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.play.Morph
import com.kaiharimoto.mastertool.core.present.stage.Box
import kotlin.test.Test
import kotlin.test.assertEquals

/** M7: Morph moves what the two slides share, rather than fading like Fade. */
class MorphTest {
    private val stage = Box(0f, 0f, 1920f, 1080f)

    private fun text(id: String, words: String, x: Float, y: Float, w: Float = 400f, h: Float = 100f, role: String = Element.ROLE_BODY, key: String? = null) =
        Element(id, Element.TEXT, x, y, w, h, role = role, paras = listOf(Para.of(words)), morphKey = key)

    @Test
    fun partnersAreFoundByKeyThenIdThenWordsThenTitle() {
        val a = Slide("a", elements = listOf(
            text("t1", "The engine", 100f, 100f, role = Element.ROLE_TITLE),
            text("k", "keyed", 100f, 300f, key = "logo"),
            text("same", "Same words", 100f, 500f),
            text("gone", "Only here", 100f, 700f),
        ))
        val b = Slide("b", elements = listOf(
            text("t2", "The payoff", 900f, 80f, role = Element.ROLE_TITLE),
            text("k2", "renamed", 1200f, 300f, key = "logo"),
            text("x", "Same words", 1000f, 500f),
            text("new", "Only there", 100f, 900f),
        ))
        val pairs = Morph.pairs(a, stage, b, stage).associate { it.to to it.from }
        assertEquals(mapOf("k2" to "k", "x" to "same", "t2" to "t1"), pairs)
    }

    @Test
    fun anElementTravelsFromItsPartnerHome() {
        val p = Morph.Partner("a", "b", Box(0f, 0f, 200f, 100f), Box(1000f, 500f, 400f, 200f))
        val start = Morph.travel(p, 0f)
        assertEquals(-1100f, start.dx)
        assertEquals(-550f, start.dy)
        assertEquals(0.5f, start.scale)
        val home = Morph.travel(p, 1f)
        assertEquals(0f, home.dx)
        assertEquals(1f, home.scale)
    }
}
