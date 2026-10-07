package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.ActivityLine
import com.kaiharimoto.neue.ai.QuestionCard
import com.kaiharimoto.neue.ai.ReasoningView
import com.kaiharimoto.neue.ai.ReplyView
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The buttons that hand Ai a cue (1.0.80): what fits the table now. Ai's link on top of the chain: No
 * response or Respond. Responding: Done. The person's own link on top: Over to you. Otherwise: Your move,
 * Catch up. While Ai answers: what it is doing, and Stop. A question it asks stands here too. From 1.0.86 each has
 * a key, shown in its tip: Y is the first button whatever it is, Shift Y Catch up, Esc Stop, and a digit picks a
 * question's option — so a duel against Ai needs no mouse.
 */
@Composable
internal fun AiCues(h: NeueHolders, duels: Duels, game: DuelGame, talking: Boolean) {
    val c = Mu.colors
    val ai = h.ai
    val q = ai.question
    if (q != null && talking) {
        // Its options take the digit keys while it stands here (1.0.86).
        Box(Modifier.fillMaxWidth().padding(8.dp)) { QuestionCard(ai, q, numbered = true) }
        return
    }
    // What Ai's watches wait for, by kind, never by card — behind Thinking, as the rest of its plans are (1.0.85).
    val live = DuelTriggers.alive(duels.watches, game.state.turn)
    if (h.neue.prefs.duel.aiThinking && h.neue.prefs.duel.aiTriggers && live.isNotEmpty() && !duels.aiAnswering) {
        Small(
            "${ai.name} is watching for ${com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.kindsWords(live).lowercase()}",
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), color = c.ink45, maxLines = 2,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The first button is the Y key's (1.0.86): both read [aiCueNow], so they never disagree.
        val answer = cueKey(DeskAction.DUEL_AI_ANSWER)
        when (val cue = aiCueNow(h, game)) {
            // Woken by a watch (1.0.85): the person's moves wait on its answer, unless they go on.
            AiCue.DONT_WAIT -> {
                AiMark(18.dp, name = ai.name)
                Small(
                    if (duels.held != null) "${ai.name} may respond before the phase moves on" else "${ai.name} may respond — your move waits",
                    Modifier.weight(1f), color = c.ink, maxLines = 1,
                )
                Tip("Go on without ${ai.name}'s answer", kbd = answer, above = true) {
                    MuButton("Don't wait", { giveCue(h, cue) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
            }
            AiCue.BUSY -> {
                AiMark(18.dp, name = ai.name)
                Small(ai.working ?: ai.status ?: "${ai.name} is thinking", Modifier.weight(1f), color = c.ink70, maxLines = 1)
                Tip("Stop ${ai.name} where it is", kbd = cueKey(DeskAction.DISMISS), above = true) {
                    MuButton("Stop", { ai.stop() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
            }
            AiCue.DONE -> {
                Small("Respond on the table, then:", Modifier.weight(1f), color = c.ink70, maxLines = 1)
                Tip("Tell ${ai.name} you have responded", kbd = answer, above = true) {
                    MuButton("Done", { giveCue(h, cue) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                }
            }
            AiCue.NO_RESPONSE -> {
                Tip("Let ${ai.name}'s link resolve", kbd = answer, above = true) {
                    MuButton("No response", { giveCue(h, cue) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                }
                MuButton("Respond", { duels.aiResponding = true }, size = BtnSize.SM)
                Box(Modifier.weight(1f))
            }
            AiCue.PASS -> {
                Tip("Pass priority to ${ai.name}", kbd = answer, above = true) {
                    MuButton("Over to you", { giveCue(h, cue) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                }
                Small("or keep acting: you hold priority", Modifier.weight(1f), color = c.ink45, maxLines = 1)
            }
            AiCue.YOUR_MOVE -> {
                Tip("${ai.name} responds, or plays its turn", kbd = answer, above = true) {
                    MuButton(Cue.YOUR_MOVE.shown, { giveCue(h, cue) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                }
                Tip("${ai.name} reads what you did and asks; it moves nothing", kbd = cueKey(DeskAction.DUEL_AI_CATCH_UP), above = true) {
                    MuButton(Cue.CATCH_UP.shown, { catchUp(h) }, size = BtnSize.SM)
                }
                Box(Modifier.weight(1f))
            }
        }
    }
}

internal fun cueKey(action: DeskAction): String? = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/** Ai's conversation at this table is the one open: the log's foot speaks for it (its question, Stop). */
internal fun duelTalking(h: NeueHolders): Boolean {
    val session = h.ai.session ?: return false
    return aiAtTable(h) && session.mode == AiSession.MODE_DUEL && session.id == h.duel.aiSession
}

/** What the log's foot offers Ai now (1.0.86): its buttons and the Y key read this one answer. */
internal fun aiCueNow(h: NeueHolders, game: DuelGame): AiCue {
    val duels = h.duel
    val s = game.state
    val seat = if (s.solo) 0 else h.neue.prefs.duel.aiSeat
    return AiCue.primary(
        waiting = duels.aiAnswering || duels.held != null,
        running = h.ai.running && duelTalking(h),
        responding = duels.aiResponding,
        topSeat = s.chain.lastOrNull()?.seat,
        aiSeat = seat,
        solo = s.solo,
    )
}

/** [cue] given, by its button or by its key. */
internal fun giveCue(h: NeueHolders, cue: AiCue) {
    val duels = h.duel
    when (cue) {
        AiCue.DONT_WAIT -> duels.dontWait()
        AiCue.BUSY -> Unit
        AiCue.DONE -> { duels.say(Cue.DONE.shown); cueAi(h, Cue.DONE) }
        AiCue.NO_RESPONSE -> { duels.say(Cue.NO_RESPONSE.shown); cueAi(h, Cue.NO_RESPONSE) }
        AiCue.PASS -> { duels.say(Cue.PASS.shown); cueAi(h, Cue.PASS) }
        AiCue.YOUR_MOVE -> { duels.say(Cue.YOUR_MOVE.shown); askAiToPlay(h) }
    }
}

/** Ai's cue typed or spoken on the Line (1.0.87): what its button does; false when no Ai sits at the table. */
internal fun lineCue(h: NeueHolders, u: DuelCommand.Parsed.Ui): Boolean {
    if (!aiAtTable(h)) return false
    val cue = u.cue
    when {
        cue != null -> giveCue(h, cue)
        u.arg == DuelCommand.CUE_CATCH_UP -> catchUp(h)
        u.arg == DuelCommand.CUE_RESPOND -> h.duel.aiResponding = true
    }
    return true
}

internal fun catchUp(h: NeueHolders) {
    h.duel.say(Cue.CATCH_UP.shown)
    cueAi(h, Cue.CATCH_UP)
}

/**
 * A digit while Ai's question stands in the log's foot (1.0.86): option [n], as a click on it would — answered, or
 * picked when it asks for several. False when no question of the duel's is showing, and the digit is a zone's.
 */
internal fun answerByDigit(h: NeueHolders, n: Int): Boolean {
    val q = h.ai.question ?: return false
    if (!duelTalking(h)) return false
    val option = q.options.getOrNull(n - 1) ?: return false
    if (q.multiple) q.picked = if (option in q.picked) q.picked - option else q.picked + option else q.reply(option)
    return true
}

/** Enter while Ai asks for several answers and some are picked: sent, as its Answer button would. */
internal fun answerPicked(h: NeueHolders): Boolean {
    val q = h.ai.question ?: return false
    if (!duelTalking(h) || !q.multiple || q.picked.isEmpty()) return false
    q.reply((q.picked + listOfNotNull(q.typed.trim().takeIf { it.isNotEmpty() })).joinToString("; "))
    return true
}

