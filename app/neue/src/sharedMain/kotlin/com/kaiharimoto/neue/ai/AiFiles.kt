package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookPaths
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Ai's folder, `<data>/ai`: the memory files, the skills it wrote, the saved
 * conversations — markdown and JSON the person can open, read and edit (Settings →
 * Ai → What it knows opens the folder). Every write is whole-file and atomic, so a
 * crash mid-write leaves the last good copy.
 *
 * ```
 * ai/SOUL.md  USER.md  MEMORY.md
 * ai/decks/<deck id>.md   ai/webs/<web id>.md
 * ai/skills/<name>/SKILL.md
 * ai/sessions/<id>.json
 * ```
 *
 * Not here since 1.0.99: the keys (`<data>/secrets/`, [SecretFiles]) and the CLIs' working folder
 * (`<data>/cli-run/`, [CliRun]), which until 1.0.98 were `ai/credentials.*` and `ai/run/`.
 */
class AiFiles(val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun file(path: String): File = File(root, path)

    fun read(path: String): String? = file(path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    @Synchronized
    fun write(path: String, text: String) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "." + target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun delete(path: String) {
        val target = file(path)
        target.delete()
        // A skill's folder goes with its SKILL.md, so a skill undone leaves nothing behind.
        if (Skills.isPath(path)) target.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    // ---- memory -------------------------------------------------------------

    fun soul(name: String): String {
        val stored = read(Persona.FILE) ?: return Persona.default(name).also { write(Persona.FILE, it) }
        // 1.0.43–1.0.45 wrote the British "duelling"; kai's app speaks American English (1.0.46).
        if ("uelling" !in stored) return stored
        return stored.replace("duelling", "dueling").replace("Duelling", "Dueling").also { write(Persona.FILE, it) }
    }

    fun memory(kind: MemoryKind, id: String? = null, name: String): MemoryDoc =
        read(AiMemory.path(kind, id))?.let(AiMemory::parse) ?: MemoryDoc.blank(AiMemory.title(kind, name))

    fun save(kind: MemoryKind, id: String?, doc: MemoryDoc) = write(AiMemory.path(kind, id), doc.render())

    /** The entries of a memory file as the prompt shows them, or empty. */
    fun entries(kind: MemoryKind, id: String? = null): String =
        read(AiMemory.path(kind, id))?.let { AiMemory.parse(it).entries.joinToString("\n") { e -> "- $e" } }.orEmpty()

    /** Every memory file, for the person to see what Ai knows. */
    fun memoryFiles(): List<File> = buildList {
        listOf(Persona.FILE, MemoryKind.USER.file, MemoryKind.AGENT.file).map(::file).filter { it.isFile }.forEach(::add)
        listOf("decks", "guides", "webs", PlaybookPaths.DIR).forEach { dir -> file(dir).listFiles { f -> f.extension == "md" }?.sortedBy { it.name }?.forEach(::add) }
    }

    // ---- Fine Tuning's reports (1.0.54) --------------------------------------

    /** Every report filed on [deckId], oldest first. */
    fun reports(deckId: String): List<SessionReport> =
        ReportLog.read(read(ReportLog.path(deckId)))

    fun addReport(report: SessionReport) = write(
        ReportLog.path(report.deckId),
        ReportLog.write(ReportLog.add(reports(report.deckId), report)),
    )

    fun deleteReports(deckId: String) {
        delete(ReportLog.path(deckId))
        // The guide's proofs go with its deck (1.0.98).
        delete(Ledger.path(deckId))
        // Its playbook too (1.1.42), the data and its words.
        delete(PlaybookPaths.of(deckId))
        delete(PlaybookPaths.of(deckId).removeSuffix(".json") + ".md")
    }

    /** Everything Ai remembers gone: memory, skills it wrote, conversations. The folder stays. */
    fun forgetEverything() {
        listOf(Persona.FILE, MemoryKind.USER.file, MemoryKind.AGENT.file).forEach(::delete)
        listOf("decks", "guides", "reports", "webs", "skills", "sessions", "images", "run", "cache").forEach { file(it).deleteRecursively() }
        listed.clear()
    }

    // ---- skills -------------------------------------------------------------

    fun ownSkills(): List<Skill> = file("skills").listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
        File(dir, "SKILL.md").takeIf { it.isFile }?.let { Skills.parse(it.readText(), dir.name) }
    }.orEmpty()

    fun saveSkill(skill: Skill) = write(Skills.path(skill.name), skill.render())

    /** The paths of the skills Ai wrote, for a review to compare and an Undo to put back. */
    fun skillPaths(): List<String> = file("skills").listFiles { f -> f.isDirectory }
        ?.filter { File(it, "SKILL.md").isFile }
        ?.map { Skills.path(it.name) }
        ?.sorted()
        .orEmpty()

    fun deleteSkill(name: String) {
        file("skills/${Skills.slug(name)}").deleteRecursively()
    }

    // ---- conversations -------------------------------------------------------

    fun saveSession(session: AiSession) {
        val path = "sessions/${AiMemory.safeId(session.id)}.json"
        write(path, json.encodeToString(AiSession.serializer(), session))
        // The list's line for it, from the conversation in hand rather than read back.
        val f = file(path)
        listed[f.name] = Listed(f.lastModified(), f.length(), SessionSummary.of(session))
    }

    fun loadSession(id: String): AiSession? =
        read("sessions/${AiMemory.safeId(id)}.json")?.let { runCatching { json.decodeFromString(AiSession.serializer(), it) }.getOrNull() }

    fun deleteSession(id: String) {
        delete("sessions/${AiMemory.safeId(id)}.json")
        listed.remove("${AiMemory.safeId(id)}.json")
        file("images/${AiMemory.safeId(id)}").deleteRecursively()
    }

    // ---- pictures (1.0.55) ----------------------------------------------------

    /**
     * A picture the person attached, kept for the conversation [sessionId] under
     * `images/<session>/<sha1>.<ext>` and named in the turn by that path; the same picture
     * twice is one file.
     */
    fun putImage(sessionId: String, bytes: ByteArray, mime: String, width: Int, height: Int): Part.Image {
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val path = "images/${AiMemory.safeId(sessionId)}/$sha.$ext"
        val target = file(path)
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return Part.Image(path, mime, width, height)
    }

    private val encoded = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 24
    }

    /** [turns] with each picture's bytes put in, as its wire sends them; a picture whose file is gone is left empty. */
    fun hydrate(turns: List<ChatTurn>): List<ChatTurn> = turns.map { turn ->
        if (turn.parts.none { it is Part.Image }) {
            turn
        } else {
            turn.copy(parts = turn.parts.map { p ->
                if (p is Part.Image) p.copy(data = imageData(p.file)) else p
            })
        }
    }

    private fun imageData(path: String): String? = synchronized(encoded) {
        encoded[path] ?: file(path).takeIf { it.isFile }?.readBytes()?.let { java.util.Base64.getEncoder().encodeToString(it) }?.also { encoded[path] = it }
    }

    /** A picture's bytes, for drawing it in the chat. */
    fun imageBytes(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    /**
     * The saved conversations as the history lists them, newest first (1.0.92): each file read once and
     * remembered by its size and time, so opening the list reads only what changed since — the whole of
     * every conversation was decoded on the main thread each time. [warm] reads them ahead, off it.
     */
    fun summaries(): List<SessionSummary> = file("sessions").listFiles { f -> f.extension == "json" }
        ?.mapNotNull { f -> summary(f) }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()

    private fun summary(f: File): SessionSummary? {
        val modified = f.lastModified()
        val length = f.length()
        val known = listed[f.name]
        if (known != null && known.modified == modified && known.length == length) return known.summary
        val summary = runCatching { SessionSummary.of(json.decodeFromString(AiSession.serializer(), f.readText())) }.getOrNull()
        listed[f.name] = Listed(modified, length, summary)
        return summary
    }

    /** Reads the list ahead (off the main thread), so it opens at once. */
    fun warm() {
        summaries()
    }

    private class Listed(val modified: Long, val length: Long, val summary: SessionSummary?)

    /** By file name; null for a file that did not read. Written from the save thread and read from the main one. */
    private val listed = ConcurrentHashMap<String, Listed>()

    /** The saved conversations, newest first: id, title, when. Read lazily, turns and all, only when opened. */
    fun sessions(): List<AiSession> = file("sessions").listFiles { f -> f.extension == "json" }
        ?.mapNotNull { f -> runCatching { json.decodeFromString(AiSession.serializer(), f.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()
}

/** One saved conversation as the history shows it: what its line needs, not its turns. */
class SessionSummary(val id: String, val title: String, val updatedAt: Long, val mode: String, val messages: Int) {
    companion object {
        fun of(s: AiSession) = SessionSummary(s.id, s.title, s.updatedAt, s.mode, s.turns.count { it.role == Role.USER && !it.isToolResults })
    }
}
