package com.kaiharimoto.mastertool.core.world.apps

/**
 * What bounds an app (§8.6 point 5): limits on code a model wrote, never on what Ai knows (§11). Every number here is
 * held by `AppSandboxTest` or `UiTreeTest`.
 */
object AppLimits {
    /** `main.js`, in characters. */
    const val CODE = 512 * 1024

    /** `state.json`, in characters. */
    const val STATE = 1024 * 1024

    /** A view's tree: nodes in all … */
    const val NODES = 3_000

    /** … and how deep. */
    const val DEPTH = 24

    /** The longest string a widget may hold; longer is cut, with an ellipsis. */
    const val STRING = 20_000

    /** A table's rows on one page; more are paged by the window. */
    const val ROWS = 2_000

    /** A select's, a segmented's or a checks' options. */
    const val OPTIONS = 200

    /** Apps in one world. */
    const val APPS = 32

    /** Of Ai's apps, open at once (the desk's `MAX_APP_WINDOWS`). */
    const val WINDOWS = 8

    /** Earlier versions kept beside the code, for *Back to v2*. */
    const val VERSIONS = 3

    /** Name and description, in characters. */
    const val NAME = 32
    const val DESCRIPTION = 140

    /** Pages an app may pin with `ygo.show` in one event (§8.6 point 2). */
    const val SHOWS = 4

    /** Per call: init's, view's and on's wall clock, ms (§8.6 point 1). */
    const val INIT_MS = 2_000L
    const val VIEW_MS = 1_000L
    const val ON_MS = 10_000L

    /** A view's steps; init and on take the World's run budget. */
    const val VIEW_STEPS = 50_000_000L
    const val CALL_STEPS = 400_000_000L

    /** An app's heap, per call. */
    const val HEAP_MB = 128L

    /** Events waiting for an app (§8.5); more are dropped with a line in the window. */
    const val QUEUE = 8

    /** A `live` widget's changes a second, at most; the last always delivered. */
    const val LIVE_PER_SECOND = 8

    /** An `on` running past this shows the breathing square in the title bar; the old screen stays. */
    const val BUSY_MS = 150L

    /** The state is written this long after the last event (atomically). */
    const val SAVE_AFTER_MS = 500L
}
