package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeCli
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeStream
import com.kaiharimoto.mastertool.core.ai.cli.CodexCli
import com.kaiharimoto.mastertool.core.ai.cli.CodexStream
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiWire
import kotlinx.coroutines.flow.Flow
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
 * It runs in `<data>/ai/run`, a folder of its own, never a project of the person's.
 */
class CliBackend(
    private val wire: Wire,
    private val program: String,
    private val workDir: File,
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
        val launch = when (wire) {
            Wire.CLAUDE_CLI -> {
                val system = File(workDir, "system.md").apply { writeText(request.system) }
                val config = File(workDir, "mcp.json").apply { writeText(ClaudeCli.mcpConfig(mcp.url, mcp.token)) }
                ClaudeCli.launch(program, message, system.absolutePath, config.absolutePath, request.model, request.effort, request.resume, last?.images.orEmpty())
            }
            else -> {
                // Codex takes no system prompt from the command line: the first message of a
                // conversation carries the instructions, and the CLI's session keeps them.
                val prompt = if (request.resume.isNullOrBlank()) "<instructions>\n${request.system}\n</instructions>\n\n$message" else message
                // Pictures are files under Ai's folder, beside this run folder.
                val pictures = last?.images.orEmpty().map { File(workDir.parentFile, it.file) }.filter { it.isFile }.map { it.absolutePath }
                CodexCli.launch(program, prompt, workDir.absolutePath, mcp.url, mcp.token, request.model, request.effort, request.resume, pictures)
            }
        }
        val claude = ClaudeStream()
        val codex = CodexStream()
        var exit: ProcessLine.Exit? = null
        AiDesk.lines(launch.args, launch.stdin, workDir, launch.env).collect { line ->
            when (line) {
                is ProcessLine.Out -> (if (wire == Wire.CLAUDE_CLI) claude.line(line.text) else codex.line(line.text)).forEach { emit(it) }
                is ProcessLine.Exit -> exit = line
            }
        }
        val finished = if (wire == Wire.CLAUDE_CLI) claude.finished else codex.finished
        emit(
            finished ?: BackendEvent.Failed(
                (exit?.errTail?.lines()?.lastOrNull { it.isNotBlank() } ?: "").ifBlank { "${File(program).name} stopped (exit ${exit?.code ?: "?"})." },
                auth = exit?.errTail?.let { ClaudeStream.looksLikeAuth(it) } == true,
            ),
        )
    }
}
