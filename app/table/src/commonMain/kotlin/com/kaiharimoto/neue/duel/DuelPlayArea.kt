package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDrawer
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * The duel in play, drawn (1.0.74; one place since the Lounge): the table between the inspector and the log, their
 * drawers where there is no room for them, what the table waits on over its top edge, and the Spotlight over it all.
 * Neue's Duel page and the Lounge's browser table both compose this, so a friend in a browser sees kai's table.
 * Everything about the table's size is [DuelLayouter]'s; this only gives it the room. The app's own pieces come in:
 * the log's head ([logHead]) and, at a networked table, the bar of what it waits on ([netBar]).
 */
@Composable
fun DuelPlayArea(
    h: TableHost,
    duels: Duels,
    game: DuelGame,
    form: FormFactor,
    phone: Boolean,
    modifier: Modifier = Modifier,
    logHead: @Composable () -> Unit = {},
    netBar: (@Composable () -> Unit)? = null,
) {
    val c = Mu.colors
    val prefs = h.duelPrefs
    val replay = duels.replay
    BoxWithConstraints(modifier.clipToBounds()) {
        val layout = remember(maxWidth, maxHeight, prefs.twoSided, game.state.solo, form, duels.bottom, prefs.logShown) {
            DuelLayouter.solve(
                maxWidth.value, maxHeight.value,
                twoSided = prefs.twoSided && !game.state.solo,
                form = form,
                bottom = duels.bottom,
                // The log and the card beside the table, or both put away together (kai, 1.0.93): the table takes
                // their room, and they open from the Table menu as drawers.
                wantRails = prefs.logShown,
            )
        }
        // The same eyes as the last frame are the same set (1.0.92): a new one equal to it each time made every
        // part of the table that takes it draw itself again.
        val eyes = duels.viewers(prefs)
        val viewers = remember(eyes) { eyes }
        val viewer = if (viewers.size > 1) null else viewers.first()
        DuelTable(h, duels, game, layout, viewers)
        layout.inspector?.let { r ->
            Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp)) {
                if (layout.logInInspector) {
                    var tab by remember { mutableStateOf("Card") }
                    Column(Modifier.fillMaxSize()) {
                        Segmented(tab, listOf("Card", "Log"), { it }, { tab = it }, Modifier.padding(8.dp), small = true)
                        if (tab == "Card") DuelInspector(h, duels, game, viewers, Modifier.weight(1f))
                        else DuelLogRail(h, duels, game, viewer, Modifier.weight(1f), head = logHead)
                    }
                } else {
                    DuelInspector(h, duels, game, viewers, Modifier.fillMaxSize())
                }
            }
            Box(Modifier.offset((r.right + 6).dp, r.top.dp).width(1.dp).height(r.height.dp).background(c.ink12))
        }
        layout.log?.let { r ->
            Box(Modifier.offset((r.left - 7).dp, r.top.dp).width(1.dp).height(r.height.dp).background(c.ink12))
            Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp)) {
                DuelLogRail(h, duels, game, viewer, Modifier.fillMaxSize(), head = logHead)
            }
        }
        if (layout.drawers) {
            MuDrawer(duels.drawer == "card", { duels.drawer = null }, header = { FieldLabel("The card") }) {
                DuelInspector(h, duels, game, viewers, Modifier.fillMaxWidth(), fill = false)
            }
            MuDrawer(duels.drawer == "log", { duels.drawer = null }, header = { FieldLabel("Log") }) {
                DuelLogRail(h, duels, game, viewer, Modifier.fillMaxWidth().height(480.dp), head = logHead)
            }
        }
        // What a networked table waits on, over its top edge: never a row that pushes the cards down.
        // Over the table's own width, between the rails, never over their heads.
        val across = Modifier.offset(layout.field.left.dp, 4.dp).width((layout.phases.right - layout.field.left).dp)
        if (netBar != null) Box(Modifier.zIndex(95f).then(across)) { netBar() }
        // The other seat's ask to move on, for the turn player to answer (1.0.79).
        if (game.state.proposal != null && replay == null && !duels.spectating) {
            Box(Modifier.zIndex(96f).then(across)) { ProposalBar(duels, game.state) }
        }
        // A duel that has ended says so (kai, after 1.1.49): who won and how, and what comes next.
        if (replay == null) DuelResults.ending(game.state)?.let { end -> Box(Modifier.zIndex(96f).then(across)) { DuelOverBar(h, game.state, end) } }
        // Command mode's Spotlight (1.0.87): over the table and its rails, in the window's own layer.
        SpotlightWhenOpen(h, game, phone, replay == null)
    }
}

/**
 * The Spotlight's layer while it is open and no replay is (1.0.92): whether it is open is read here, so opening it and
 * every key typed in it redraws the Spotlight, never the page round it.
 */
@Composable
private fun SpotlightWhenOpen(h: TableHost, game: DuelGame, phone: Boolean, live: Boolean) {
    if (h.duel.spotlight != null && live) SpotlightLayer(h, game, phone)
}

/**
 * The other seat asks to move on (1.0.79, `DuelAction.Propose`): a row over the table's top edge for the
 * turn player to answer — Go on or Not yet — and, for the one who asked, that it is waiting. Never a dialog.
 */
@Composable
fun ProposalBar(duels: Duels, s: DuelState) {
    val c = Mu.colors
    val p = s.proposal ?: return
    val asker = DuelWords.seatName(s, p.seat)
    val what = if (p.end) "end the turn" else "go to the ${p.phase?.label} Phase"
    // At a hot-seat the screen answers for the turn player; online only the turn player's screen does.
    val answers = duels.role == null || duels.mySeat == s.active
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(if (answers) c.ink else c.paper).border(1.dp, c.ink).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val ink = if (answers) c.paper else c.ink
        Small(if (answers) "$asker asks to $what" else "Waiting for ${com.kaiharimoto.mastertool.core.duel.text.DuelWords.seatName(s, s.active)} to answer: $what", Modifier.weight(1f), color = ink, maxLines = 1)
        if (answers) {
            MuButton("Go on", { duels.answerProposal(true) }, size = BtnSize.SM)
            MuButton("Not yet", { duels.answerProposal(false) }, size = BtnSize.SM)
        }
    }
}

