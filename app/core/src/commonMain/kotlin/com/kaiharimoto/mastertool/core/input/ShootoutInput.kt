package com.kaiharimoto.mastertool.core.input

/**
 * The mouse and the finger on Shootout (1.1.2, Phase S), as data — a table pair beside [PresentMouse] and
 * [PresentTouch], because a hand being judged is not a deck being built: its cards are read, never moved. The help
 * dialog renders both, and a test holds every mouse action to a finger's form.
 *
 * The keys are [DeskShortcuts]' `SHOOTOUT` rows: 1 to 5 answer, ← and → choose in a comparison.
 */
enum class ShootoutTarget(val heading: String) {
    ANSWER("The five answers"),
    TRIAL("A hand being judged"),
    HAND("A hand in a comparison"),
    CARD("A card in a hand"),
    NUMBER("A number in the results"),
    VERDICT("Ai's answer, supervised"),
}

enum class ShootoutAction {
    /** The hand answered on the five-point scale. */
    ANSWER,

    /** This hand, of the two. */
    PREFER,

    /** The card's name and text, read without leaving the trial. */
    READ,

    /** The trials behind the number, listed. */
    OPEN_TRIALS,

    /** Ai's answer taken as the person's (supervised, stage 3). */
    ACCEPT,
}

data class ShootoutBinding(
    val target: ShootoutTarget,
    /** The words of the gesture, as the help dialog prints them. */
    val gesture: String,
    val action: ShootoutAction,
    val description: String,
)

object ShootoutMouse {
    val all: List<ShootoutBinding> = listOf(
        ShootoutBinding(ShootoutTarget.ANSWER, "Click", ShootoutAction.ANSWER, "Answer with it"),
        ShootoutBinding(ShootoutTarget.HAND, "Click", ShootoutAction.PREFER, "Open with this hand rather than the other"),
        ShootoutBinding(ShootoutTarget.CARD, "Hover", ShootoutAction.READ, "Read it below the hands"),
        ShootoutBinding(ShootoutTarget.NUMBER, "Click", ShootoutAction.OPEN_TRIALS, "List the trials behind it"),
        ShootoutBinding(ShootoutTarget.VERDICT, "Click Accept", ShootoutAction.ACCEPT, "Take Ai's answer as yours; an answer box corrects it"),
    )
}

object ShootoutTouch {
    val all: List<ShootoutBinding> = listOf(
        ShootoutBinding(ShootoutTarget.ANSWER, "Tap", ShootoutAction.ANSWER, "Answer with it"),
        ShootoutBinding(ShootoutTarget.TRIAL, "Swipe right or left", ShootoutAction.ANSWER, "A win or a loss; a long swipe is the clear one"),
        ShootoutBinding(ShootoutTarget.TRIAL, "Swipe up", ShootoutAction.ANSWER, "A coin flip"),
        ShootoutBinding(ShootoutTarget.HAND, "Tap", ShootoutAction.PREFER, "Open with this hand rather than the other"),
        ShootoutBinding(ShootoutTarget.CARD, "Press and hold", ShootoutAction.READ, "Read it below the hands"),
        ShootoutBinding(ShootoutTarget.NUMBER, "Tap", ShootoutAction.OPEN_TRIALS, "List the trials behind it"),
        ShootoutBinding(ShootoutTarget.VERDICT, "Tap Accept", ShootoutAction.ACCEPT, "Take Ai's answer as yours; an answer box corrects it"),
    )
}
