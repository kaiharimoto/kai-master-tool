package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.WorldApi
import com.kaiharimoto.mastertool.core.world.WorldHost

/** An app's code at one version: what a runner compiles once and caches by [slug] and [version]. */
data class AppCode(val slug: String, val version: Int, val source: String)

/** What one call of an app came to (§8.5). */
sealed interface AppCall<out T> {
    val ms: Long

    /** [shown] are the pages it pinned with `ygo.show` (at most [AppLimits.SHOWS]); [printed] what it printed, for Show code. */
    data class Ok<T>(val value: T, override val ms: Long, val shown: List<WorldApi.Shown> = emptyList(), val printed: List<String> = emptyList()) : AppCall<T>

    /**
     * A throw, a stop or a limit: [call] is the function and the event (`on(press draw)`), [error] the error in words,
     * [line] where in `main.js`. The state stays as it was; a failed view keeps the last good screen (§8.5).
     */
    data class Failed(val call: String, val error: String, val line: Int? = null, override val ms: Long = 0L, val stopped: Boolean = false) : AppCall<Nothing> {
        /** One line for over the app: "on(press draw) failed at line 41: TypeError: …". */
        val words: String get() = "$call failed" + (line?.let { " at line $it" }.orEmpty()) + ": " + error.lineSequence().firstOrNull().orEmpty()
    }
}

/**
 * Runs an app's three pure functions (§8.1, §8.5): `init()` the first state, `view(state)` the screen, `on(state, event)`
 * the next state. State goes in and comes out as JSON text; no JavaScript survives between calls, so an app holds no
 * timer, socket, handle or secret, and a restart, a sync or a backup brings it back exactly. `JsApp` (jvmMain) runs them
 * on Rhino in the World's cage; calls block their caller, so the host runs them on the apps' own threads.
 *
 * [host] is the app as a world's scripts see it (`ygo.*`), read now.
 */
interface AppRunner {
    fun init(app: AppCode, host: WorldHost): AppCall<String>

    fun view(app: AppCode, state: String, host: WorldHost): AppCall<UiTree>

    fun on(app: AppCode, state: String, event: UiEvent, host: WorldHost): AppCall<String>

    /** A new version's `migrate(state, fromVersion)`, or [state] unchanged when the app has none (§8.3). */
    fun migrate(app: AppCode, state: String, from: Int, host: WorldHost): AppCall<String>

    /** The call under way for [slug], stopped (the person's Stop, the window closed). */
    fun stop(slug: String)

    /**
     * A new app checked before anything opens (`world_app make`): `init` and `view` once in the cage; the screen, or the
     * error with its line for Ai.
     */
    fun check(app: AppCode, host: WorldHost): AppCall<UiTree> = when (val s = init(app, host)) {
        is AppCall.Failed -> s
        is AppCall.Ok -> view(app, s.value, host)
    }
}
