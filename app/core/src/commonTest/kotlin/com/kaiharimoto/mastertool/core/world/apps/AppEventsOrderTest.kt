package com.kaiharimoto.mastertool.core.world.apps

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A change never jumps a press (found photographing the matchup tracker): choose an opponent, Log, choose another, Log —
 * each Log must read the opponent chosen just before it. Only a change still last in the queue is replaced.
 */
class AppEventsOrderTest {
    @Test
    fun aChangeBehindAPressKeepsItsPlace() {
        val q = AppEvents()
        q.offer("opp", UiEvent.CHANGE, JsonPrimitive("A"))
        q.offer("log", UiEvent.PRESS, JsonPrimitive(true))
        assertEquals(Offered.QUEUED, q.offer("opp", UiEvent.CHANGE, JsonPrimitive("B")))
        q.offer("log", UiEvent.PRESS, JsonPrimitive(true))
        val order = generateSequence { q.take() }.map { "${it.id}=${it.value}" }.toList()
        assertEquals(listOf("opp=\"A\"", "log=true", "opp=\"B\"", "log=true"), order)
    }

    @Test
    fun aSliderMovingReplacesItsOwnLastChange() {
        val q = AppEvents()
        q.offer("x", UiEvent.CHANGE, JsonPrimitive(1))
        assertEquals(Offered.REPLACED, q.offer("x", UiEvent.CHANGE, JsonPrimitive(2)))
        assertEquals(1, q.size)
        assertEquals(JsonPrimitive(2), q.take()!!.value)
    }
}
