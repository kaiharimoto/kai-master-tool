package com.kaiharimoto.neue.shot

import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.layout.BandLayout
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.GroupBands
import com.kaiharimoto.mastertool.core.layout.GroupRows
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.remote.CardSetRelease
import com.kaiharimoto.mastertool.core.remote.CardSetReleases
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.builder.bandsOn
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * What the picture shows, gathered off the builder at the instant it was asked for — the desk's and the tablet's alike
 * (I.2: it was the desk's alone).
 */
internal fun shotModel(state: DeckBuilderState, neue: NeueState): ShotModel {
    // The groups are in the picture when they are on screen: a lens chosen, and no draft half-drawn.
    val showing = state.lens != Lens.DECK && state.groupDraft == null
    return ShotModel(
        name = state.deckName,
        format = state.format,
        limits = state.limits,
        date = LocalDate.now().toString(),
        latestSet = null,
        sections = DeckSection.entries.map { section ->
            ShotSection(
                section = section,
                // Each card with the artwork chosen for it, as the builder draws it.
                cards = state.deck[section].map { id ->
                    state.index.byId(id)?.let { card ->
                        val choice = neue.prefs.arts[card.id.value]
                        neue.customArt?.drawn(card, choice) ?: CardArt.show(card, choice?.let(::CardId))
                    }
                },
                keying = if (showing) state.keying(section).takeIf { !it.isEmpty } else null,
                // The bands the builder is showing, or the same deck laid out for the picture's shape.
                bands = if (section == DeckSection.MAIN) shotBands(state, neue) else null,
            )
        },
        lens = if (showing) state.lens.displayName else null,
        ink = neue.prefs.theme == NeueTheme.INK,
        foil = neue.prefs.foil,
        names = neue.prefs.foilNames,
        style = ShotStyle.of(neue.prefs.shotStyle),
    )
}

/** The bands the builder is showing, or the same deck laid out for the picture's shape; null when it shows none. */
private fun shotBands(state: DeckBuilderState, neue: NeueState): BandLayout? {
    if (!bandsOn(state, neue, neue.phoneColumns)) return null
    val ids = state.deck[DeckSection.MAIN]
    val shown: BandLayout? = neue.bandCache.last
    if (shown != null && shown.row.size == ids.size) return shown
    val keying = state.keying(DeckSection.MAIN)
    val fitted = state.groups.fitted.map { it.value }
    if (neue.prefs.arrangement == GroupArrangement.SEPARATE) {
        return GroupRows.layout(ids.map { it.value }, keying.keyOfCell, keying.keyOrder, 1600f to 900f, setOrder = fitted)
    }
    return GroupBands.layout(ids.map { it.value }, keying.keyOfCell, keying.keyOrder, 1600f to 900f, setOrder = fitted)
}

/** The file name the picture is offered under: the deck's name and the day, nothing a file system refuses. */
internal fun shotFileName(model: ShotModel): String {
    val safe = model.name.ifBlank { "Deck" }.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
    return "$safe ${model.date}.png"
}

/** The newest TCG set as of a day, for the picture's foot: asked of YGOPRODeck once a run of the app. */
internal class ShotSets {
    private var sets: List<CardSetRelease>? = null
    private val api by lazy { YgoProDeckApi(HttpClientFactory.create()) }

    suspend fun latest(today: String): CardSetRelease? {
        val list = sets ?: withTimeoutOrNull(10_000) { api.fetchCardSets().getOrNull() }?.also { sets = it }
        return list?.let { CardSetReleases.latest(it, today) }
    }
}
