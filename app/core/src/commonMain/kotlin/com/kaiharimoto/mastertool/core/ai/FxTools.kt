package com.kaiharimoto.mastertool.core.ai

/**
 * Effects as code, Ai's tools (Phase D step 2, `docs/phases/D.md` §3.6): reading the library, checking a card's script, and
 * offering cards to write ([request]: an offer only — the person's click puts cards on the asked list). Targets and the
 * goldfish come after. Writing a script is `world_write` to `lib/effects/<passcode>.js`, as for any file of a world, and is
 * refused for a card nobody asked for (`FxAsks.gate`).
 */
object FxTools {
    val state = ToolSpec(
        "fx_state",
        "Effects as code, read-only: the library of written effects (one per card, every printing reads it). With card, that " +
            "card's status, its script read back in words beside its printed text, and every error and warning of the legality " +
            "pass (accepted ones marked). With deck_id (or deck: 'open'), each distinct card of the deck's Main and Extra Deck " +
            "with its status. With neither, the library's counts and the cards that are broken or warned. A script is " +
            "`lib/effects/<passcode>.js` in any world, built with the prelude's fx.* words.",
        schema {
            string("card", "A card by name or passcode")
            string("deck_id", "A deck's id, or 'open' for the builder's deck")
        },
        ToolGroup.LOOK,
        phase = 3,
    )

    val check = ToolSpec(
        "fx_check",
        "Effects as code: compiles lib/effects/<passcode>.js for one card (5-second budget, shut in) and runs the legality " +
            "pass — its shape, its references, the card it is for, and lints against the card's printed text — and returns " +
            "the verdict: compiled or the error with its line, then each error and warning. Fix every error; fix a warning or " +
            "leave it for the person to accept (only the person accepts). Run it after every write.",
        schema { string("card", "The card by name or passcode", required = true) },
        ToolGroup.APP,
        phase = 3,
    )

    val request = ToolSpec(
        "fx_request",
        "Effects as code: OFFERS cards to have their effects written — it writes nothing and asks for nothing by itself. Name " +
            "cards, or a deck (deck_id, or 'open') with what of it: its engine (the cards its saved combos use and its engine " +
            "groups), one of its groups by name, one of its combos by name, or suggested (what to write first). It answers with " +
            "what is to write, to repair and already done (reused at no cost, every deck reads the one script), and the cost; the " +
            "person sees a request card with the cards, the cost, Write and Not now. Only their Write puts the cards on the asked " +
            "list and starts a session that writes them. Use it whenever the person asks for effects in words, and never write a " +
            "card that is not on the list.",
        schema {
            strings("cards", "Cards by name or passcode")
            string("deck_id", "A deck's id, or 'open' for the builder's deck")
            enum("scope", "What of the deck: engine, group, combo, suggested (default: engine)", listOf("engine", "group", "combo", "suggested"))
            string("name", "The group's or the combo's name, with scope group or combo")
        },
        ToolGroup.ASK,
        phase = 3,
    )

    val all: List<ToolSpec> = listOf(state, check, request)
}
