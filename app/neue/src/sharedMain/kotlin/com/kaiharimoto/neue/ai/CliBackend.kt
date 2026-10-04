package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeCli
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeStream
import com.kaiharimoto.mastertool.core.ai.cli.CliCarry
import com.kaiharimoto.mastertool.core.ai.cli.CliLaunch
import com.kaiharimoto.mastertool.core.ai.cli.CliWeb
import com.kaiharimoto.mastertool.core.ai.cli.CodexCli
import com.kaiharimoto.mastertool.core.ai.cli.CodexStream
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * A coding-plan CLI as Ai's model: Claude Code (`claude -p`) or Codex (`codex exec`),
 * installed and signed in by the person. The CLI runs its own agent loop and reaches
 * the app's tools over [mcp]; this reads its event stream into the chat's events and
 * hands back the finished answer. The CLI keeps the conversation itself, carried on
 * turn to turn by its session id ([TurnRequest.resume]), so each turn sends only the
 * new message.
 *
 * It runs in [workDir], `<data>/cli-run` ([CliRun], 1.0.99): a folder of its own outside
 * Ai's folder ([aiRoot]), never a project of the person's, holding only this turn's
 * instructions and MCP configuration while it runs. Until 1.0.98 it ran in `<data>/ai/run`,
 * beside the keys.
 */
class CliBackend(
    private val wire: Wire,
    private val program: String,
    private val workDir: File,
    private val aiRoot: File,
    private val mcp: McpHandle,
) : ModelBackend {
    override val runsOwnLoop = true

    override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
        val last = request.history.lastOrNull { it.role == Role.USER && !it.isToolResults }
        val message = last?.parts?.mapNotNull {
            when (it) {
                is Part.Context -> OpenAiWire.contextBlock(it.text)
                is Part.Text -> it.text
                else -> null
            }
        }?.joinToString("\n\n").orEmpty()
        workDir.mkdirs()
        // The CLI's own web tools only where the app offered its own: a mode that closes the web closes the CLI's.
        val web = CliWeb.offered(request.tools.map { it.name })
        // This turn's files: owner-only, named for this turn alone (a helper's turn may run beside it), gone when it ends.
        val made = mutableListOf<File>()
        fun launch(words: String, resume: String?): CliLaunch = when (wire) {
            Wire.CLAUDE_CLI -> {
                val system = CliRun.turnFile(workDir, "system", "md", request.system).also { made += it }
                // The token lives in memory for the app's lifetime; on disk only in this file, only while this turn runs.
                val config = CliRun.turnFile(workDir, "mcp", "json", ClaudeCli.mcpConfig(mcp.url, mcp.token)).also { made += it }
                ClaudeCli.launch(program, words, system.absolutePath, config.absolutePath, request.model, request.effort, resume, last?.images.orEmpty(), web)
            }
            else -> {
                // Codex takes no system prompt from the command line: the first message of a
                // conversation carries the instructions, and the CLI's session keeps them.
                val prompt = if (resume.isNullOrBlank()) "<instructions>\n${request.system}\n</instructions>\n\n$words" else words
                // Pictures are files under Ai's folder; Codex reads them itself. Its token goes by its environment, never a file.
                val pictures = last?.images.orEmpty().map { File(aiRoot, it.file) }.filter { it.isFile }.map { it.absolutePath }
                CodexCli.launch(program, prompt, workDir.absolutePath, mcp.url, mcp.token, request.model, request.effort, resume, pictures, web)
            }
        }
        try {
            var run = run(launch(message, request.resume))
            // A Claude Code conversation begun in the old working folder is not found from this one (1.0.99):
            // its words go to a new session instead, before anything of this turn was shown.
            if (wire == Wire.CLAUDE_CLI && !request.resume.isNullOrBlank() && !run.spoke && run.lostSession) {
                made.forEach { it.delete() }
                run = run(launch(CliCarry.message(request.history, message), null))
            }
            val exit = run.exit
            emit(
                run.finished ?: BackendEvent.Failed(
                    (exit?.errTail?.lines()?.lastOrNull { it.isNotBlank() } ?: "").ifBlank { "${File(program).name} stopped (exit ${exit?.code ?: "?"})." },
                    auth = exit?.errTail?.let { ClaudeStream.looksLikeAuth(it) } == true,
                ),
            )
        } finally {
            made.forEach { it.delete() }
        }
    }

    /** How one launch ended: its last word, its exit, and whether it showed any of an answer. */
    private class Run(val finished: BackendEvent?, val exit: ProcessLine.Exit?, val spoke: Boolean) {
        val lostSession: Boolean
            get() = CliCarry.lostSession((finished as? BackendEvent.Failed)?.message) || CliCarry.lostSession(exit?.errTail)
    }

    private suspend fun FlowCollector<BackendEvent>.run(launch: CliLaunch): Run {
        val claude = ClaudeStream()
        val codex = CodexStream()
        var exit: ProcessLine.Exit? = null
        var spoke = false
        AiDesk.lines(launch.args, launch.stdin, workDir, launch.env).collect { line ->
            when (line) {
                is ProcessLine.Out -> (if (wire == Wire.CLAUDE_CLI) claude.line(line.text) else codex.line(line.text)).forEach {
                    if (it is BackendEvent.TextDelta || it is BackendEvent.ToolSeen || it is BackendEvent.ReasoningDelta) spoke = true
                    emit(it)
                }
                is ProcessLine.Exit -> exit = line
            }
        }
        return Run(if (wire == Wire.CLAUDE_CLI) claude.finished else codex.finished, exit, spoke)
    }
}
