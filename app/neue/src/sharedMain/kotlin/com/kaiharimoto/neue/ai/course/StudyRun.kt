package com.kaiharimoto.neue.ai.course

import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One step of a course study, carried in its coroutine's context so the host answers its tool calls for it, not for the
 * conversation on the panel (the person may be chatting meanwhile): which course and deck it is about, the step's own
 * turns — what a guide entry's numbers are proven against — and the room the study may take in the guide.
 */
class StudyRun(
    val courseId: String,
    val deckId: String,
    val deckName: String,
    /** The step's conversation so far, its brief aside: the person said none of it. */
    val turns: () -> List<ChatTurn>,
    /** The guide's size when the study began, the study's room in it, and its name; null when the step writes no guide. */
    val guideRoom: Triple<Int, Int, String>? = null,
) : AbstractCoroutineContextElement(Key) {
    /** What the person is told when the study used all its room in the guide. */
    @Volatile var filled: String? = null

    /**
     * The calls a command-line app made over the study's own MCP server, with their answers: its loop is its own, so they
     * never reach [turns] — and a number the step read (a chapter's "(per <author>)") is proven against them.
     */
    private val served = mutableListOf<ChatTurn>()

    fun record(call: Part.ToolUse, result: Part.ToolResult) {
        synchronized(served) {
            served += ChatTurn(Role.ASSISTANT, listOf(call))
            served += ChatTurn(Role.USER, listOf(result))
        }
    }

    /** What the step's numbers are proven against: its turns, and the tools a command-line app ran. */
    fun evidence(): List<ChatTurn> = turns() + synchronized(served) { served.toList() }

    companion object Key : CoroutineContext.Key<StudyRun>
}
