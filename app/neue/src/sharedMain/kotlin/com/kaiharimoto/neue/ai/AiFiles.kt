package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import kotlinx.serialization.json.Json
import java.io.File

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
 * ai/run/            (a CLI's working folder: its prompt and MCP config)
 * ```
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
        file(path).delete()
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
        listOf("decks", "guides", "webs").forEach { dir -> file(dir).listFiles { f -> f.extension == "md" }?.sortedBy { it.name }?.forEach(::add) }
    }

    /** Everything Ai remembers gone: memory, skills it wrote, conversations. The folder stays. */
    fun forgetEverything() {
        listOf(Persona.FILE, MemoryKind.USER.file, MemoryKind.AGENT.file).forEach(::delete)
        listOf("decks", "guides", "webs", "skills", "sessions", "run", "cache").forEach { file(it).deleteRecursively() }
    }

    // ---- skills -------------------------------------------------------------

    fun ownSkills(): List<Skill> = file("skills").listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
        File(dir, "SKILL.md").takeIf { it.isFile }?.let { Skills.parse(it.readText(), dir.name) }
    }.orEmpty()

    fun saveSkill(skill: Skill) = write("skills/${skill.name}/SKILL.md", skill.render())

    fun deleteSkill(name: String) {
        file("skills/${Skills.slug(name)}").deleteRecursively()
    }

    // ---- conversations -------------------------------------------------------

    fun saveSession(session: AiSession) = write("sessions/${AiMemory.safeId(session.id)}.json", json.encodeToString(AiSession.serializer(), session))

    fun loadSession(id: String): AiSession? =
        read("sessions/${AiMemory.safeId(id)}.json")?.let { runCatching { json.decodeFromString(AiSession.serializer(), it) }.getOrNull() }

    fun deleteSession(id: String) = delete("sessions/${AiMemory.safeId(id)}.json")

    /** The saved conversations, newest first: id, title, when. Read lazily, turns and all, only when opened. */
    fun sessions(): List<AiSession> = file("sessions").listFiles { f -> f.extension == "json" }
        ?.mapNotNull { f -> runCatching { json.decodeFromString(AiSession.serializer(), f.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()
}
