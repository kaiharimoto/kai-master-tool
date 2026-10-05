package com.kaiharimoto.mastertool.core.ai

/**
 * Effects as code, Ai's tools (Phase D step 2, `docs/phases/D.md` §3.6): reading the library and checking a card's script.
 * Checking only at this step — asking (`fx_request`, the person's go and the asked list), targets and the goldfish come
 * after, on top of these. Writing a script is `world_write` to `lib/effects/<passcode>.js`, as for any file of a world.
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

    val all: List<ToolSpec> = listOf(state, check)
}
